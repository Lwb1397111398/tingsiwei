package com.tingsiwei.app

import com.tingsiwei.app.data.TtsCloudProvider
import com.tingsiwei.app.data.TtsLatentDefaults
import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.CloudTtsTransport
import com.tingsiwei.app.tts.FakeCloudTtsStream
import com.tingsiwei.app.tts.LatentTts
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 公益站 Latent Audio 客户端：请求拼装、双 Key 轮换与限流互备、Idempotency-Key、504 job 收取、错误映射、WAV 解析 */
class LatentTtsTest {

    /** 按请求次数放剧本：第 1 次用 first 剧本，第 2 次起用 retry 剧本（retryCode<0 表示始终同一个） */
    private class FakeTransport(
        private val firstCode: Int = 200,
        private val firstError: String = "",
        private val retryCode: Int = -1,
        private val retryError: String = "",
        private val wav: ByteArray = ByteArray(0),
        private val getCode: Int = 200,
        private val getWav: ByteArray = ByteArray(0),
    ) : CloudTtsTransport {
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val idemKeys = mutableListOf<String>()
        val gets = mutableListOf<String>()
        val getKeys = mutableListOf<String>()

        override fun post(
            url: String,
            key: String,
            jsonBody: String,
            authHeaderName: String,
            extraHeaders: Map<String, String>,
        ): CloudTtsTransport.Resp {
            urls += url
            keys += key
            bodies += jsonBody
            idemKeys += extraHeaders["Idempotency-Key"].orEmpty()
            val retrying = retryCode >= 0 && bodies.size >= 2
            val code = if (retrying) retryCode else firstCode
            val err = if (retrying) retryError else firstError
            return if (code == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(listOf(wav)), "", "audio/wav")
            } else {
                CloudTtsTransport.Resp(code, null, err, "application/json")
            }
        }

