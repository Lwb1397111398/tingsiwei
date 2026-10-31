package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    // 连接超时继承 util.Http.base（20s），只覆盖读写；连接池与线程池与模型下载/在线朗读共用
    private val http: OkHttpClient = Http.base.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp =
        execute(buildRequest(url, headers) {
            it.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        })

    override suspend fun get(url: String, headers: Map<String, String>): Transport.RawResp =
        execute(buildRequest(url, headers) { it.get() })

    private inline fun buildRequest(
        url: String,
        headers: Map<String, String>,
        block: (Request.Builder) -> Unit,
    ): Request = try {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        block(builder)
        builder.build()
    } catch (e: IllegalArgumentException) {
        // 地址或头字段不成形：重试没有意义
        throw LlmError.BadUrl()
    }

    /**
     * enqueue + 可取消挂起：等待响应期间协程被取消（用户点停止/删笔记/退出重试）时立刻 cancel 在途
     * HTTP 请求，不再把最长 300 秒的读超时（×重试退避）耗在后台继续烧额度。旧实现同步 execute()
     * 对协程取消无感，点取消后请求会在后台跑完整轮退避周期。
     */
    private suspend fun execute(request: Request): Transport.RawResp =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, resp: Response) {
                    val mapped = runCatching {
                        resp.use { r ->
                            val map = r.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                            Transport.RawResp(r.code, r.body?.string().orEmpty(), map)
                        }
                    }.getOrElse { e ->
                        cont.resumeWith(Result.failure(e))
                        return
                    }
                    cont.resumeWith(Result.success(mapped))
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return // 协程取消触发的 call.cancel()：正常停止路径，不当网络错误
                    cont.resumeWith(Result.failure(when (e) {
                        is SocketTimeoutException -> LlmError.Timeout()
                        else -> LlmError.Network()
                    }))
                }
            })
        }
}
