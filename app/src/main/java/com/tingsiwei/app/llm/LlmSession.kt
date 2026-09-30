package com.tingsiwei.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * 纯 Kotlin 的接口会话：预算 → 节流 → 发送 → 分类 → 退避重试 → 解析。
 * 不依赖任何 Android 类型，单测直接注入假 [Transport]/[Sleeper]。
 */
class LlmSession(
    private val transport: Transport,
    private val gate: RateGate = GlobalGate.shared,
    private val sleeper: Sleeper = CoroutineSleeper,
    private val rng: () -> Double = { Random.nextDouble() },
    val policy: Policy = Policy(),
) {

    data class Policy(
        val contextWindow: Int = LlmPolicy.DEFAULT_CONTEXT_WINDOW,
        val minIntervalMs: Long = LlmPolicy.DEFAULT_MIN_INTERVAL_MS,
        val maxRetries: Int = LlmPolicy.MAX_RETRIES,
        val conservative: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val window: Int get() = LlmPolicy.windowFor(policy.contextWindow, policy.conservative)
    private val intervalMs: Long get() = LlmPolicy.intervalFor(policy.minIntervalMs, policy.conservative)

    /**
     * 单轮对话，流式（stream=true）。为什么必须流式：很多中转渠道套着 Cloudflare，
     * 非流式请求在模型憋长输出（如整图改写）时超过 ~100 秒无字节往返，会被网关直接掐成 524/503，
     * 表现为"服务端暂时不可用"；流式边生成边回字节，网关不会掐。个别渠道不认 stream 参数
     * 仍回整段 JSON，[parseContent] 两种都能解。
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        desiredOutTokens: Int = LlmPolicy.DEFAULT_MAX_OUTPUT_TOKENS,
    ): String {
        if (model.isBlank()) throw LlmException("请先在设置里选择模型")
        val fit = Tokens.fit(window, system, user, desiredOutTokens)
        val payload = buildJsonObject {
            put("model", model.trim())
            put("max_tokens", fit.maxOut)
            put("stream", true)
            put("messages", buildJsonArray {
                if (fit.system.isNotBlank()) add(buildJsonObject {
                    put("role", "system")
                    put("content", fit.system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", fit.user)
                })
            })
        }
        val resp = call("$baseUrl/chat/completions", authHeaders(apiKey), payload.toString())
        // 解析层任何意外都归成"坏格式"，不把英文异常甩到界面上
        return try {
            parseContent(resp.body)
        } catch (e: LlmError) {
            throw e
        } catch (e: Exception) {
            throw LlmError.BadFormat()
        }
    }

    /**
     * 输出被截断时的自动降级：抬高输出预算（从输入侧让位）并附体量规则再问一次；
     * 两次仍截断就把两次中较长的部分内容随异常带回，由调用方抢救，不再整段作废。
     * 个别渠道不认过大的 max_tokens（回 4xx），退回原预算再试最后一次。
     */
    suspend fun chatWithDegrade(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        desiredOutTokens: Int = LlmPolicy.DEFAULT_MAX_OUTPUT_TOKENS,
        degradeRule: String = "",
    ): String {
        var partial = ""
        try {
            return chat(baseUrl, apiKey, model, system, user, desiredOutTokens)
        } catch (e: LlmError.Reject) {
            // 新默认 16384 可能撞上个别渠道的 max_tokens 硬帽：400 类（参数红线）退回保守档带体量规则再问一次；
            // 401/403/404 与输出参数无关，维持快速失败，重试只是浪费
            if (e.httpCode == 400 && desiredOutTokens > LlmPolicy.REJECT_FALLBACK_OUTPUT_TOKENS) {
                return chat(
                    baseUrl, apiKey, model, "$system\n$degradeRule", user,
                    LlmPolicy.REJECT_FALLBACK_OUTPUT_TOKENS,
                )
            }
            throw e
        } catch (e: LlmError.Truncated) {
            partial = e.partialContent
        }
        try {
            return chat(baseUrl, apiKey, model, "$system\n$degradeRule", user, (desiredOutTokens * 2).coerceAtLeast(4096))
        } catch (e: LlmError.Truncated) {
            throw if (e.partialContent.length >= partial.length) e else LlmError.Truncated(partial)
        } catch (e: LlmError.Reject) {
            // 抬高输出触发了渠道的参数红线：退回原预算 + 体量规则，仍是最有效的降级
            return try {
                chat(baseUrl, apiKey, model, "$system\n$degradeRule", user, desiredOutTokens)
            } catch (t: LlmError.Truncated) {
                throw if (t.partialContent.length >= partial.length) t else LlmError.Truncated(partial)
            }
        }
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val resp = try {
            call("$baseUrl/models", authHeaders(apiKey), null)
        } catch (e: LlmError.Reject) {
            // 模型列表是只读端点：404 大概率是渠道不提供列表功能，而不是地址错了
            throw LlmException("拉取模型列表失败（HTTP ${e.httpCode}）。这个渠道可能不提供模型列表，直接手动输入模型名即可")
        }
        return parseModelIds(resp.body)
    }

    // ---------- 内部 ----------

    private suspend fun call(url: String, headers: Map<String, String>, body: String?): Transport.RawResp {
        var attempt = 0
        while (true) {
            val outcome: Any = try {
                val resp = gate.acquire(intervalMs) {
                    if (body == null) transport.get(url, headers) else transport.post(url, headers, body)
                }
                classify(resp) ?: resp
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError) {
                e
            } catch (e: Exception) {
                // 传输层漏出来的意外异常按可重试的网络错误处理，不外泄英文堆栈
                LlmError.Network()
            }
            if (outcome is Transport.RawResp) return outcome
            val err = outcome as LlmError
            if (!err.retryable || attempt >= policy.maxRetries) throw err
            // Retry-After 与指数退避取大，避免渠道回 0/1 秒时变成疯狂重打
            sleeper.sleep(maxOf(err.retryAfterMs ?: 0L, LlmPolicy.backoffMs(attempt, rng)))
            attempt++
        }
    }

    private fun classify(resp: Transport.RawResp): LlmError? = when {
        resp.code in 200..299 -> null
        resp.code == 429 -> LlmError.RateLimited(429, LlmPolicy.retryAfterMs(resp.headers["retry-after"], resp.body))
        resp.code in 500..599 || resp.code == 408 || resp.code == 425 -> LlmError.Server(resp.code)
        else -> LlmError.Reject(resp.code)
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) mapOf("Content-Type" to "application/json")
        else mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${apiKey.trim()}")

    private fun parseContent(body: String): String {
        // SSE 常以 ": keep-alive" 注释行或 event: 行开头，不能只看首行；
        // 只要任一行以 data: 开头就按流解析（JSON 键名带引号、前面还有缩进，不会误判）。
        val looksStream = body.lineSequence().any { it.trimStart().startsWith("data:") }
        return if (looksStream) parseStreamBody(body) else parseJsonBody(body)
    }

    /**
     * 流式响应（SSE）解析：逐行取 data: 载荷，把 delta.content 拼回完整回答。
     * 思维链（delta.reasoning_content）一律跳过；channel 忽略 stream 参数回整段 JSON 的情况走 [parseJsonBody]。
     */
    private fun parseStreamBody(body: String): String {
        val content = StringBuilder()
        var finish: String? = null
        var sawDone = false
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue // event:/注释/空行一律跳过
            val payload = line.substring("data:".length).trim()
            if (payload == "[DONE]") {
                sawDone = true
                break
            }
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
            val delta = choice["delta"] as? JsonObject
            val piece = (delta?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!piece.isNullOrEmpty()) content.append(piece)
            (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { finish = it }
        }
        if (content.isBlank()) {
            if (finish == "length") throw LlmError.Truncated()
            throw LlmError.EmptyContent()
        }
        // 有部分内容也算截断：异常带着部分内容走，调用方决定是重试还是抢救
        if (finish == "length") throw LlmError.Truncated(content.toString())
        // 既没有 finish_reason 也没有 [DONE]：流被中途掐断，输出不完整，按可重试的网络错误走退避
        if (finish == null && !sawDone) throw LlmError.Network()
        return content.toString()
    }

    private fun parseJsonBody(body: String): String {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw LlmError.BadFormat()
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw LlmError.EmptyContent()
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val raw = (choice["message"] as? JsonObject)?.get("content")
        val content = when {
            raw is JsonPrimitive && raw.isString -> raw.content
            // 个别兼容接口把 content 返回成分段数组
            raw is JsonArray -> raw.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            }.joinToString("")
            else -> ""
        }
        if (content.isBlank()) {
            if (finish == "length") throw LlmError.Truncated()
            throw LlmError.EmptyContent()
        }
        // 有部分内容也算截断：异常带着部分内容走，调用方决定是重试还是抢救
        if (finish == "length") throw LlmError.Truncated(content)
        return content
    }

    private fun parseModelIds(body: String): List<String> = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val arr: JsonArray = when {
            obj["data"] is JsonArray -> obj["data"]!!.jsonArray
            obj["models"] is JsonArray -> obj["models"]!!.jsonArray
            else -> return emptyList()
        }
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            (o["id"] ?: o["name"])?.jsonPrimitive?.takeIf { it.isString }?.content
        }.filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) {
        emptyList()
    }
}