        override fun get(url: String, key: String, authHeaderName: String): CloudTtsTransport.Resp {
            gets += url
            getKeys += key
            return if (getCode == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(listOf(getWav)), "", "audio/wav")
            } else {
                CloudTtsTransport.Resp(getCode, null, "", "application/json")
            }
        }
    }

    private fun client(t: FakeTransport, key1: String = "key-one", key2: String = "key-two", voice: String = "miko") =
        LatentTts(t, CloudTtsConfig(TtsLatentDefaults.BASE_URL, key1, voice, TtsLatentDefaults.MODEL, TtsCloudProvider.LATENT, key2))

    private inline fun catchErr(block: () -> Unit): CloudTts.CloudTtsException =
        try {
            block()
            error("没有抛异常")
        } catch (e: CloudTts.CloudTtsException) {
            e
        }

    /** 组一段最小合法 WAV（16bit 单声道，与 CloudTtsTest 同构） */
    private fun wavBytes(sampleRate: Int, samples: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            data[2 * i] = (s.toInt() and 0xFF).toByte()
            data[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        fun le(v: Int, n: Int) = (0 until n).forEach { out.write((v shr (8 * it)) and 0xFF) }
        fun four(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        four("RIFF"); le(36 + data.size, 4); four("WAVE")
        four("fmt "); le(16, 4); le(1, 2); le(1, 2); le(sampleRate, 4); le(sampleRate * 2, 4); le(2, 2); le(16, 2)
        four("data"); le(data.size, 4)
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `请求拼装 内置端点 请求体wav格式 带幂等键`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(1, 2, 3)))
        client(t).streamPcm("你好呀。", 1.5f) { true }
        assertEquals("https://audio.latent.moe/v1/audio/speech", t.urls.single())
        assertEquals("key-one", t.keys.single())
        val body = kotlinx.serialization.json.Json.parseToJsonElement(t.bodies[0]).jsonObject
        assertEquals("latent-audio", body["model"]!!.jsonPrimitive.content)
        assertEquals("miko", body["voice"]!!.jsonPrimitive.content)
        assertEquals("你好呀。", body["input"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertTrue("幂等键必须有", t.idemKeys.single().isNotBlank())
        assertTrue("不该带 speed 参数（站点不支持）", body["speed"] == null)
        assertTrue("不该带 stream 参数（站点只收整段）", body["stream"] == null)
    }

    @Test
    fun `双Key句间轮换 两句一轮`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(1, 2)))
        val c = client(t)
        c.streamPcm("第一句。", 1f) { true }
        c.streamPcm("第二句。", 1f) { true }
        c.streamPcm("第三句。", 1f) { true }
        assertEquals(listOf("key-one", "key-two", "key-one"), t.keys)
        assertNotEquals("每次请求的幂等键必须换新（同键会返回首次结果）", t.idemKeys[0], t.idemKeys[1])
    }

    @Test
    fun `只有一把Key时一直用它`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(1, 2)))
        val c = client(t, key2 = "")
        c.streamPcm("一。", 1f) { true }
        c.streamPcm("二。", 1f) { true }
        assertEquals(listOf("key-one", "key-one"), t.keys)
    }

    @Test
    fun `两把Key都空 报错不发请求`() {
        val t = FakeTransport()
        val e = catchErr { client(t, key1 = " ", key2 = "").streamPcm("测", 1f) { true } }
        assertTrue(e.message!!.contains("API Key"))
        assertEquals(0, t.urls.size)
    }

    @Test
    fun `没填音色 报错不发请求`() {
        val t = FakeTransport()
        val e = catchErr { client(t, voice = " ").streamPcm("测", 1f) { true } }
        assertTrue("要提音色：${e.message}", e.message!!.contains("音色"))
        assertEquals(0, t.urls.size)
    }

    @Test
    fun `wav整段回调 采样率不一致时重采样`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(100, -200, 300)))
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm -> got += pcm; true }
        assertEquals(listOf<Short>(100, -200, 300), got.single().toList())

        val t44 = FakeTransport(wav = wavBytes(48000, shortArrayOf(0, 1000, 2000, 3000)))
        val got44 = mutableListOf<ShortArray>()
        client(t44).streamPcm("测", 1f) { pcm -> got44 += pcm; true }
        val resampled = got44.single()
        assertEquals("48000 重采样到统一 24000（4 样本减半为 2）", 2, resampled.size)
    }

    @Test
    fun `onPcm回false正常停止不抛异常`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(1, 2)))
        client(t).streamPcm("测", 1f) { false }
    }

    @Test
    fun `429并发满 换另一把Key重试成功`() {
        val t = FakeTransport(
            firstCode = 429,
            firstError = "{\"error\":{\"message\":\"too many requests\",\"code\":\"concurrent_limit\"}}",
            retryCode = 200,
            wav = wavBytes(24000, shortArrayOf(7, 8)),
        )
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm -> got += pcm; true }
        assertEquals("第一次 Key1 被限流，第二次换 Key2 成功", listOf("key-one", "key-two"), t.keys)
        assertEquals(2, t.idemKeys.size)
        assertNotEquals("重试必须换幂等键", t.idemKeys[0], t.idemKeys[1])
        assertEquals(listOf<Short>(7, 8), got.single().toList())
    }

    @Test
    fun `两把Key都限流 报人话不再重试`() {
        val t = FakeTransport(
            firstCode = 429,
            firstError = "{\"error\":{\"code\":\"quota_exhausted\"}}",
            retryCode = 429,
            retryError = "{\"error\":{\"code\":\"quota_exhausted\"}}",
        )
        val e = catchErr { client(t).streamPcm("测", 1f) { true } }
        assertTrue("要提配额：${e.message}", e.message!!.contains("配额") || e.message!!.contains("限流"))
        assertEquals("最多两把 Key 各试一次", 2, t.urls.size)
    }

    @Test
    fun `限流但只有一把Key 不重试直接报`() {
        val t = FakeTransport(firstCode = 429, firstError = "{\"error\":{\"code\":\"concurrent_limit\"}}")
        val e = catchErr { client(t, key2 = "").streamPcm("测", 1f) { true } }
        assertTrue(e.message!!.contains("并发"))
        assertEquals(1, t.urls.size)
    }

    @Test
    fun `401提Key过期 404提音色不存在`() {
        val t401 = FakeTransport(firstCode = 401, firstError = "{\"error\":{\"code\":\"invalid_api_key\"}}")
        val e401 = catchErr { client(t401).streamPcm("测", 1f) { true } }
        assertTrue("401 要提 Key 有效期：${e401.message}", e401.message!!.contains("过期") || e401.message!!.contains("Key"))
        assertEquals("401 换 Key 没用，不重试", 1, t401.urls.size)

        val t404 = FakeTransport(firstCode = 404, firstError = "{\"error\":{\"message\":\"No such voice\",\"code\":\"voice_not_found\"}}")
        val e404 = catchErr { client(t404).streamPcm("测", 1f) { true } }
        assertTrue("404 要提音色名：${e404.message}", e404.message!!.contains("miko"))
        assertEquals(1, t404.urls.size)
    }

    @Test
    fun `504带jobId 转收取接口拿成品`() {
        val t = FakeTransport(
            firstCode = 504,
            firstError = "{\"error\":{\"code\":\"timeout\",\"job_id\":\"job-123\"}}",
            getWav = wavBytes(24000, shortArrayOf(11, 22, 33)),
        )
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm -> got += pcm; true }
        assertEquals("收取接口", "https://audio.latent.moe/api/jobs/job-123/audio", t.gets.single())
        assertEquals("收取用同一把 Key", "key-one", t.getKeys.single())
        assertEquals(listOf<Short>(11, 22, 33), got.single().toList())
    }

    @Test
    fun `504收取不到成品 明说稍后再试`() {
        val t = FakeTransport(
            firstCode = 504,
            firstError = "{\"error\":{\"code\":\"timeout\",\"job_id\":\"job-x\"}}",
            getCode = 404,
        )
        val e = catchErr { client(t).streamPcm("测", 1f) { true } }
        assertTrue("要提任务与重试：${e.message}", e.message!!.contains("job-x"))
    }

    @Test
    fun `503队列满报人话`() {
        val t = FakeTransport(firstCode = 503, firstError = "queue full")
        val e = catchErr { client(t, key2 = "").streamPcm("测", 1f) { true } }
        assertTrue(e.message!!.contains("队列"))
    }

    @Test
    fun `fromSettings 公益站分支组装双Key`() {
        val s = com.tingsiwei.app.data.AppSettings(
            llmUrl = "", llmKey = "", llmModel = "", transcribeMode = "", allowExpand = true, ttsRate = 1f,
            ttsEngine = com.tingsiwei.app.data.TtsEngine.CLOUD,
            ttsCloudProvider = TtsCloudProvider.LATENT,
            ttsLatentKey1 = "k1", ttsLatentKey2 = "k2", ttsLatentVoice = "miko",
        )
        val cfg = CloudTtsConfig.fromSettings(s)!!
        assertEquals(TtsLatentDefaults.BASE_URL, cfg.baseUrl)
        assertEquals("k1", cfg.key)
        assertEquals("k2", cfg.key2)
        assertEquals("miko", cfg.voice)
        assertEquals(TtsLatentDefaults.MODEL, cfg.model)
        assertEquals(TtsCloudProvider.LATENT, cfg.provider)
    }

    @Test
    fun `deliversWholeChunks 仅整段型为真`() {
        assertTrue("LatentTts 是整段型", LatentTts(FakeTransport(), CloudTtsConfig("https://audio.latent.moe", "k", "v")).deliversWholeChunks)
        val streaming = object : com.tingsiwei.app.tts.CloudTtsClient {
            override fun streamPcm(text: String, speed: Float, onPcm: (ShortArray) -> Boolean) {}
        }
        assertTrue("接口默认流式不能预取", !streaming.deliversWholeChunks)
    }

    @Test
    fun `音色归一化 slug原样 网址取末段`() {
        assertEquals("aria", LatentTts.normalizeVoice("aria"))
        assertEquals("miko", LatentTts.normalizeVoice("  miko  "))
        assertEquals("aria", LatentTts.normalizeVoice("https://audio.latent.moe/voice/aria"))
        assertEquals("aria", LatentTts.normalizeVoice("audio.latent.moe/voice/aria/"))
        assertEquals("aria", LatentTts.normalizeVoice("voice/aria?x=1"))
        assertEquals("aria", LatentTts.normalizeVoice("https://audio.latent.moe/voice/aria#top"))
        assertEquals("", LatentTts.normalizeVoice("   "))
    }

    @Test
    fun `音色填成语音页网址 自动取末段发请求`() {
        val t = FakeTransport(wav = wavBytes(24000, shortArrayOf(1, 2)))
        client(t, voice = "https://audio.latent.moe/voice/aria").streamPcm("测", 1f) { true }
        val body = kotlinx.serialization.json.Json.parseToJsonElement(t.bodies[0]).jsonObject
        assertEquals("aria", body["voice"]!!.jsonPrimitive.content)
    }
}
