package com.tingsiwei.app.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/** HTTP 往来接缝：生产用 [OkHttpTransport]，单测注入假实现（不加任何测试依赖）。 */
interface Transport {
    class RawResp(val code: Int, val body: String, val headers: Map<String, String>)
    fun post(url: String, headers: Map<String, String>, body: String): RawResp
}

interface Sleeper { suspend fun sleep(ms: Long) }
interface ClockSource { fun nowMs(): Long }

object SystemClock : ClockSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

object CoroutineSleeper : Sleeper {
    override suspend fun sleep(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}

/** 调用级错误：只有 [retryable] 里的类别进退避，其余直接交给上层处理。 */
sealed class LlmCallError(
    message: String,
    val httpCode: Int = 0,
    val retryAfterMs: Long? = null,
) : RuntimeException(message) {

    class RateLimited(code: Int, retryAfterMs: Long?, detail: String) :
        LlmCallError("接口限流中（HTTP $code）", code, retryAfterMs)

    class Server(code: Int, detail: String) :
        LlmCallError("服务端暂时不可用（HTTP $code）", code)

    class Client(code: Int, detail: String) :
        LlmCallError("接口拒绝了请求（HTTP $code）", code)

    class Network(detail: String) : LlmCallError("无法连接接口，请检查网络或接口地址")
    class Timeout(detail: String) : LlmCallError("请求超时，可稍后重试或换个更快的模型")
    class BadFormat(detail: String) : LlmCallError("接口返回的不是有效 JSON")
    class EmptyContent(detail: String) : LlmCallError("接口没有返回内容")
    class Truncated(detail: String) : LlmCallError("接口输出被长度上限截断")

    val retryable: Boolean
        get() = this is RateLimited || this is Server || this is Network || this is Timeout
}

class RateGate(
    private val clock: ClockSource = SystemClock,
    private val sleeper: Sleeper = CoroutineSleeper,
) {
    private val mutex = Mutex()
    private var nextSlotMs = 0L

    /** 最近一次发号的时间戳，测试据此确认退避等待没有占用闸口。 */
    var lastSlotMs = 0L
        private set

    /** 取号即释放锁，等待发生在锁外：退避中的请求不会把闸门堵死。 */
    suspend fun <T> acquire(minIntervalMs: Long, block: suspend (slotMs: Long) -> T): T {
        val slot = mutex.withLock {
            val now = clock.nowMs()
            val s = if (now > nextSlotMs) now else nextSlotMs
            nextSlotMs = s + minIntervalMs.coerceAtLeast(0L)
            s
        }
        lastSlotMs = slot
        val wait = slot - clock.nowMs()
        if (wait > 0) sleeper.sleep(wait)
        return block(slot)
    }
}

/** 进程级闸门：所有 chat / listModels / 测试连接共用同一个，避免多处 new 客户端时限流形同虚设。 */
object GlobalGate {
    val shared = RateGate()
}

object LlmPolicy {
    const val MAX_RETRIES = 5
    const val BASE_DELAY_MS = 1000L
    const val MAX_DELAY_MS = 60_000L
    const val MIN_OUTPUT_TOKENS = 512
    const val MIN_INPUT_ROOM_TOKENS = 256
    private const val JITTER = 0.2

    /** Retry-After 只认数字秒（个人自用，不解析 HTTP-date）。 */
    fun retryAfterMs(headerValue: String?, body: String): Long? {
        headerValue?.trim()?.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, MAX_DELAY_MS) }
        val m = Regex("\"(?:retry_after|retryAfter|reset|wait)\"\\s*:\\s*(\\d{1,5})").find(body)
        return m?.groupValues?.get(1)?.toLongOrNull()?.let { (it * 1000L).coerceIn(0L, MAX_DELAY_MS) }
    }

    /** 指数退避 + ±20% 抖动；rng 固定为 0.5 时结果精确可断言。 */
    fun backoffMs(attempt: Int, rng: () -> Double): Long {
        val base = minOf(BASE_DELAY_MS shl attempt.coerceIn(0, 20), MAX_DELAY_MS)
        val factor = 1.0 - JITTER + 2 * JITTER * rng()
        return (base * factor).toLong().coerceAtLeast(1L)
    }
}

object Tokens {

    /** 粗估：CJK 一字一 token，其余 4 字符一 token。 */
    fun estimate(s: String): Int {
        if (s.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in s) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + ceil(other / 4.0).toInt()
    }

    /** 在句末边界截断到 capTokens 以内，返回（结果, 是否截断过）。 */
    fun truncateToTokens(s: String, capTokens: Int): Pair<String, Boolean> {
        if (capTokens <= 0) return "" to s.isNotEmpty()
        val suffix = "…（内容过长，已截断）"
        val cap = (capTokens - RoundingSlack).coerceAtLeast(1)
        if (estimate(s) <= cap) return s to false
        var tokens = 0.0
        var lastBoundary = -1
        var cutIndex = s.length
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            tokens += if (isCjk(ch)) 1.0 else 0.25
            if (tokens > cap) {
                cutIndex = i
                break
            }
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' ||
                ch == '!' || ch == '?' || ch == ';'
            ) {
                lastBoundary = i + 1
            }
            i++
        }
        val cut = if (lastBoundary > 0 && lastBoundary <= cutIndex) lastBoundary else cutIndex
        return s.substring(0, cut.coerceAtLeast(0)) + suffix to true
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3000..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF
    }

    private const val RoundingSlack = 16

    class Fit(val maxOut: Int, val system: String, val user: String, val trimmed: Boolean)

    /**
     * 预算降级：宁可截输入也绝不因为"放不下"而打断一个本来能发的请求。
     * 顺序 = 压输出 → 截输入 → 缩 system → 兜底照发。
     */
    fun fit(contextWindow: Int, system: String, user: String, desiredOut: Int): Fit {
        val cw = contextWindow.coerceAtLeast(MinWindow)
        val estSys0 = estimate(system)
        val estUser = estimate(user)
        var out = desiredOut.coerceAtLeast(MinOut)
        val roomForOut = cw - estSys0 - estUser
        if (roomForOut < out) out = roomForOut.coerceAtLeast(MinOut)

        var sys = system
        var estSys = estSys0
        if (cw - estSys - out < MinRoom) {
            val (shortSys, _) = truncateToTokens(system, (cw / 4).coerceAtLeast(MinRoom))
            sys = shortSys
            estSys = estimate(sys)
        }
        val room = (cw - estSys - out).coerceAtLeast(MinRoom)
        return if (estUser <= room) Fit(out, sys, user, false)
        else {
            val (u, did) = truncateToTokens(user, room)
            Fit(out, sys, u, did)
        }
    }

    private const val MinOut = LlmPolicy.MIN_OUTPUT_TOKENS
    private const val MinRoom = LlmPolicy.MIN_INPUT_ROOM_TOKENS
    private const val MinWindow = MinRoom + MinOut * 2
}
