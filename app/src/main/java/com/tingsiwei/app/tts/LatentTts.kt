package com.tingsiwei.app.tts

import android.util.Log
import com.tingsiwei.app.data.TtsCloudProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 公益站 Latent Audio（audio.latent.moe）专属客户端。
 * 与 OpenAI 兼容格式的差异：模型参数收下即忽略；无 speed 参数（语速滑条对此档无效）；
 * 没有 /models、/voices 列表接口——音色是网站语音库里已发布语音的地址，用户手填；
 * 每号并发 4 路、每周配额 14400 秒，超过回 429（code 区分 concurrent_limit / quota_exhausted）。
 * 请求策略：整段 WAV（站点不支持流式 PCM；mp3/opus 还得解码，WAV 解析与重采样全部复用 CloudTtsAudio）。
 * 双 Key 轮换：每次请求按序号在两把 Key 间交替（摊薄单号限流）；撞 429 换另一把 Key 立即重试一次。
 * 生成等待约 1 分钟后站点回 504 + job_id，再用 GET /api/jobs/{id}/audio 收取成品。
 * 每次请求都带新的 Idempotency-Key（同键重试会返回首次结果，我们的重试都是明确失败后的新请求，键必须换新）。
 */
class LatentTts(
    private val transport: CloudTtsTransport = OkHttpCloudTtsTransport,
    private val cfg: CloudTtsConfig,
) : CloudTtsClient {

    /** 整段返回型客户端：TtsPlayer 可在播放当前句时预取下一句（双 Key 正好一句一把） */
    override val deliversWholeChunks: Boolean = true

    companion object {
        /** 与播放层统一的采样率；服务端 WAV 采样率不同则重采样 */
        const val SAMPLE_RATE = CloudTts.SAMPLE_RATE

        private const val TAG = "LatentTts"

        /** 这两类限流值得换另一把 Key 重试一次；其余错误换 Key 也没用 */
        private val SWITCH_KEY_CODES = setOf("concurrent_limit", "quota_exhausted", "queue_full")
    }

    /** 请求序号：句间轮换 Key 的依据（两句一轮） */
    private val callCounter = AtomicLong(0)

    /** 双 Key 列表（去空白）；至少一把才可朗读 */
    private fun keys(): List<String> =
        listOf(cfg.key.trim(), cfg.key2.trim()).filter { it.isNotEmpty() }

    fun endpoint(): String = apiBase() + "/audio/speech"

    private fun apiBase(): String {
        var u = cfg.baseUrl.trim().trimEnd('/')
        if (u.isEmpty()) throw CloudTts.CloudTtsException("还没填公益站的接口地址")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        if (!u.endsWith("/v1", true)) u = "$u/v1"
        return u
    }

    /**
     * 合成 [text]：返回整句 16bit PCM（已统一到 [SAMPLE_RATE]）。
     * onPcm 回 false 属正常停止（结果直接丢弃），不抛异常。失败抛 [CloudTts.CloudTtsException]。
     */
    override fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean) {
        val voice = cfg.voice.trim()
        if (voice.isEmpty()) {
            throw CloudTts.CloudTtsException("还没填音色：到公益站语音库挑一个已发布的语音，把它的地址填进设置")
        }
        val ks = keys()
        if (ks.isEmpty()) {
            throw CloudTts.CloudTtsException("还没填公益站的 API Key：到设置页「朗读」里填（两个账号都填上才能轮换防限流）")
        }
        val first = ks[(callCounter.getAndIncrement() % ks.size).toInt()]
        val pcm = try {
            requestOnce(text, voice, first, ks.indexOf(first) + 1)
        } catch (e: CloudTts.CloudTtsException) {
            // 只有「这把 Key 被限流/额度尽/队列满」才值得换另一把立即重试；Key 错、音色错换了也一样
            if (!e.switchKey || ks.size < 2) throw e
            val other = ks.first { it != first }
            Log.w(TAG, "Key#${ks.indexOf(first) + 1} 被限流，换 Key#${ks.indexOf(other) + 1} 重试：${e.message}")
            requestOnce(text, voice, other, ks.indexOf(other) + 1)
        }
        onPcm(pcm)
    }

    /** 单次请求：POST /audio/speech 要 WAV 整段；504 带 job_id 时转 GET 收取 */
    private fun requestOnce(text: String, voice: String, key: String, keyNo: Int): ShortArray {
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val resp = post(
            endpoint(), key,
            payload.toString(),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        Log.i(TAG, "语音请求发出 Key#$keyNo voice=$voice 字数=${text.length} 格式=wav")
        if (resp.code == 504) {
            // 站点最多等约 1 分钟，没生成完就回 504 + job_id：转收取接口拿成品
            val jobId = Regex("\"job_id\"\\s*:\\s*\"([^\"]+)\"").find(resp.errorText)?.groupValues?.get(1)
            if (jobId != null) return collectJob(jobId, key, keyNo)
            throw CloudTts.CloudTtsException("公益站生成超时，稍等再试")
        }
        if (resp.code >= 400) throw httpError(resp.code, resp.errorText, keyNo)
        val bytes = resp.bodyStream?.readBytes() ?: ByteArray(0)
        if (bytes.isEmpty()) throw CloudTts.CloudTtsException("公益站没有返回音频")
        if (bytes[0] == '{'.code.toByte()) {
            val body = bytes.toString(Charsets.UTF_8)
            val code = errorCode(body)
            throw CloudTts.CloudTtsException(
                "公益站没返回音频${serverMessage(body)?.let { "：$it" } ?: ""}" +
                    (code?.let { "（$it）" } ?: ""),
                switchKey = code in SWITCH_KEY_CODES,
            )
        }
        val wav = try {
            CloudTtsAudio.parseWav(bytes)
        } catch (e: CloudTtsAudio.WavException) {
            throw CloudTts.CloudTtsException("在线朗读失败：${e.message}")
        }
        var pcm = wav.pcm
        if (wav.sampleRate != SAMPLE_RATE) {
            pcm = CloudTtsAudio.resample(pcm, wav.sampleRate, SAMPLE_RATE)
            Log.i(TAG, "WAV ${wav.sampleRate}Hz 重采样到 ${SAMPLE_RATE}Hz：${wav.pcm.size} → ${pcm.size} 采样")
        }
        Log.i(TAG, "WAV 合成完成 Key#$keyNo：${wav.pcm.size} 采样 @${wav.sampleRate}Hz 时长≈${resp.durationMs ?: "?"}ms")
        return pcm
    }

    /** 收取 504 超时任务的成品：任务此刻可能还没生成完，收不到就明说让用户重试 */
    private fun collectJob(jobId: String, key: String, keyNo: Int): ShortArray {
        val url = "${apiBase().removeSuffix("/v1")}/api/jobs/$jobId/audio"
        Log.i(TAG, "504 转 job 收取 Key#$keyNo：$url")
        val resp = try {
            transport.get(url, key)
        } catch (e: Exception) {
            throw CloudTts.CloudTtsException("公益站生成超时后收取成品失败，请重试朗读本段")
        }
        if (resp.code != 200) {
            throw CloudTts.CloudTtsException("公益站生成超时（任务 $jobId 还没完成），稍后再试朗读本段")
        }
        val bytes = resp.bodyStream?.readBytes() ?: ByteArray(0)
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) {
            throw CloudTts.CloudTtsException("公益站成品收取失败（任务 $jobId），请重试朗读本段")
        }
        val wav = try {
            CloudTtsAudio.parseWav(bytes)
        } catch (e: CloudTtsAudio.WavException) {
            throw CloudTts.CloudTtsException("在线朗读失败：${e.message}")
        }
        var pcm = wav.pcm
        if (wav.sampleRate != SAMPLE_RATE) pcm = CloudTtsAudio.resample(pcm, wav.sampleRate, SAMPLE_RATE)
        Log.i(TAG, "job 收取成功 Key#$keyNo：${wav.pcm.size} 采样 @${wav.sampleRate}Hz")
        return pcm
    }

    private fun post(url: String, key: String, jsonBody: String, extraHeaders: Map<String, String>): CloudTtsTransport.Resp =
        try {
            transport.post(url, key, jsonBody, extraHeaders = extraHeaders)
        } catch (e: CloudTts.CloudTtsException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "语音请求失败：${e.javaClass.simpleName} ${e.message}")
            throw CloudTts.CloudTtsException("连不上公益站，请检查手机网络（该站在境外，网络波动属正常，重试即可）")
        }

    /** 站点错误是 OpenAI 信封 {"error":{"message","code"}}，文档要求按 code 分支而非 message */
    private fun errorCode(body: String): String? =
        Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun serverMessage(body: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun httpError(code: Int, body: String, keyNo: Int): CloudTts.CloudTtsException {
        val siteCode = errorCode(body)
        val switchKey = code == 429 || siteCode in SWITCH_KEY_CODES
        val msg = when {
            code == 401 -> "公益站的 API Key 无效或已过期（Key 30 天有效，登录网站可续期）"
            code == 404 -> "音色「${cfg.voice.trim()}」在公益站不存在或未发布：去网站语音库确认该语音已发布，再复制它的地址填进设置"
            siteCode == "concurrent_limit" -> "公益站 Key#$keyNo 的并发满了（每号同时 4 路），换另一把 Key 重试中"
            siteCode == "quota_exhausted" -> "公益站 Key#$keyNo 本周配额用完了（每号每周约 4 小时），换另一把 Key"
            siteCode == "queue_full" -> "公益站队列满了，稍等再试"
            siteCode == "input_too_long" -> "这一句太长，公益站单次最多约 300 秒的量"
            code == 429 -> "公益站限流了（${siteCode ?: "未知原因"}），换另一把 Key 重试中"
            code == 503 -> "公益站队列满了，稍等再试"
            code == 502 -> "公益站服务端生成失败，稍后再试"
            else -> "公益站返回错误（HTTP $code）${serverMessage(body)?.let { "：$it" } ?: ""}"
        }
        return CloudTts.CloudTtsException(msg, switchKey = switchKey)
    }
}
