package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 面向用户的接口异常，message 本身就是能看懂的中文提示。 */
class LlmException(message: String) : RuntimeException(message)

/** OpenAI 兼容接口客户端：读设置 → 组装 [LlmSession] → 校验地址。重试/预算都在 [LlmSession] 里。 */
class LlmClient(
    private val settings: SettingsRepository,
    private val transport: Transport = OkHttpTransport,
    private val gate: RateGate = GlobalGate.shared,
    private val sleeper: Sleeper = CoroutineSleeper,
    private val rng: () -> Double = { Random.nextDouble() },
) {

    /** 把用户填的地址规范化为以 /v1 结尾的基础地址；忘写协议时默认 https */
    fun normalizeBase(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "https://$u"
        }
        if (u.endsWith("/chat/completions")) u = u.removeSuffix("/chat/completions")
        if (!u.endsWith("/v1")) u = "$u/v1"
        return u
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(baseUrl)
        try {
            session().listModels(base, apiKey)
        } catch (e: LlmError) {
            throw LlmException(e.message ?: "获取模型列表失败")
        }
    }

    /** 单轮对话（非流式）。system 可为空。429/5xx/网络抖动自动退避重试，输出截断则精简重问一次。 */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.4,
        maxTokens: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val base = requireBaseUrl(baseUrl)
        try {
            session().chatWithDegrade(
                baseUrl = base,
                apiKey = apiKey,
                model = model,
                system = system,
                user = user,
                temperature = temperature,
                desiredOutTokens = maxTokens ?: LlmPolicy.DEFAULT_MAX_OUTPUT_TOKENS,
                degradeRule = Prompts.trimRule(),
            )
        } catch (e: LlmError) {
            throw LlmException(e.message ?: "接口调用失败")
        }
    }

    private suspend fun session(): LlmSession {
        val cfg = settings.current()
        return LlmSession(
            transport = transport,
            gate = gate,
            sleeper = sleeper,
            rng = rng,
            policy = LlmSession.Policy(
                contextWindow = cfg.llmContextWindow.coerceIn(2048, 1_000_000),
                minIntervalMs = cfg.llmMinIntervalMs.coerceIn(0L, 60_000L),
                conservative = cfg.llmConservative,
            ),
        )
    }

    private fun requireBaseUrl(baseUrl: String): String {
        val base = normalizeBase(baseUrl)
        if (base.isBlank() || base == "/v1" || !base.startsWith("http")) {
            throw LlmException("还没有填写接口地址，请到设置里填好接口地址和 KEY 再试")
        }
        return base
    }
}

/** 生产传输实现：把 IO 与非法地址异常都收敛成 [LlmError]，供 [LlmSession] 判断是否重试。 */
object OkHttpTransport : Transport {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
        val request = try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        } catch (e: IllegalArgumentException) {
            throw LlmError.Network()
        }
        return try {
            http.newCall(request).execute().use { resp ->
                val map = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                Transport.RawResp(resp.code, resp.body?.string().orEmpty(), map)
            }
        } catch (e: SocketTimeoutException) {
            throw LlmError.Timeout()
        } catch (e: IOException) {
            throw LlmError.Network()
        }
    }
}
