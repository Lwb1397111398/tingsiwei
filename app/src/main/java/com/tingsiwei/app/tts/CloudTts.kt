package com.tingsiwei.app.tts

import android.util.Log
import com.tingsiwei.app.data.TtsCloudProvider
import com.tingsiwei.app.util.Http
import com.tingsiwei.app.data.TtsElevenDefaults
import com.tingsiwei.app.data.TtsEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 云端大模型朗读：OpenAI /audio/speech 兼容格式，不绑定具体渠道（硅基流动 / 中转站 / OpenAI 官方都可）。
 * 请求策略两级：先发流式 PCM（硅基流动式，边下边播，首句出声只等首包）；
 * 渠道拒收这种参数时（400/404 或返回了文本而不是音频）自动改要 WAV 整段——
 * WAV 支持面最广，解析后重采样回统一采样率，播放层无感知。
 * 语速 speed 范围 0.25~4；每次调用独立请求，调用方逐句调即可实现朗读中调速。
 */
class CloudTts(
    private val transport: CloudTtsTransport = OkHttpCloudTtsTransport,
    private val cfg: CloudTtsConfig,
) : CloudTtsClient {

    companion object {
        const val DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B"

        /** 统一输出采样率：服务端给了别的采样率就重采样到这个值，播放层只认它 */
        const val SAMPLE_RATE = 24000

        private const val TAG = "CloudTts"

        /** 硅基流动 CosyVoice2 系统预置音色（8 种）；key 是接口里的音色后缀，value 是给用户看的中文标签 */
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

        /** OpenAI 官方 TTS 音色：tts-1 / tts-1-hd 支持前 6 个，gpt-4o-mini-tts 全部 11 个 */
        private val OPENAI_TTS_VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
        private val OPENAI_MINI_TTS_VOICES = OPENAI_TTS_VOICES + listOf("ash", "ballad", "coral", "sage", "verse")

        fun voiceId(name: String) = "$DEFAULT_MODEL:$name"

        /** 存储值（形如 FunAudioLLM/CosyVoice2-0.5B:anna）转显示标签；不认识的原样显示 */
        fun voiceLabel(stored: String): String =
            VOICES.firstOrNull { voiceId(it.first) == stored }?.second ?: stored

        /** 按模型给预置音色；空表 = 该模型没有已知预置，音色由用户自由手填 */
        fun voicePresetsFor(model: String): List<Pair<String, String>> {
            val m = model.trim().lowercase()
            return when {
                "cosyvoice" in m -> VOICES
                m.startsWith("tts-1") -> OPENAI_TTS_VOICES.map { it to it }
                "gpt-4o-mini-tts" in m -> OPENAI_MINI_TTS_VOICES.map { it to it }
                else -> emptyList()
            }
        }

        /**
         * 音色请求期拼装，兼容三种存储值：
         * 短名（anna）+ 硅基式模型（含 /）→ 拼「模型:anna」；
         * 挂着别的模型前缀（Old/Model:anna）→ 换成当前模型；
         * OpenAI 式原名（alloy）或非模型路径的自定义前缀 → 原样发。
         */
        fun buildVoice(model: String, voice: String): String {
            val m = model.trim()
            val v = voice.trim()
            if (v.isEmpty()) return v
            if (v.contains(':')) {
                val prefix = v.substringBefore(':')
                return when {
                    prefix == m || prefix == m.substringAfterLast('/') -> v
                    prefix.contains('/') -> "$m:${v.substringAfterLast(':')}"
                    else -> v
                }
            }
            return if (m.contains('/')) "$m:$v" else v
        }

        /**
         * 从渠道模型列表里筛语音类模型：先排除明确不是 TTS 的（转写/嵌入/图像/视频等），
         * 再按关键词命中。宁滥勿缺——最终能不能合成，点选后试听才知道。
         */
        fun filterTtsModels(ids: List<String>): List<String> {
            val exclude = listOf(
                "whisper", "asr", "stt", "transcri", "sensevoice", "embed", "rerank",
                "moderation", "guard", "dall", "image", "flux", "diffusion", "video", "vision", "ocr", "clip",
            )
            val hint = listOf(
                "tts", "speech", "voice", "audio", "cosyvoice", "fish", "kokoro", "chattts",
                "megatts", "vits", "melo", "piper", "openaudio", "step-audio", "qwen-tts", "qwen3-tts", "minimax",
            )
            return ids.filter { id ->
                val m = id.lowercase()
                exclude.none { it in m } && hint.any { it in m }
            }
        }
    }

    /** message 一律是给用户看的中文，调用方直接展示；retryWithWav 标记该错误值得换 WAV 格式重试一次 */
    class CloudTtsException(message: String, internal val retryWithWav: Boolean = false) : RuntimeException(message)

    /** 地址规范化：http(s)://开头、以 /v1 结尾的接口根（补协议与 /v1，完整路径会剥回根） */
    fun apiBase(): String {
        var u = cfg.baseUrl.trim().trimEnd('/')
        if (u.isEmpty()) throw CloudTtsException("还没填在线朗读的接口地址")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        if (u.endsWith("/audio/speech", true)) u = u.dropLast("/audio/speech".length)
        if (!u.endsWith("/v1", true)) u = "$u/v1"
        return u
    }

    fun endpoint(): String = apiBase() + "/audio/speech"

    fun modelsEndpoint(): String = apiBase() + "/models"

    /**
     * 合成 [text]，把 16bit 小端 PCM 按块回调（单位：采样值，已统一到 [SAMPLE_RATE]）。
     * onPcm 回 false 立即断流停止。失败抛 [CloudTtsException]；onPcm 回 false 属正常停止，不抛。
     */
    override fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean) {
        val key = cfg.key.trim()
        if (key.isEmpty()) throw CloudTtsException("还没填在线朗读的 API Key，到设置页「朗读」里填")
        val voice = buildVoice(cfg.model, cfg.voice)
        try {
            streamPcmLive(text, speed, voice, key, onPcm)
            return
        } catch (e: CloudTtsException) {
            // 只有「这种格式该渠道不收」才值得换 WAV 重试；Key 错/限流/断网换格式也一样
            if (!e.retryWithWav) throw e
            Log.w(TAG, "流式 PCM 被拒（${e.message}），改用 WAV 整段重试 model=${cfg.model}")
        }
        streamWav(text, speed, voice, key, onPcm)
    }

    /** 第一次尝试：流式 PCM（硅基流动式，stream=true 边下边播） */
    private fun streamPcmLive(text: String, speed: Float, voice: String, key: String, onPcm: (ShortArray) -> Boolean) {
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            put("voice", voice)
            put("response_format", "pcm")
            put("sample_rate", SAMPLE_RATE)
            put("speed", speed.coerceIn(0.25f, 4.0f).toDouble())
            put("stream", true)
        }
        val resp = post(endpoint(), key, payload.toString())
        if (resp.code >= 400) {
            throw httpError(resp.code, resp.errorText, retryWithWav = resp.code in WAV_RETRY_CODES)
        }
        val ins = assertAudioBody(resp)
        Log.i(TAG, "语音请求发出：${endpoint()} model=${cfg.model} voice=$voice 格式=pcm-stream")
        try {
            val totalBytes = CloudTtsAudio.drainPcmStream(ins, onPcm)
            if (totalBytes < 0) return // onPcm 回 false：用户停止，正常返回不报错
            if (totalBytes == 0) throw CloudTtsException("语音接口没有返回音频")
            Log.i(TAG, "流式 PCM 完成：$totalBytes 字节 model=${cfg.model}")
        } catch (e: CloudTtsException) {
            throw e
        } catch (e: Exception) {
            throw CloudTtsException("朗读中断：${e.message ?: "网络断开"}")
        } finally {
            try {
                ins.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 第二次尝试：极简 body 要 WAV 整段（OpenAI 官方与多数中转站都支持），解析后重采样统一采样率 */
    private fun streamWav(text: String, speed: Float, voice: String, key: String, onPcm: (ShortArray) -> Boolean) {
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
            put("speed", speed.coerceIn(0.25f, 4.0f).toDouble())
        }
        val resp = post(endpoint(), key, payload.toString())
        if (resp.code >= 400) throw httpError(resp.code, resp.errorText)
        val bytes = resp.bodyStream?.readBytes() ?: ByteArray(0)
        if (bytes.isEmpty()) throw CloudTtsException("语音接口没有返回音频")
        if (bytes[0] == '{'.code.toByte()) throw jsonBodyError(bytes.toString(Charsets.UTF_8))
        val wav = try {
            CloudTtsAudio.parseWav(bytes)
        } catch (e: CloudTtsAudio.WavException) {
            throw CloudTtsException("在线朗读失败：${e.message}")
        }
        var pcm = wav.pcm
        if (wav.sampleRate != SAMPLE_RATE) {
            pcm = CloudTtsAudio.resample(pcm, wav.sampleRate, SAMPLE_RATE)
            Log.i(TAG, "WAV ${wav.sampleRate}Hz 重采样到 ${SAMPLE_RATE}Hz：${wav.pcm.size} → ${pcm.size} 采样")
        }
        Log.i(TAG, "WAV 合成完成：${wav.pcm.size} 采样 @${wav.sampleRate}Hz model=${cfg.model}")
        onPcm(pcm)
    }

    /** 拉渠道模型列表（OpenAI 兼容 GET /models），供设置页「检索模型」；错误消息与朗读同风格 */
    fun fetchModels(): List<String> {
        val key = cfg.key.trim()
        if (key.isEmpty()) throw CloudTtsException("还没填在线朗读的 API Key，先填 Key 再检索模型")
        val resp = try {
            transport.get(modelsEndpoint(), key)
        } catch (e: Exception) {
            throw CloudTtsException("无法连接语音接口，请检查手机网络和设置里的地址")
        }
        if (resp.code == 401 || resp.code == 403) {
            throw CloudTtsException("拉取模型列表被拒（HTTP ${resp.code}），请检查 API Key 是否正确")
        }
        if (resp.code >= 400) {
            if (resp.code == 404) throw CloudTtsException("这个渠道不提供模型列表（HTTP 404），直接手动输入模型名即可")
            throw CloudTtsException("拉取模型列表失败（HTTP ${resp.code}）${serverMessage(resp.errorText)?.let { "：$it" } ?: ""}")
        }
        val body = resp.bodyStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        runCatching { resp.bodyStream?.close() }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.distinct().toList()
        Log.i(TAG, "模型列表拉到 ${ids.size} 个（HTTP ${resp.code}）")
        return ids
    }

    /** 服务端错误体里的 message 字段；"error" 也兜一层（OpenAI 式 {"error":{"message":...}} 拿不到嵌套就算了） */
    private fun serverMessage(body: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun httpError(code: Int, body: String, retryWithWav: Boolean = false): CloudTtsException {
        val msg = when {
            code == 401 || code == 403 -> "语音接口拒绝了请求，请检查 API Key 是否正确"
            code == 429 -> "语音接口限流了，稍等一会儿再试"
            // TtsPlayer 展示时会再套「在线朗读失败：」前缀，这里别重复
            else -> "接口返回错误（HTTP $code）${serverMessage(body)?.let { "：$it" } ?: ""}"
        }
        return CloudTtsException(msg, retryWithWav)
    }

    private fun jsonBodyError(bodyText: String): CloudTtsException =
        CloudTtsException(
            "语音接口没返回音频${serverMessage(bodyText)?.let { "：$it" } ?: "，返回了文本而不是音频"}",
            retryWithWav = true,
        )

    /** 200 但返回 JSON/HTML/text 的渠道（错误包 200 或网关页）：把响应体读出来当错误，绝不喂给播放器 */
    private fun assertAudioBody(resp: CloudTtsTransport.Resp): InputStream {
        val raw = resp.bodyStream ?: throw CloudTtsException("语音接口没有返回音频")
        val ct = resp.contentType.lowercase()
        val buffered = BufferedInputStream(raw)
        val ctSaysText = "json" in ct || "html" in ct || "text/" in ct
        if (ctSaysText) {
            val text = buffered.readBytes().toString(Charsets.UTF_8)
            runCatching { buffered.close() }
            throw jsonBodyError(text)
        }
        // Content-Type 缺失时偷看首字节：JSON 错误体以 { 开头；真 PCM 流有 audio/octet-stream 的 Content-Type 兜着
        if (ct.isBlank()) {
            buffered.mark(1)
            val first = buffered.read()
            buffered.reset()
            if (first == '{'.code) {
                val text = buffered.readBytes().toString(Charsets.UTF_8)
                runCatching { buffered.close() }
                throw jsonBodyError(text)
            }
        }
        return buffered
    }

    private fun post(url: String, key: String, payload: String): CloudTtsTransport.Resp =
        try {
            transport.post(url, key, payload)
        } catch (e: CloudTtsException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "语音请求失败：${e.javaClass.simpleName} ${e.message}")
            throw CloudTtsException("无法连接语音接口，请检查手机网络和设置里的地址")
        }
}

