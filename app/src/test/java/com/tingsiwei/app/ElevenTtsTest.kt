package com.tingsiwei.app

import com.tingsiwei.app.data.AppSettings
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.data.TtsCloudProvider
import com.tingsiwei.app.data.TtsElevenDefaults
import com.tingsiwei.app.data.TtsEngine
import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.CloudTtsTransport
import com.tingsiwei.app.tts.ElevenTts
import com.tingsiwei.app.tts.FakeCloudTtsStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ElevenLabs 官方朗读客户端：URL/请求体/鉴权头、语速夹取、错误映射、音色列表解析、流式解码与提前停止、设置工厂 */
class ElevenTtsTest {

    /** 记下请求并按剧本回响应；authHeaders 记录每次请求的鉴权头名（官方档必须是 xi-api-key） */
    private class FakeTransport(
        private val code: Int = 200,
        private val chunks: List<ByteArray> = emptyList(),
        private val errorText: String = "",
        private val contentType: String = "audio/pcm",
    ) : CloudTtsTransport {
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val authHeaders = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var getCode = 200
        var getBody = ""

        override fun post(url: String, key: String, jsonBody: String, authHeaderName: String): CloudTtsTransport.Resp {
            urls += url
            keys += key
            authHeaders += authHeaderName
            bodies += jsonBody
            return if (code == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(chunks), "", contentType)
            } else {
                CloudTtsTransport.Resp(code, null, errorText, contentType)
            }
        }

