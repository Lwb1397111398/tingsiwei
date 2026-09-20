package com.tingsiwei.app.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/** HTTP 往来接缝：生产用 [OkHttpTransport]，单测注入假实现（不加任何测试依赖）。 */
interface Transport {
    class RawResp(val code: Int, val body: String, val headers: Map<String, String>)
    /** 只允许抛 [LlmError]，其余异常由 [LlmSession] 兜底归类为不可重试 */
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

/**
 * 分级错误：只有 [retryable] 里的类别进退避。
 * message 本身就是给用户看的话，因此不再单独维护 userText；原始响应体一概不进错误信息（里面有回显的用户内容）。
 */
sealed class LlmError(message: String, val httpCode: Int = 0, val retryAfterMs: Long? = null) :
    RuntimeException(message) {

    class RateLimited(code: Int, retryAfterMs: Long?) :
        LlmError("接口限流（HTTP $code），已自动重试多次仍未成功", code, retryAfterMs)

    class Server(code: Int) : LlmError("服务端暂时不可用（HTTP $code），已自动重试仍未成功，请稍后再试或换个模型", code)

    /** 4xx 非限流：重试没有意义 */
    class Reject(code: Int) : LlmError(
        when (code) {
            401, 403 -> "接口拒绝了请求，请检查 API KEY 是否正确、账户是否有余额"
            404 -> "接口地址不对（找不到对话接口），请检查设置里的接口地址"
            else -> "接口返回错误 (HTTP $code)，请检查接口地址与模型名"
        },
        code,
    )

    /** 地址本身不成形，重试无意义 */
    class BadUrl : LlmError("接口地址格式不对，请检查设置里的接口地址（要以 http:// 或 https:// 开头）")

    class Network : LlmError("无法连接接口，请检查手机网络和设置里的接口地址")
    class Timeout : LlmError("请求超时，可稍后重试，或在设置里换个更快的模型")
    class BadFormat : LlmError("接口返回的不是有效 JSON，这个渠道可能不兼容，换个模型试试")
    class EmptyContent : LlmError("接口没有返回内容，请在设置里换一个模型再试")
    class Truncated : LlmError("接口输出被长度上限截断，请在设置里开启「保守模式」或调小「上下文窗口」后重试")

    val retryable: Boolean
        get() = this is RateLimited || this is Server || this is Network || this is Timeout
}

/**
 * 相邻请求的最小间隔闸门（进程级共用一个实例），防止分段多时自己把渠道 QPS 打满。
 * 取号即释放锁，等待发生在锁外——退避中的请求不会把闸门锁死。
 */
class RateGate(
    private val clock: ClockSource = SystemClock,
    private val sleeper: Sleeper = CoroutineSleeper,
) {
    private val mutex = Mutex()
    private var nextSlotMs = 0L

    suspend fun <T> acquire(minIntervalMs: Long, block: suspend (slotMs: Long) -> T): T {
        val slot = mutex.withLock {
            val now = clock.nowMs()
            val s = if (now > nextSlotMs) now else nextSlotMs
            nextSlotMs = s + minIntervalMs.coerceAtLeast(0L)
            s
        }
        val wait = slot - clock.nowMs()
        if (wait > 0) sleeper.sleep(wait)
        return block(slot)
    }
}

/** 进程级闸门：chat / listModels / 测试连接 全部共用，避免多处 new 客户端让限流形同虚设。 */
object GlobalGate {
    val shared = RateGate()
}

/** 用户手填接口地址的规范化。纯函数，单独放这里才能被 JVM 单测覆盖。 */
object LlmEndpoint {

