package com.tingsiwei.app

import com.tingsiwei.app.llm.LlmError
import com.tingsiwei.app.llm.LlmSession
import com.tingsiwei.app.llm.RateGate
import com.tingsiwei.app.llm.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 流式（SSE）响应解析：改图请求已切到 stream=true，网关（如 Cloudflare）不再掐长请求。
 * delta.content 逐块拼回、思维链跳过、截断带部分内容、流被掐断按可重试网络错误处理。
 */
class LlmSessionStreamTest {

    private class StaticTransport(private val body: String, private val code: Int = 200) : Transport {
        var lastBody: String? = null
        override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
            lastBody = body
            return Transport.RawResp(code, this.body, emptyMap())
        }

        override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
            Transport.RawResp(code, body, emptyMap())
    }

    private fun session(transport: Transport): LlmSession = LlmSession(
        transport = transport,
        gate = RateGate(),
        policy = LlmSession.Policy(maxRetries = 0),
    )

    private fun chat(transport: Transport): String = runBlocking {
        session(transport).chat("https://x/v1", "k", "m", "sys", "u")
    }

    private fun chunk(content: String?, finish: String? = null) = buildString {
        append("data: {\"choices\":[{\"delta\":")
        append(if (content == null) "{}" else "{\"content\":\"$content\"}")
        if (finish != null) append(",\"finish_reason\":\"$finish\"")
        append("}]}\n\n")
    }

    @Test
    fun `a 流式内容逐块拼回 finish_reason 正常结束`() {
        val body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
            chunk("今天讲", ) + "\n" +
            chunk("正当防卫。") +
            chunk(null, "stop") +
            "data: [DONE]\n\n"
        assertEquals("今天讲正当防卫。", chat(StaticTransport(body)))
    }

    @Test
    fun `a2 请求体必须是 stream true`() {
        val t = StaticTransport(chunk("ok", "stop") + "data: [DONE]\n\n")
        chat(t)
        assertTrue("请求应带 stream:true", t.lastBody!!.contains("\"stream\":true"))
    }

    @Test
    fun `b 思维链 reasoning_content 跳过 不混进正文`() {
        val body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"让我想想……\"}}]}\n\n" +
            chunk("正文") + "data: [DONE]\n\n"
        assertEquals("正文", chat(StaticTransport(body)))
    }

    @Test
    fun `c finish_reason 为 length 时按截断处理并带回部分内容`() {
        val t = StaticTransport(chunk("半截的", "length"))
        try {
            chat(t)
            fail("应抛 Truncated")
        } catch (e: LlmError.Truncated) {
            assertEquals("半截的", e.partialContent)
        }
    }

    @Test
    fun `d 流被中途掐断 无 finish_reason 无 DONE 按可重试网络错误`() {
        val t = StaticTransport(chunk("说到一半就断了"))
        try {
            chat(t)
            fail("应抛 Network")
        } catch (e: LlmError.Network) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `e 只收到 DONE 没有内容 按空内容报错`() {
        val t = StaticTransport("data: [DONE]\n\n")
        try {
            chat(t)
            fail("应抛 EmptyContent")
        } catch (e: LlmError.EmptyContent) {
            assertEquals("接口没有返回内容，请在设置里换一个模型再试", e.message)
        }
    }

    @Test
    fun `f 渠道忽略 stream 参数回整段 JSON 也能解`() {
        val t = StaticTransport("""{"choices":[{"finish_reason":"stop","message":{"content":"整段回复"}}]}""")
        assertEquals("整段回复", chat(t))
    }

    @Test
    fun `g SSE 里的 keep-alive 注释行与空载荷不影响解析`() {
        val body = ": keep-alive\n\n" + chunk("你好") + "event: ping\n\n" + "data: [DONE]\n"
        assertEquals("你好", chat(StaticTransport(body)))
    }
}
