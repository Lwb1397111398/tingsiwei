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

class LlmException(message: String) : RuntimeException(message)

/** OpenAI 兼容接口客户端：读设置 → 组装 [LlmSession] → 把分级错误转成用户看得懂的文案。 */
class LlmClient(
    private val settings: SettingsRepository,
    private val transport: Transport = OkHttpTransport,
    private val gate: RateGate = GlobalGate.shared,
    private val sleeper: Sleeper = CoroutineSleeper,
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
        try {
            session().listModels(normalizeBase(baseUrl), apiKey)
        } catch (e: LlmCallError) {
            throw LlmException(e.userText())
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
        try {
            session().chatWithDegrade(
                baseUrl = normalizeBase(baseUrl),
                apiKey = apiKey,
                model = model,
                system = system,
                user = user,
                temperature = temperature,
                desiredOutTokens = maxTokens ?: 2048,
                degradeRule = Prompts.trimRule(),
            )
        } catch (e: LlmCallError) {
            throw LlmException(e.userText())
        }
    }

    private suspend fun session(): LlmSession {
        val cfg = settings.current()
        return LlmSession(
            transport = transport,
            gate = gate,
            sleeper = sleeper,
            policy = LlmSession.Policy(
                contextWindow = cfg.llmContextWindow,
                minIntervalMs = cfg.llmMinIntervalMs,
                conservative = cfg.llmConservative,
            ),
        )
    }
}

/** 分级错误 → 自然语言 + 下一步动作；不把 HTTP body 甩给用户。 */
fun LlmCallError.userText(): String = when (this) {
    is LlmCallError.RateLimited ->
        if (retryAfterMs != null) "接口限流中，正在自动等待 ${retryAfterMs / 1000} 秒后继续，无需手动重试"
        else "接口限流中，正在自动退避重试（约 1 分钟内），无需手动重试"
    is LlmCallError.Server -> "服务端暂时不可用，已自动重试仍未成功，请稍后再试或在设置里换个模型"
    is LlmCallError.Client -> when (httpCode) {
        401, 403 -> "接口拒绝了请求，请检查 API KEY 是否正确、账户是否有余额"
        404 -> "接口地址不对（找不到对话接口），请检查设置里的接口地址"
        else -> "接口返回错误 (HTTP $httpCode)，请检查接口地址与模型名"
    }
    is LlmCallError.Network -> "无法连接接口，请检查手机网络和设置里的接口地址"
    is LlmCallError.Timeout -> "请求超时，请在设置里换更快的模型，或缩短「上下文长度」"
    is LlmCallError.BadFormat -> "接口返回的不是有效 JSON，这个渠道可能不兼容，换个模型试试"
    is LlmCallError.EmptyContent -> "接口没有返回内容，请在设置里换一个模型再试"
    is LlmCallError.Truncated -> "内容太长被接口截断，请在设置里开启「保守模式」后重试"
}

/** 生产传输实现：把 IO 异常收敛成分级错误，供 [LlmSession] 判断是否重试。 */
object OkHttpTransport : Transport {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val req = builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val map = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                Transport.RawResp(resp.code, resp.body?.string().orEmpty(), map)
            }
        } catch (e: SocketTimeoutException) {
            throw LlmCallError.Timeout(e.message.orEmpty())
        } catch (e: IOException) {
            throw LlmCallError.Network(e.message.orEmpty())
        }
    }
}