    /** 规范化为以 /v1 结尾的基础地址；忘写协议时默认 https。返回空串表示地址不可用。 */
    fun normalize(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        if (u.endsWith("/chat/completions", true)) u = u.removeSuffix("/chat/completions")
        if (!u.endsWith("/v1", true)) u = "$u/v1"
        return u
    }
}

object LlmPolicy {
    const val MAX_RETRIES = 5
    const val BASE_DELAY_MS = 1000L
    const val MAX_DELAY_MS = 60_000L
    const val MIN_OUTPUT_TOKENS = 512
    const val MIN_INPUT_ROOM_TOKENS = 256
    const val DEFAULT_CONTEXT_WINDOW = 16384
    const val DEFAULT_MIN_INTERVAL_MS = 1200L
    const val DEFAULT_MAX_OUTPUT_TOKENS = 2048
    private const val JITTER = 0.2

    /** Retry-After 只认数字秒（个人自用，不解析 HTTP-date）；≤0 视为未提供，交给指数退避。 */
    fun retryAfterMs(headerValue: String?, body: String): Long? {
        val seconds = headerValue?.trim()?.toLongOrNull()
            ?: Regex("\"(?:retry_after|retryAfter|reset|wait)\"\\s*:\\s*(\\d{1,5})").find(body)
                ?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        if (seconds <= 0) return null
        return (seconds * 1000L).coerceAtMost(MAX_DELAY_MS)
    }

    /** 指数退避 + ±20% 抖动；rng 固定 0.5 时结果精确可断言。 */
    fun backoffMs(attempt: Int, rng: () -> Double): Long {
        val base = minOf(BASE_DELAY_MS shl attempt.coerceIn(0, 20), MAX_DELAY_MS)
        val factor = 1.0 - JITTER + 2 * JITTER * rng()
        return (base * factor).toLong().coerceAtLeast(1L)
    }

    /** 渠道限流时按地址/参数把窗口与间隔收一收 */
    fun windowFor(contextWindow: Int, conservative: Boolean): Int =
        if (conservative) contextWindow / 2 else contextWindow

    fun intervalFor(minIntervalMs: Long, conservative: Boolean): Long =
        if (conservative) minIntervalMs * 2 else minIntervalMs
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

    private const val TrimSuffix = "…（内容过长，已截断）"
    private val SuffixTokens = estimate(TrimSuffix)

    /** 在句末边界截断到 capTokens 以内，返回（结果, 是否截断过）。cap 至少要装下截断标记。 */
    fun truncateToTokens(s: String, capTokens: Int): Pair<String, Boolean> {
        if (capTokens <= 0) return "" to s.isNotEmpty()
        val cap = (capTokens - SuffixTokens).coerceAtLeast(1)
        if (estimate(s) <= capTokens) return s to false
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
            if (ch in StrongEnds) lastBoundary = i + 1
            i++
        }
        val cut = if (lastBoundary > 0 && lastBoundary <= cutIndex) lastBoundary else cutIndex
        return s.substring(0, cut.coerceAtLeast(0)) + TrimSuffix to true
    }

    private val StrongEnds = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3000..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
            c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF
    }

    class Fit(val maxOut: Int, val system: String, val user: String, val trimmed: Boolean)

    /**
     * 预算降级：宁可截输入也绝不因为"放不下"而打断一个本来能发的请求。
     * 顺序 = 压输出 → 缩 system → 重算输出余量 → 截输入。
     */
    fun fit(contextWindow: Int, system: String, user: String, desiredOut: Int): Fit {
        val cw = contextWindow.coerceAtLeast(MinWindow)
        val estUser = estimate(user)
        var sys = system
        var estSys = estimate(sys)
        var out = desiredOut.coerceAtLeast(MinOut)

        // 1. 输入装得下但输出余量不足时先压输出
        val roomForOut = cw - estSys - estUser
        if (roomForOut < out) out = roomForOut.coerceAtLeast(MinOut)
        // 2. 连最小输入余量都不够，说明 system 本身过长 → 缩 system
        if (cw - estSys - out < MinRoom) {
            sys = truncateToTokens(sys, (cw / 4).coerceAtLeast(MinRoom)).first
            estSys = estimate(sys)
            // 3. 缩完 system 把腾出来的余量还给输出
            out = minOf(desiredOut.coerceAtLeast(MinOut), (cw - estSys - estUser).coerceAtLeast(MinOut))
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
