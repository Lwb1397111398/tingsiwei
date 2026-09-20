package com.tingsiwei.app.llm

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
        val contextWindow: Int = 16384,
        val minIntervalMs: Long = 1200L,
        val maxRetries: Int = LlmPolicy.MAX_RETRIES,
        val conservative: Boolean = false,
    )

    class Recorded(val url: String, val body: String)

    /** 每次真实发出都记一条，测试据此断言调用次数与每次请求的输入规模。 */
    val requests = mutableListOf<Recorded>()

    private val json = Json { ignoreUnknownKeys = true }

    private val intervalMs: Long
        get() = if (policy.conservative) policy.minIntervalMs * 2 else policy.minIntervalMs

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.4,
        desiredOutTokens: Int = 2048,
    ): String {
        require(model.isNotBlank()) { "请先在设置里选择模型" }
        val fit = Tokens.fit(
            contextWindow = if (policy.conservative) policy.contextWindow / 2 else policy.contextWindow,
            system = system,
            user = user,
            desiredOut = desiredOutTokens,
        )
        val payload = buildJsonObject {
            put("model", model.trim())
            put("temperature", temperature)
            put("max_tokens", fit.maxOut)
            put("stream", false)
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
        return parseContent(resp.body)
    }

    /** 输出被截断时，带一条"精简"规则再问一次；仍截断才放弃。 */
    suspend fun chatWithDegrade(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.4,
        desiredOutTokens: Int = 2048,
        degradeRule: String = "",
    ): String = try {
        chat(baseUrl, apiKey, model, system, user, temperature, desiredOutTokens)
    } catch (e: LlmCallError.Truncated) {
        chat(baseUrl, apiKey, model, "$system\n$degradeRule", user, temperature, desiredOutTokens)
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val resp = call("$baseUrl/models", authHeaders(apiKey), "{}")
        return parseModelIds(resp.body)
    }

    // ---------- 内部 ----------

    private suspend fun call(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
        var attempt = 0
        while (true) {
            val outcome: Any = try {
                val resp = gate.acquire(intervalMs) { requests.add(Recorded(url, body)); transport.post(url, headers, body) }
                classify(resp) ?: resp
            } catch (e: LlmCallError) {
                e
            }
            if (outcome is Transport.RawResp) return outcome
            val err = outcome as LlmCallError
            if (!err.retryable || attempt >= policy.maxRetries) throw err
            sleeper.sleep(err.retryAfterMs ?: LlmPolicy.backoffMs(attempt, rng))
            attempt++
        }
    }

    private fun classify(resp: Transport.RawResp): LlmCallError? = when {
        resp.code in 200..299 -> null
        resp.code == 429 -> LlmCallError.RateLimited(
            429,
            LlmPolicy.retryAfterMs(resp.headers["retry-after"] ?: resp.headers["Retry-After"], resp.body),
            resp.body.take(200),
        )
        resp.code in 500..599 -> LlmCallError.Server(resp.code, resp.body.take(200))
        else -> LlmCallError.Client(resp.code, resp.body.take(200))
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) mapOf("Content-Type" to "application/json")
        else mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${apiKey.trim()}")

    private fun parseContent(body: String): String {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw LlmCallError.BadFormat(body.take(200))
        }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw LlmCallError.EmptyContent(body.take(200))
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val raw = choice["message"]?.jsonObject?.get("content")
        val content = when (raw) {
            is JsonPrimitive -> raw.content
            // 个别兼容接口把 content 返回成分段数组
            is JsonArray -> raw.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            }.joinToString("")
            null -> ""
            else -> ""
        }
        if (finish == "length" && content.isBlank()) throw LlmCallError.Truncated(body.take(200))
        content.takeIf { it.isNotBlank() } ?: throw LlmCallError.EmptyContent(body.take(200))
        if (finish == "length") throw LlmCallError.Truncated(content.take(200))
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
