package com.tingsiwei.app

import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsAudio
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.CloudTtsTransport
import com.tingsiwei.app.tts.FakeCloudTtsStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 云端大模型朗读：请求组装、地址规范化、PCM 跨块解码、错误映射、WAV 回退、模型检索与筛选 */
class CloudTtsTest {

    /** 记下请求并按剧本回响应；[wavRetryBytes] 非空时第二次 post 起改回 200+WAV（格式回退剧本） */
    private class FakeTransport(
        private val code: Int = 200,
        private val chunks: List<ByteArray> = emptyList(),
        private val errorText: String = "",
        private val contentType: String = "audio/octet-stream",
        private val wavRetryBytes: ByteArray? = null,
    ) : CloudTtsTransport {
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val gets = mutableListOf<String>()
        var pcmAccepted = true
        var getCode = 200
        var getBody = ""

        override fun post(url: String, key: String, jsonBody: String): CloudTtsTransport.Resp {
            urls += url
            keys += key
            bodies += jsonBody
            if (wavRetryBytes != null && bodies.size >= 2) {
                return CloudTtsTransport.Resp(200, FakeCloudTtsStream(listOf(wavRetryBytes)), "", "audio/wav")
            }
            return if (code == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(chunks), "", contentType)
            } else {
                CloudTtsTransport.Resp(code, null, errorText, contentType)
            }
        }

