package com.tingsiwei.app.tts

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 云端大模型朗读：默认硅基流动的 FunAudioLLM/CosyVoice2-0.5B（OpenAI /audio/speech 兼容格式）。
 * request_format=pcm + stream=true，响应是 HTTP chunked 的 16bit 小端 PCM：
 * 边下边喂 AudioTrack，首句出声只等首包（约几百毫秒），整篇不再有句间干等；
 * 中文韵律由语音大模型给出，断句与人味远好于离线 kokoro。
 * 语速 speed 范围 0.25~4；每次调用独立请求，调用方逐句调即可实现朗读中调速。
 */
class CloudTts(
    private val transport: CloudTtsTransport = OkHttpCloudTtsTransport,
    private val cfg: CloudTtsConfig,
) {

    companion object {
        const val DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B"

        /** CosyVoice2 原生采样率，服务端免重采样 */
        const val SAMPLE_RATE = 24000

        /** 硅基流动系统预置音色（8 种）；key 是接口里的音色后缀，value 是给用户看的中文标签 */
        val VOICES: List<Pair<String, String>> = listOf(
            "anna" to "女声·沉稳",
            "bella" to "女声·激情",
            "claire" to "女声·温柔",
            "diana" to "女声·欢快",
            "alex" to "男声·沉稳",
            "benjamin" to "男声·低沉",
            "charles" to "男声·磁性",
            "david" to "男声·欢快",
        )

        fun voiceId(name: String) = "$DEFAULT_MODEL:$name"

        /** 存储值（形如 FunAudioLLM/CosyVoice2-0.5B:anna）转显示标签；不认识的原样显示 */
        fun voiceLabel(stored: String): String =
            VOICES.firstOrNull { voiceId(it.first) == stored }?.second ?: stored
    }

    /** message 一律是给用户看的中文，调用方直接展示 */
    class CloudTtsException(message: String) : RuntimeException(message)

    /** 用户手填接口地址的规范化，逻辑对齐 LLM 的 LlmEndpoint。空串表示地址不可用。 */
    fun endpoint(): String {
        var u = cfg.baseUrl.trim().trimEnd('/')
        if (u.isEmpty()) throw CloudTtsException("还没填在线朗读的接口地址")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        if (u.endsWith("/audio/speech", true)) return u
        if (u.endsWith("/v1", true)) return "$u/audio/speech"
        return "$u/v1/audio/speech"
    }

    /**
     * 合成 [text]，把 16bit 小端 PCM 按块回调（单位：采样值）。onPcm 回 false 立即断流停止。
     * 失败抛 [CloudTtsException]；onPcm 回 false 属正常停止，不抛。
     */
    fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean) {
        val key = cfg.apiKey.trim()
        if (key.isEmpty()) throw CloudTtsException("还没填在线朗读的 API Key，到设置页「朗读」里填")
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            put("voice", cfg.voice)
            put("response_format", "pcm")
            put("sample_rate", SAMPLE_RATE)
            put("speed", speed.coerceIn(0.25f, 4.0f).toDouble())
            put("stream", true)
        }
        val resp = try {
            transport.post(endpoint(), key, payload.toString())
        } catch (e: CloudTtsException) {
            throw e
        } catch (e: Exception) {
            throw CloudTtsException("无法连接语音接口，请检查手机网络和设置里的地址")
        }
        if (resp.code == 401 || resp.code == 403) {
            throw CloudTtsException("语音接口拒绝了请求，请检查 API Key 是否正确")
        }
        if (resp.code == 429) {
            throw CloudTtsException("语音接口限流了，稍等一会儿再试")
        }
        if (resp.code >= 400) {
            val serverMsg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(resp.errorText)?.groupValues?.get(1)
            throw CloudTtsException("在线朗读失败（HTTP ${resp.code}）${serverMsg?.let { "：$it" } ?: ""}")
        }
        val ins = resp.bodyStream ?: throw CloudTtsException("语音接口没有返回音频")
        try {
            val buf = ByteArray(8192)
            var carry = ByteArray(0) // 奇数字节收尾时剩下的半个采样，拼到下一块开头
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val data = carry + buf.copyOfRange(0, n)
                val frames = data.size / 2
                if (frames > 0) {
                    val pcm = ShortArray(frames)
                    for (i in 0 until frames) {
                        pcm[i] = (((data[2 * i].toInt() and 0xFF) or ((data[2 * i + 1].toInt() and 0xFF) shl 8)).toShort())
                    }
                    if (!onPcm(pcm)) return
                }
                carry = if (data.size % 2 == 1) byteArrayOf(data[data.size - 1]) else ByteArray(0)
            }
        } catch (e: Exception) {
            throw CloudTtsException("朗读中断：${e.message ?: "网络断开"}")
        } finally {
            try {
                ins.close()
            } catch (_: Exception) {
            }
        }
    }
}

/** 在线朗读配置（调用方从设置读好传入，本层不读设置，与 TtsPlayer 的引擎字符串同约定） */
data class CloudTtsConfig(
    val baseUrl: String,
    val apiKey: String,
    val voice: String,
    val model: String = CloudTts.DEFAULT_MODEL,
)

/** HTTP 往来接缝：生产用 [OkHttpCloudTtsTransport]，单测注入假实现（不加任何测试依赖）。 */
interface CloudTtsTransport {
    class Resp(val code: Int, val bodyStream: InputStream?, val errorText: String)
    fun post(url: String, apiKey: String, jsonBody: String): Resp
}

object OkHttpCloudTtsTransport : CloudTtsTransport {

    // readTimeout 是相邻两个字节包的最大间隔；流式合成持续出字节，30 秒足够宽容
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun post(url: String, apiKey: String, jsonBody: String): CloudTtsTransport.Resp {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        return if (resp.isSuccessful) {
            // 成功路径不能 close：body 流要在整个朗读期间慢慢读
            CloudTtsTransport.Resp(resp.code, resp.body?.byteStream(), "")
        } else {
            val err = try {
                resp.body?.string().orEmpty()
            } finally {
                resp.close()
            }
            CloudTtsTransport.Resp(resp.code, null, err)
        }
    }
}

/** 单测用：把字节块按序吐出，验证跨块解码与提前停止 */
class FakeCloudTtsStream(private val chunks: List<ByteArray>) : InputStream() {
    private val it = chunks.iterator()
    private var cur: ByteArrayInputStream? = null

    override fun read(): Int {
        while (true) {
            val c = cur ?: if (it.hasNext()) ByteArrayInputStream(it.next()).also { cur = it } else return -1
            val b = c.read()
            if (b >= 0) return b
            cur = null
        }
    }
}
