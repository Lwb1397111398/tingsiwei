package com.tingsiwei.app.tts

import android.util.Log
import com.tingsiwei.app.data.TtsElevenDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream

/**
 * ElevenLabs 官方朗读客户端（在线朗读「官方」档）。官方 API 与 OpenAI 兼容格式不同：
 * 音色 ID 在 URL 路径里（/text-to-speech/{voice_id}）、鉴权用 xi-api-key 头、
 * 请求体是 {"text","model_id"}。输出固定要 pcm_24000（16bit 小端单声道 24k），
 * 与播放层统一采样率一致，无需重采样。
 * 语速走 voice_settings.speed，官方限 0.7~1.2（超范围 400，实测 2026-10），按此夹取。
 * 错误复用 CloudTts.CloudTtsException，TtsPlayer 的回落逻辑原样生效。
 */
class ElevenTts(
    private val transport: CloudTtsTransport = OkHttpCloudTtsTransport,
    private val cfg: CloudTtsConfig,
) : CloudTtsClient {

    /** 官方音色列表里的一项：voice_id 存库，其余给设置页点选 chips 展示 */
    data class ElevenVoice(
        val id: String,
        val name: String,
        val gender: String,
        val accent: String,
        val category: String,
    ) {
        /** 官方 name 常带长描述（Roger - Laid-Back, Casual, Resonant），取破折号前短名做 chips 标签 */
        val shortName: String get() = name.substringBefore(" - ").trim()

        val label: String
            get() = listOfNotNull(
                shortName.ifEmpty { null },
                when (gender) {
                    "male" -> "男声"
                    "female" -> "女声"
                    else -> null
                },
            ).joinToString("·").ifEmpty { id }
    }

    companion object {
        const val SAMPLE_RATE = 24000

        private const val TAG = "ElevenTts"
        private const val AUTH_HEADER = "xi-api-key"

        /** 免费档实测可用的官方模型（2026-10），都支持中文；其余模型名可手填 */
        val MODELS: List<Pair<String, String>> = listOf(
            "eleven_multilingual_v2" to "效果最好",
            "eleven_turbo_v2_5" to "均衡",
            "eleven_flash_v2_5" to "最快",
        )

        /**
         * 解析 GET /voices 的响应体；结构不对返回空表（调用方提示手动填音色 ID）。
         * 只取需要的字段，labels 缺失不致命。
         */
        fun parseVoices(json: String): List<ElevenVoice> = try {
            val arr = Json.parseToJsonElement(json).jsonObject["voices"]?.jsonArray
            arr?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["voice_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val labels = o["labels"]?.jsonObject
                ElevenVoice(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.content ?: id,
                    gender = labels?.get("gender")?.jsonPrimitive?.content.orEmpty(),
                    accent = labels?.get("accent")?.jsonPrimitive?.content.orEmpty(),
                    category = o["category"]?.jsonPrimitive?.content.orEmpty(),
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun apiBase(): String {
        var u = cfg.baseUrl.trim().trimEnd('/')
        if (u.isEmpty()) throw CloudTts.CloudTtsException("还没配置 ElevenLabs 接口地址")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return u
    }

    fun speechEndpoint(): String = "${apiBase()}/text-to-speech/${cfg.voice.trim()}?output_format=pcm_24000"

    fun voicesEndpoint(): String = "${apiBase()}/voices"

    override fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean) {
        val key = cfg.key.trim()
        if (key.isEmpty()) throw CloudTts.CloudTtsException("还没填 ElevenLabs 的 API Key，到设置页「朗读」里填")
        val voice = cfg.voice.trim()
        if (voice.isEmpty()) throw CloudTts.CloudTtsException("还没选朗读音色，到设置页「朗读」里获取音色列表点选")
        val payload = buildJsonObject {
            put("text", text)
            put("model_id", cfg.model.trim().ifEmpty { TtsElevenDefaults.MODEL })
            putJsonObject("voice_settings") {
                // 官方严格校验 0.7~1.2 且不容忍 Float 长尾（1.2f.toDouble()=1.2000000476…会被 400 拒），
                // 先夹取再量化到百分位，序列化出的就是干净的 1.2
                put("speed", Math.round(speed.coerceIn(0.7f, 1.2f) * 100f) / 100.0)
            }
        }
        val resp = try {
            transport.post(speechEndpoint(), key, payload.toString(), AUTH_HEADER)
        } catch (e: CloudTts.CloudTtsException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "语音请求失败：${e.javaClass.simpleName} ${e.message}")
            throw CloudTts.CloudTtsException("无法连接 ElevenLabs，请检查手机网络")
        }
        if (resp.code >= 400) throw httpError(resp.code, resp.errorText)
        val ins = assertAudioBody(resp)
        Log.i(TAG, "语音请求发出：${speechEndpoint()} model=${cfg.model} voice=$voice 格式=pcm_24000")
        try {
            val totalBytes = CloudTtsAudio.drainPcmStream(ins, onPcm)
            if (totalBytes < 0) return // onPcm 回 false：用户停止，正常返回不报错
            if (totalBytes == 0) throw CloudTts.CloudTtsException("ElevenLabs 没有返回音频")
            Log.i(TAG, "流式 PCM 完成：$totalBytes 字节 model=${cfg.model}")
        } catch (e: CloudTts.CloudTtsException) {
            throw e
        } catch (e: Exception) {
            throw CloudTts.CloudTtsException("朗读中断：${e.message ?: "网络断开"}")
        } finally {
            try {
                ins.close()
            } catch (_: Exception) {
            }
        }
    }

    /** GET /voices 拉官方音色列表（设置页「获取音色」用）；网络调用，调用方负责切 IO 线程 */
    fun fetchVoices(): List<ElevenVoice> {
        val key = cfg.key.trim()
        if (key.isEmpty()) throw CloudTts.CloudTtsException("还没填 ElevenLabs 的 API Key，先填 Key 再获取音色")
        val resp = try {
            transport.get(voicesEndpoint(), key, AUTH_HEADER)
        } catch (e: Exception) {
            throw CloudTts.CloudTtsException("无法连接 ElevenLabs，请检查手机网络")
        }
        if (resp.code == 401 || resp.code == 403) {
            throw CloudTts.CloudTtsException("获取音色被拒（HTTP ${resp.code}），请检查 API Key 是否正确")
        }
        if (resp.code >= 400) {
            throw CloudTts.CloudTtsException("获取音色失败（HTTP ${resp.code}）${serverMessage(resp.errorText)?.let { "：$it" } ?: ""}")
        }
        val body = resp.bodyStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        runCatching { resp.bodyStream?.close() }
        val voices = parseVoices(body)
        Log.i(TAG, "音色列表拉到 ${voices.size} 个（HTTP ${resp.code}）")
        return voices
    }

    /** 服务端错误体里的 message 字段（ElevenLabs 是 {"detail":{...,"message":...}}，嵌套与否都按文本抓） */
    private fun serverMessage(body: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun httpError(code: Int, body: String): CloudTts.CloudTtsException {
        val msg = when {
            code == 401 || code == 403 -> "ElevenLabs 拒绝了请求，请检查 API Key 是否正确（免费额度用尽也会这样）"
            code == 402 -> "ElevenLabs 额度已用尽，等下月免费额度恢复或升级付费"
            code == 429 -> "ElevenLabs 限流了，稍等一会儿再试"
            else -> "ElevenLabs 返回错误（HTTP $code）${serverMessage(body)?.let { "：$it" } ?: ""}"
        }
        return CloudTts.CloudTtsException(msg)
    }

    /** 200 但 Content-Type 是 JSON/HTML/文本（网关错误页、错误包 200）：读出报错，绝不把文本当音频喂喇叭 */
    private fun assertAudioBody(resp: CloudTtsTransport.Resp): InputStream {
        val raw = resp.bodyStream ?: throw CloudTts.CloudTtsException("ElevenLabs 没有返回音频")
        val ct = resp.contentType.lowercase()
        if ("json" in ct || "html" in ct || "text/" in ct) {
            val text = raw.readBytes().toString(Charsets.UTF_8)
            runCatching { raw.close() }
            throw CloudTts.CloudTtsException("ElevenLabs 没返回音频${serverMessage(text)?.let { "：$it" } ?: "，返回了文本"}")
        }
        return raw
    }
}