        override fun get(url: String, key: String): CloudTtsTransport.Resp {
            gets += url
            keys += key
            return if (getCode == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(listOf(getBody.toByteArray())), "", "application/json")
            } else {
                CloudTtsTransport.Resp(getCode, null, getBody, "application/json")
            }
        }
    }

    private fun client(transport: FakeTransport) =
        CloudTts(transport, CloudTtsConfig("api.siliconflow.cn/v1", "test-key-fake", "FunAudioLLM/CosyVoice2-0.5B:anna"))

    @Test
    fun `地址规范化 补协议与路径`() {
        fun ep(url: String) = CloudTts(FakeTransport(), CloudTtsConfig(url, "k", "v")).endpoint()
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("api.siliconflow.cn/v1"))
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("https://api.siliconflow.cn"))
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("https://api.siliconflow.cn/v1/"))
        // 已带完整路径的不再叠加；不是 /v1 结尾就补 /v1（与 LLM 地址规范化同规则）
        assertEquals("https://x.cn/v1/audio/speech", ep("https://x.cn/v1/audio/speech"))
        assertEquals("https://x.cn/v2/v1/audio/speech", ep("https://x.cn/v2"))
        // 模型列表端点同一套规范化：用户填完整 speech 路径也能拉 /models
        assertEquals(
            "https://api.siliconflow.cn/v1/models",
            CloudTts(FakeTransport(), CloudTtsConfig("https://api.siliconflow.cn/v1/audio/speech", "k", "v")).modelsEndpoint(),
        )
    }

    @Test
    fun `请求体字段完整 语速夹到合法区间`() {
        val t = FakeTransport(chunks = listOf(byteArrayOf(1, 2)))
        client(t).streamPcm("你好。", speed = 9f) { true }
        val body = kotlinx.serialization.json.Json.parseToJsonElement(t.bodies[0]).jsonObject
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", body["model"]!!.jsonPrimitive.content)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", body["voice"]!!.jsonPrimitive.content)
        assertEquals("你好。", body["input"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals(24000, body["sample_rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("4.0", body["speed"]!!.jsonPrimitive.content.toDouble().toString()) // 9 被夹到上限
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", t.urls[0])
        assertEquals("test-key-fake", t.keys[0])
    }

    @Test
    fun `PCM 小端跨块解码 奇数字节进位`() {
        // 三字节一块 + 两字节一块：样本0=01,02 样本1=03,04 样本2=05,06 —— 块边界把样本1劈开也要拼对
        val t = FakeTransport(chunks = listOf(byteArrayOf(0x01, 0x02, 0x03), byteArrayOf(0x04, 0x05, 0x06)))
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm ->
            got += pcm
            true
        }
        val all = got.flatMap { it.toList() }
        // 0x0201 = 513，0x0403 = 1027，0x0605 = 1541（小端：低字节在前）
        assertEquals(listOf<Short>(513, 1027, 1541), all)
    }

    @Test
    fun `回调返回 false 立即停止不再回调`() {
        val t = FakeTransport(chunks = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6)))
        var calls = 0
        client(t).streamPcm("测", 1f) { _ ->
            calls++
            false
        }
        assertEquals(1, calls)
    }

    @Test
    fun `没填 Key 直接报中文错`() {
        val t = FakeTransport()
        val e = try {
            CloudTts(t, CloudTtsConfig("https://x.cn/v1", "  ", "v")).streamPcm("测", 1f) { true }
            null
        } catch (e: CloudTts.CloudTtsException) {
            e
        }!!
        assertTrue("要提到 API Key：${e.message}", e.message!!.contains("API Key"))
        assertEquals("没发请求", 0, t.urls.size)
    }

    @Test
    fun `401 与限流与一般错误映射成人话`() {
        val t401 = FakeTransport(code = 401, errorText = "Invalid token")
        val e401 = try {
            client(t401).streamPcm("测", 1f) { true }; null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("401 要提 Key：${e401.message}", e401.message!!.contains("API Key"))

        val t429 = FakeTransport(code = 429, errorText = "{\"message\":\"TPM limit\"}")
        val e429 = try {
            client(t429).streamPcm("测", 1f) { true }; null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("429 要提限流：${e429.message}", e429.message!!.contains("限流"))

        val t400 = FakeTransport(code = 400, errorText = "{\"code\":20012,\"message\":\"余额不足\",\"data\":\"\"}")
        val e400 = try {
            client(t400).streamPcm("测", 1f) { true }; null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("400 要带服务端消息：${e400.message}", e400.message!!.contains("余额不足"))
    }

    @Test
    fun `音色清单与标签互查`() {
        assertEquals(8, CloudTts.VOICES.size)
        assertEquals("女声·沉稳", CloudTts.voiceLabel(CloudTts.voiceId("anna")))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:claire", CloudTts.voiceId("claire"))
        // 不认识的存储值原样显示（比如以后换模型的自定义音色）
        assertEquals("speech:custom", CloudTts.voiceLabel("speech:custom"))
    }

    // ---------- 音色拼装：换渠道后模型对不上是「没声音」的头号原因 ----------

    @Test
    fun `音色拼装 短名补前缀 旧前缀替换 OpenAI式原样`() {
        // 短名 + 硅基式模型（含 /）→ 拼模型前缀
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", CloudTts.buildVoice("FunAudioLLM/CosyVoice2-0.5B", "anna"))
        // 已经是当前模型前缀 → 原样
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            CloudTts.buildVoice("FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:anna"),
        )
        // 挂着别的模型前缀（换渠道残留）→ 换成当前模型
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            CloudTts.buildVoice("FunAudioLLM/CosyVoice2-0.5B", "SomeOther/Model:anna"),
        )
        // OpenAI 式模型（不含 /）：音色原名直发，不拼前缀
        assertEquals("alloy", CloudTts.buildVoice("tts-1", "alloy"))
        assertEquals("alloy", CloudTts.buildVoice("gpt-4o-mini-tts", "alloy"))
        // 非模型路径的自定义前缀：尊重原值
        assertEquals("myvoice:01", CloudTts.buildVoice("tts-1", "myvoice:01"))
        // 空音色原样（发出去让服务端报错，错误信息比本地猜的准）
        assertEquals("", CloudTts.buildVoice("tts-1", " "))
    }

    @Test
    fun `按模型给预置音色 未知模型给空表`() {
        assertEquals(8, CloudTts.voicePresetsFor("FunAudioLLM/CosyVoice2-0.5B").size)
        assertEquals(6, CloudTts.voicePresetsFor("tts-1").size)
        assertEquals(6, CloudTts.voicePresetsFor("tts-1-hd").size)
        assertEquals(11, CloudTts.voicePresetsFor("gpt-4o-mini-tts").size)
        assertTrue("没预置的模型自由手填", CloudTts.voicePresetsFor("fish-speech-1.5").isEmpty())
    }

    // ---------- 格式回退：渠道不收流式 PCM 时改要 WAV ----------

    /** 组一段最小合法 WAV（16bit 单声道）字节 */
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
    fun `流式PCM被拒 自动改要WAV并且极简请求体`() {
        val wav = wavBytes(24000, shortArrayOf(100, -200, 300))
        val t = FakeTransport(code = 400, errorText = "{\"message\":\"stream unsupported\"}", wavRetryBytes = wav)
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm ->
            got += pcm
            true
        }
        assertEquals("第二次请求成功拿到音频", listOf<Short>(100, -200, 300), got.single().toList())
        assertEquals("共两次请求", 2, t.bodies.size)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(t.bodies[1]).jsonObject
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertTrue("WAV 兜底请求不带 stream 参数", body["stream"] == null)
        assertTrue("WAV 兜底请求不带 sample_rate 参数", body["sample_rate"] == null)
        assertTrue("speed 仍保留（OpenAI 官方也支持）", body["speed"] != null)
    }

    @Test
    fun `200但返回JSON 改要WAV重试`() {
        val wav = wavBytes(24000, shortArrayOf(7, 8, 9))
        val t = FakeTransport(
            code = 200,
            chunks = listOf("{\"error\":{\"message\":\"format not supported\"}}".toByteArray()),
            contentType = "application/json",
            wavRetryBytes = wav,
        )
        val got = mutableListOf<ShortArray>()
        client(t).streamPcm("测", 1f) { pcm ->
            got += pcm
            true
        }
        assertEquals(listOf<Short>(7, 8, 9), got.single().toList())
        assertEquals("JSON 响应触发了第二次 WAV 请求", 2, t.bodies.size)
    }

    @Test
    fun `两次都不是音频 报中文错不喂喇叭`() {
        val t = FakeTransport(
            code = 200,
            chunks = listOf("{\"message\":\"quota exceeded\"}".toByteArray()),
            contentType = "application/json",
            wavRetryBytes = "{\"message\":\"quota exceeded\"}".toByteArray(), // 第二次也回 JSON 错误体
        )
        val e = try {
            client(t).streamPcm("测", 1f) { true }; null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("要带服务端原因：${e.message}", e.message!!.contains("quota exceeded"))
    }

    @Test
    fun `WAV非24k 重采样到统一采样率`() {
        // 8k 的 1 秒 4 采样重采样到 24k 应放大 3 倍
        val wav = wavBytes(8000, shortArrayOf(0, 1000, 2000, 3000))
        val t = FakeTransport(code = 400, errorText = "no pcm", wavRetryBytes = wav)
        val got = mutableListOf<ShortArray>()
        CloudTts(t, CloudTtsConfig("x.cn/v1", "k", "alloy", "tts-1")).streamPcm("测", 1f) { pcm ->
            got += pcm
            true
        }
        val all = got.single()
        assertEquals("8k→24k 采样数放大 3 倍", 12, all.size)
        assertEquals("首尾采样保持", 0, all.first().toInt())
        assertEquals(3000, all.last().toInt())
    }

    @Test
    fun `401不重试 直接报错`() {
        val t = FakeTransport(code = 401, errorText = "bad key")
        try {
            client(t).streamPcm("测", 1f) { true }
        } catch (_: CloudTts.CloudTtsException) {
        }
        assertEquals("Key 错换格式也没用，只发一次", 1, t.bodies.size)
    }

    // ---------- WAV 解析与重采样 ----------

    @Test
    fun `WAV解析 立体声降混与非法结构报错`() {
        val mono = CloudTtsAudio.parseWav(wavBytes(24000, shortArrayOf(1, -1, 32767, -32768)))
        assertEquals(24000, mono.sampleRate)
        assertEquals(listOf<Short>(1, -1, 32767, -32768), mono.pcm.toList())

        // 不是 WAV（比如 JSON 错误体）→ 报错而不是解出垃圾
        val e = try {
            CloudTtsAudio.parseWav("{\"message\":\"x\"}".toByteArray()); null
        } catch (e: CloudTtsAudio.WavException) { e }!!
        assertTrue(e.message!!.contains("WAV"))

        // 空数据块
        assertTrue("空 WAV 解出 0 采样", CloudTtsAudio.parseWav(wavBytes(16000, shortArrayOf())).pcm.isEmpty())
    }

    @Test
    fun `重采样 同率原样 升降率长度正确`() {
        val src = shortArrayOf(0, 100, 200, 300)
        assertTrue("同率原样返回", CloudTtsAudio.resample(src, 24000, 24000).contentEquals(src))
        assertEquals("8k→24k 放大 3 倍", 12, CloudTtsAudio.resample(src, 8000, 24000).size)
        assertEquals("48k→24k 减半", 2, CloudTtsAudio.resample(src, 48000, 24000).size)
        assertTrue("空输入原样", CloudTtsAudio.resample(ShortArray(0), 8000, 24000).isEmpty())
    }

    @Test
    fun `200但零字节 报没有返回音频`() {
        val t = FakeTransport() // 200、无任何字节
        val e = try {
            client(t).streamPcm("测", 1f) { true }; null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("空响应要明说：${e.message}", e.message!!.contains("没有返回音频"))
    }

    // ---------- 模型检索与筛选 ----------

    @Test
    fun `检索模型 解析id列表`() {
        val t = FakeTransport()
        t.getBody = "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"FunAudioLLM/CosyVoice2-0.5B\"},{\"id\":\"gpt-4o\"}]}"
        val ids = CloudTts(t, CloudTtsConfig("api.x.cn/v1", "sk-1", "v")).fetchModels()
        assertEquals("https://api.x.cn/v1/models", t.gets.single())
        assertEquals("Bearer 鉴权", "sk-1", t.keys.single())
        assertEquals(listOf("gpt-4o", "FunAudioLLM/CosyVoice2-0.5B"), ids)
    }

    @Test
    fun `检索模型 404与401给中文指引`() {
        val t404 = FakeTransport().apply { getCode = 404; getBody = "not found" }
        val e404 = try {
            CloudTts(t404, CloudTtsConfig("api.x.cn/v1", "k", "v")).fetchModels(); null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("404 要指路手动输入：${e404.message}", e404.message!!.contains("手动输入"))

        val t401 = FakeTransport().apply { getCode = 401; getBody = "unauthorized" }
        val e401 = try {
            CloudTts(t401, CloudTtsConfig("api.x.cn/v1", "k", "v")).fetchModels(); null
        } catch (e: CloudTts.CloudTtsException) { e }!!
        assertTrue("401 要提 Key：${e401.message}", e401.message!!.contains("API Key"))
    }

    @Test
    fun `筛语音模型 排除转写图像等 留下TTS`() {
        val filtered = CloudTts.filterTtsModels(
            listOf(
                "gpt-4o",
                "whisper-large-v3",            // 语音转写，不是合成
                "text-embedding-3-small",      // 嵌入
                "dall-e-3",                    // 生图
                "FunAudioLLM/CosyVoice2-0.5B",
                "tts-1",
                "gpt-4o-mini-tts",
                "fishaudio/fish-speech-1.5",
                "qwen3-tts-flash",
                "deepseek-chat",
            ),
        )
        assertEquals(
            listOf("FunAudioLLM/CosyVoice2-0.5B", "tts-1", "gpt-4o-mini-tts", "fishaudio/fish-speech-1.5", "qwen3-tts-flash"),
            filtered,
        )
        assertTrue("无关列表筛完为空", CloudTts.filterTtsModels(listOf("gpt-4o", "deepseek-chat")).isEmpty())
    }
}
