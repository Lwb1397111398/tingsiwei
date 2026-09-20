package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmException(message: String) : RuntimeException(message)

/** OpenAI 兼容接口客户端：/v1/models 拉模型列表，/v1/chat/completions 对话 */
class LlmClient(private val settings: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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

    /** 拉取模型列表（宽松解析：data[].id 或 models[].id） */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val base = normalizeBase(baseUrl)
        val req = requestBuilder("$base/models", apiKey).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("获取模型列表失败 (HTTP ${resp.code})：${body.take(300)}")
            parseModelIds(body)
        }
    }

    private fun parseModelIds(body: String): List<String> {
        return try {
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

    /** 单轮对话（非流式）。system 可为空。 */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.4,
        maxTokens: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "请先在设置里选择模型" }
        val base = normalizeBase(baseUrl)
        val messages = buildJsonArray {
            if (system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", user)
            })
        }
        val payload = buildJsonObject {
            put("model", model.trim())
            put("temperature", temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("stream", false)
            put("messages", messages)
        }
        val req = requestBuilder("$base/chat/completions", apiKey)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("接口返回错误 (HTTP ${resp.code})：${body.take(400)}")
            val obj = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw LlmException("接口返回的不是有效 JSON：${body.take(200)}")
            }
            val choices = obj["choices"] as? JsonArray
            val content = choices?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("message")?.jsonObject?.get("content") }
                ?.let { c ->
                    // 标准格式是字符串；个别兼容接口把 content 返回成分段数组
                    when (c) {
                        is JsonPrimitive -> c.content
                        is JsonArray -> c.mapNotNull { part ->
                            (part as? JsonObject)?.get("text")
                                ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
                        }.joinToString("")
                        else -> null
                    }
                }
            content?.takeIf { it.isNotBlank() } ?: throw LlmException("接口没有返回内容：${body.take(300)}")
        }
    }

    private fun requestBuilder(url: String, apiKey: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer ${apiKey.trim()}")
        return b
    }
}
