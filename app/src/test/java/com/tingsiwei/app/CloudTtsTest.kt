package com.tingsiwei.app

import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.CloudTtsTransport
import com.tingsiwei.app.tts.FakeCloudTtsStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云端大模型朗读（默认硅基流动 CosyVoice2）：请求组装、地址规范化、PCM 跨块解码、错误映射 */
class CloudTtsTest {

    /** 记下请求并按剧本回响应 */
    private class FakeTransport(
        private val code: Int = 200,
        private val chunks: List<ByteArray> = emptyList(),
        private val errorText: String = "",
    ) : CloudTtsTransport {
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var pcmAccepted = true

        override fun post(url: String, apiKey: String, jsonBody: String): CloudTtsTransport.Resp {
            urls += url
            keys += apiKey
            bodies += jsonBody
            return if (code == 200) {
                CloudTtsTransport.Resp(200, FakeCloudTtsStream(chunks), "")
            } else {
                CloudTtsTransport.Resp(code, null, errorText)
            }
        }
    }

    private fun client(transport: FakeTransport) =
        CloudTts(transport, CloudTtsConfig("api.siliconflow.cn/v1", "sk-test", "FunAudioLLM/CosyVoice2-0.5B:anna"))

    @Test
    fun `地址规范化 补协议与路径`() {
        fun ep(url: String) = CloudTts(FakeTransport(), CloudTtsConfig(url, "k", "v")).endpoint()
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("api.siliconflow.cn/v1"))
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("https://api.siliconflow.cn"))
        assertEquals("https://api.siliconflow.cn/v1/audio/speech", ep("https://api.siliconflow.cn/v1/"))
        // 已带完整路径的不再叠加；不是 /v1 结尾就补 /v1（与 LLM 地址规范化同规则）
        assertEquals("https://x.cn/v1/audio/speech", ep("https://x.cn/v1/audio/speech"))
        assertEquals("https://x.cn/v2/v1/audio/speech", ep("https://x.cn/v2"))
    }

    @Test
    fun `请求体字段完整 语速夹到合法区间`() {
        val t = FakeTransport()
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
        assertEquals("sk-test", t.keys[0])
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
}