        override fun get(url: String, key: String, authHeaderName: String): CloudTtsTransport.Resp {
            urls += url
            keys += key
            authHeaders += authHeaderName
            return if (getCode == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(listOf(getBody.toByteArray())), "", "application/json")
            } else {
                CloudTtsTransport.Resp(getCode, null, getBody, "application/json")
            }
        }
    }

    private fun client(t: FakeTransport) = ElevenTts(
        t,
        CloudTtsConfig(TtsElevenDefaults.BASE_URL, "test-key-fake", "CwhRBWXzGAHq8TQ4Fs17", "eleven_multilingual_v2", TtsCloudProvider.ELEVEN),
    )

    private inline fun catchErr(block: () -> Unit): CloudTts.CloudTtsException =
        try {
            block()
            error("没有抛异常")
        } catch (e: CloudTts.CloudTtsException) {
            e
        }

    @Test
    fun `请求拼装 URL带音色ID与pcm格式 鉴权用xi-api-key`() {
        val t = FakeTransport(chunks = listOf(byteArrayOf(1, 2)))
        client(t).streamPcm("你好。", 1f) { true }
        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/CwhRBWXzGAHq8TQ4Fs17?output_format=pcm_24000",
            t.urls[0],
        )
        assertEquals("xi-api-key", t.authHeaders[0])
        assertEquals("test-key-fake", t.keys[0])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(t.bodies[0]).jsonObject
        assertEquals("你好。", body["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_multilingual_v2", body["model_id"]!!.jsonPrimitive.content)
        assertEquals(1.0, body["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `地址规范化 补协议剥尾斜杠`() {
        val t = FakeTransport(chunks = listOf(byteArrayOf(1, 2)))
        ElevenTts(t, CloudTtsConfig("api.elevenlabs.io/v1/", "k", "voice1")).streamPcm("测", 1f) { true }
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/voice1?output_format=pcm_24000", t.urls[0])
    }

    @Test
    fun `语速夹到官方0点7到1点2区间`() {
        val fast = FakeTransport(chunks = listOf(byteArrayOf(1, 2)))
        client(fast).streamPcm("测", 2f) { true }
        assertEquals(
            1.2,
            kotlinx.serialization.json.Json.parseToJsonElement(fast.bodies[0]).jsonObject["voice_settings"]!!
                .jsonObject["speed"]!!.jsonPrimitive.content.toDouble(),
            1e-4, // Float 1.2f 转 Double 是 1.2000000476…，容差放到位
        )
        // 官方严格校验不容忍长尾：序列化文本必须是干净的 1.2（Float 直接 toDouble 会 400）
        assertTrue(
            "speed 序列化不能带 Float 长尾：${fast.bodies[0]}",
            fast.bodies[0].contains("\"speed\":1.2,") || fast.bodies[0].contains("\"speed\":1.2}"),
        )
        val slow = FakeTransport(chunks = listOf(byteArrayOf(1, 2)))
        client(slow).streamPcm("测", 0.5f) { true }
        assertEquals(
            0.7,
            kotlinx.serialization.json.Json.parseToJsonElement(slow.bodies[0]).jsonObject["voice_settings"]!!
                .jsonObject["speed"]!!.jsonPrimitive.content.toDouble(),
            1e-4,
        )
    }

    @Test
    fun `没填Key或音色 报中文错且不发请求`() {
        val t = FakeTransport()
        val e1 = catchErr { ElevenTts(t, CloudTtsConfig(TtsElevenDefaults.BASE_URL, " ", "voice1")).streamPcm("测", 1f) { true } }
        assertTrue("要提 API Key：${e1.message}", e1.message!!.contains("API Key"))
        assertEquals("没发请求", 0, t.urls.size)
        val e2 = catchErr { ElevenTts(t, CloudTtsConfig(TtsElevenDefaults.BASE_URL, "k", " ")).streamPcm("测", 1f) { true } }
        assertTrue("要提音色：${e2.message}", e2.message!!.contains("音色"))
        assertEquals("没发请求", 0, t.urls.size)
    }

    @Test
    fun `错误映射 401提Key 402提额度 429提限流 400带服务端消息`() {
        val e401 = catchErr { client(FakeTransport(code = 401, errorText = "quota_exceeded")).streamPcm("测", 1f) { true } }
        assertTrue("401 要提 Key：${e401.message}", e401.message!!.contains("API Key"))
        val e402 = catchErr { client(FakeTransport(code = 402, errorText = "payment")).streamPcm("测", 1f) { true } }
        assertTrue("402 要提额度：${e402.message}", e402.message!!.contains("额度"))
        val e429 = catchErr { client(FakeTransport(code = 429, errorText = "rate limited")).streamPcm("测", 1f) { true } }
        assertTrue("429 要提限流：${e429.message}", e429.message!!.contains("限流"))
        val e400 = catchErr {
            client(FakeTransport(code = 422, errorText = "{\"detail\":{\"message\":\"Invalid speed\"}}")).streamPcm("测", 1f) { true }
        }
        assertTrue("422 要带服务端消息：${e400.message}", e400.message!!.contains("Invalid speed"))
    }

    @Test
    fun `PCM 跨块解码与提前停止`() {
        // 三字节一块 + 两字节一块：块边界把样本劈开也要拼对
        val t = FakeTransport(chunks = listOf(byteArrayOf(0x01, 0x02, 0x03), byteArrayOf(0x04, 0x05, 0x06)))
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm ->
            got += pcm
            true
        }
        assertEquals(listOf<Short>(513, 1027, 1541), got.flatMap { it.toList() })

        val t2 = FakeTransport(chunks = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6)))
        var calls = 0
        client(t2).streamPcm("测", 1f) { _ ->
            calls++
            false
        }
        assertEquals("回调回 false 后不再回调", 1, calls)
    }

    @Test
    fun `200零字节报没有返回音频 200返回JSON不喂喇叭`() {
        val e1 = catchErr { client(FakeTransport()).streamPcm("测", 1f) { true } }
        assertTrue(e1.message!!.contains("没有返回音频"))
        val t2 = FakeTransport(
            chunks = listOf("{\"detail\":{\"message\":\"gateway error\"}}".toByteArray()),
            contentType = "application/json",
        )
        val e2 = catchErr { client(t2).streamPcm("测", 1f) { true } }
        assertTrue("要带服务端原因：${e2.message}", e2.message!!.contains("gateway error"))
    }

    @Test
    fun `音色列表解析 取id与labels 坏JSON返回空`() {
        val json = """
            {"voices":[
              {"voice_id":"abc123","name":"Roger - Laid-Back, Casual","category":"premade","labels":{"gender":"male","accent":"american"}},
              {"voice_id":"def456","name":"Rachel","labels":{"gender":"female"}},
              {"name":"没有id的坏项"}
            ]}
        """.trimIndent()
        val voices = ElevenTts.parseVoices(json)
        assertEquals(2, voices.size)
        assertEquals("abc123", voices[0].id)
        assertEquals("Roger", voices[0].shortName)
        assertEquals("Roger·男声", voices[0].label)
        assertEquals("def456", voices[1].id)
        assertEquals("Rachel·女声", voices[1].label)
        assertTrue("非 JSON 返回空", ElevenTts.parseVoices("not json").isEmpty())
        assertTrue("无 voices 键返回空", ElevenTts.parseVoices("{}").isEmpty())
    }

    @Test
    fun `获取音色 GET voices 用xi-api-key 401映射Key`() {
        val t = FakeTransport()
        t.getBody = """{"voices":[{"voice_id":"abc","name":"Roger","labels":{"gender":"male"}}]}"""
        val voices = client(t).fetchVoices()
        assertEquals(1, voices.size)
        assertEquals("abc", voices[0].id)
        assertTrue("GET /voices", t.urls.single().endsWith("/voices"))
        assertEquals("xi-api-key", t.authHeaders.single())
        val t401 = FakeTransport().apply { getCode = 401; getBody = "unauthorized" }
        val e = catchErr { client(t401).fetchVoices() }
        assertTrue("401 要提 Key：${e.message}", e.message!!.contains("API Key"))
        val tNoKey = FakeTransport()
        val eNoKey = catchErr { ElevenTts(tNoKey, CloudTtsConfig(TtsElevenDefaults.BASE_URL, " ", "v")).fetchVoices() }
        assertTrue(eNoKey.message!!.contains("API Key"))
        assertEquals(0, tNoKey.urls.size)
    }

    @Test
    fun `fromSettings 按档位组装 未选在线引擎返回null`() {
        val s = AppSettings("", "", "", TranscribeMode.OFFLINE, true, 1.0f)
        assertNull(CloudTtsConfig.fromSettings(s))
        // 默认官方档：地址/模型/音色全内置
        val cfg = CloudTtsConfig.fromSettings(s.copy(ttsEngine = TtsEngine.CLOUD))!!
        assertEquals(TtsCloudProvider.ELEVEN, cfg.provider)
        assertEquals(TtsElevenDefaults.BASE_URL, cfg.baseUrl)
        assertEquals(TtsElevenDefaults.MODEL, cfg.model)
        assertEquals(TtsElevenDefaults.VOICE, cfg.voice)
        // 自定义档：四件套原样，provider 标 custom
        val cfg2 = CloudTtsConfig.fromSettings(
            s.copy(
                ttsEngine = TtsEngine.CLOUD,
                ttsCloudProvider = TtsCloudProvider.CUSTOM,
                ttsCloudUrl = "https://x.cn/v1",
                ttsCloudKey = "kc",
                ttsCloudVoice = "vc",
                ttsCloudModel = "mc",
            ),
        )!!
        assertEquals(TtsCloudProvider.CUSTOM, cfg2.provider)
        assertEquals("https://x.cn/v1", cfg2.baseUrl)
        assertEquals("mc", cfg2.model)
    }
}