/** 在线朗读客户端的统一播放接口：TtsPlayer 只依赖它做流式朗读，具体协议在 [CloudTts]（OpenAI 兼容）与 [ElevenTts]（官方） */
interface CloudTtsClient {
    fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean)
}

/** 在线朗读配置（调用方从设置读好传入，本层不读设置，与 TtsPlayer 的引擎字符串同约定） */
data class CloudTtsConfig(
    val baseUrl: String,
    val key: String,
    val voice: String,
    val model: String = CloudTts.DEFAULT_MODEL,
    /** 服务档位：官方 ElevenLabs（voice 存官方音色 ID）/ 自定义 OpenAI 兼容（默认，向后兼容省略传参） */
    val provider: String = TtsCloudProvider.CUSTOM,
) {
    companion object {
        /** 按设置快照组装在线朗读配置；引擎没选「在线朗读」返回 null。官方档地址内置，自定义档用四件套 */
        fun fromSettings(s: com.tingsiwei.app.data.AppSettings): CloudTtsConfig? =
            when {
                s.ttsEngine != TtsEngine.CLOUD -> null
                s.ttsCloudProvider == TtsCloudProvider.ELEVEN -> CloudTtsConfig(
                    TtsElevenDefaults.BASE_URL, s.ttsElevenKey, s.ttsElevenVoice, s.ttsElevenModel, TtsCloudProvider.ELEVEN,
                )
                else -> CloudTtsConfig(s.ttsCloudUrl, s.ttsCloudKey, s.ttsCloudVoice, s.ttsCloudModel)
            }
    }
}

