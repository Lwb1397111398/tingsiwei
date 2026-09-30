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

/** 非接口层的用户可见错误（内容空、格式不对等）。[LlmError] 的 message 本身就是中文提示。 */
class LlmException(message: String) : RuntimeException(message)

/** OpenAI 兼容接口客户端：读设置 → 规范化地址 → 组装 [LlmSession]。重试/预算/限流都在 [LlmSession] 里。 */
class LlmClient(
    private val settings: SettingsRepository,
    private val transport: Transport = OkHttpTransport,
    private val gate: RateGate = GlobalGate.shared,
    private val sleeper: Sleeper = CoroutineSleeper,
    private val rng: () -> Double = { Random.nextDouble() },
) {

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        session().listModels(endpoint(baseUrl), apiKey)
    }

    /**
     * 单轮对话（非流式）。system 可为空。429/5xx/网络抖动自动退避重试，输出截断则精简重问一次。
     * 出错时抛 [LlmError]（message 已是给用户看的中文），调用方直接展示即可。
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int? = null,
        degradeRule: String = Prompts.trimRule(),
    ): String = withContext(Dispatchers.IO) {
        session().chatWithDegrade(
            baseUrl = endpoint(baseUrl),
            apiKey = apiKey,
            model = model,
            system = system,
            user = user,
            desiredOutTokens = maxTokens ?: LlmPolicy.DEFAULT_MAX_OUTPUT_TOKENS,
            degradeRule = degradeRule,
        )
    }

    private fun endpoint(baseUrl: String): String =
        LlmEndpoint.normalize(baseUrl).ifBlank { throw LlmError.BadUrl() }

    private suspend fun session(): LlmSession {
        val cfg = settings.current()
        return LlmSession(
            transport = transport,
            gate = gate,
            sleeper = sleeper,
            rng = rng,
            policy = LlmSession.Policy(
                contextWindow = cfg.llmContextWindow.coerceIn(
                    SettingsRepository.MinContextWindow,
                    SettingsRepository.MaxContextWindow,
                ),
                minIntervalMs = cfg.llmMinIntervalMs.coerceIn(0L, SettingsRepository.MaxMinIntervalMs),
                conservative = cfg.llmConservative,
            ),
        )
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
            // 地址或头字段不成形：重试没有意义
            throw LlmError.BadUrl()
        }
        return execute(request)
    }

    override fun get(url: String, headers: Map<String, String>): Transport.RawResp {
        val request = try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.get().build()
        } catch (e: IllegalArgumentException) {
            throw LlmError.BadUrl()
        }
        return execute(request)
    }

    private fun execute(request: Request): Transport.RawResp =
        try {
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