/** HTTP 往来接缝：生产用 [OkHttpCloudTtsTransport]，单测注入假实现（不加任何测试依赖）。 */
interface CloudTtsTransport {
    class Resp(
        val code: Int,
        val bodyStream: InputStream?,
        val errorText: String,
        val contentType: String = "",
    )

    /** [authHeaderName]：鉴权头名。Authorization（缺省）按 Bearer 拼；其他头名（如 ElevenLabs 的 xi-api-key）直接用 key 原值 */
    fun post(url: String, key: String, jsonBody: String, authHeaderName: String = "Authorization"): Resp
    fun get(url: String, key: String, authHeaderName: String = "Authorization"): Resp
}

private val WAV_RETRY_CODES = setOf(400, 404, 415, 422)

object OkHttpCloudTtsTransport : CloudTtsTransport {

    // readTimeout 是相邻两个字节包的最大间隔；流式合成持续出字节，30 秒足够宽容。
    // 连接池与线程池继承 util.Http.base，与 LLM/模型下载共用
    private val client = Http.base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun post(url: String, key: String, jsonBody: String, authHeaderName: String): CloudTtsTransport.Resp {
        val req = Request.Builder()
            .url(url)
            .header(authHeaderName, if (authHeaderName == "Authorization") "Bearer $key" else key)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val contentType = resp.header("Content-Type").orEmpty()
        return if (resp.isSuccessful) {
            // 成功路径不能 close：body 流要在整个朗读期间慢慢读
            CloudTtsTransport.Resp(resp.code, resp.body?.byteStream(), "", contentType)
        } else {
            val err = try {
                resp.body?.string().orEmpty()
            } finally {
                resp.close()
            }
            CloudTtsTransport.Resp(resp.code, null, err, contentType)
        }
    }

    override fun get(url: String, key: String, authHeaderName: String): CloudTtsTransport.Resp {
        val req = Request.Builder()
            .url(url)
            .header(authHeaderName, if (authHeaderName == "Authorization") "Bearer $key" else key)
            .get()
            .build()
        val resp = client.newCall(req).execute()
        val contentType = resp.header("Content-Type").orEmpty()
        return if (resp.isSuccessful) {
            CloudTtsTransport.Resp(resp.code, resp.body?.byteStream(), "", contentType)
        } else {
            val err = try {
                resp.body?.string().orEmpty()
            } finally {
                resp.close()
            }
            CloudTtsTransport.Resp(resp.code, null, err, contentType)
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
