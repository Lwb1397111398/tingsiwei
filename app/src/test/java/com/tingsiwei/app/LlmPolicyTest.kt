package com.tingsiwei.app

import com.tingsiwei.app.llm.ClockSource
import com.tingsiwei.app.llm.LlmCallError
import com.tingsiwei.app.llm.LlmPolicy
import com.tingsiwei.app.llm.LlmSession
import com.tingsiwei.app.llm.Prompts
import com.tingsiwei.app.llm.RateGate
import com.tingsiwei.app.llm.Sleeper
import com.tingsiwei.app.llm.TextChunker
import com.tingsiwei.app.llm.Tokens
import com.tingsiwei.app.llm.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T1 弹性层：429 退避、Retry-After、截断降级、节流闸、预算降级。零真实 sleep、零 Android 依赖。 */
private fun chatBody(content: String, finish: String = "stop") =
    """{"choices":[{"finish_reason":"$finish","message":{"content":"$content"}}]}"""

private fun resp(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
    Transport.RawResp(code, body, headers)

class LlmPolicyTest {

    private class FakeTransport(private val queue: MutableList<Transport.RawResp>) : Transport {
        var calls = 0
        val bodies = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
            calls++
            bodies.add(body)
            return if (queue.isNotEmpty()) queue.removeAt(0) else Transport.RawResp(200, chatBody("兜底回复"), emptyMap())
        }
    }

    private class RecSleeper : Sleeper {
        val log = mutableListOf<Long>()
        override suspend fun sleep(ms: Long) {
            log.add(ms)
        }
    }

    private class FixedClock(private val t: Long = 0L) : ClockSource {
        override fun nowMs(): Long = t
    }

    private fun rateLimited() = resp(429, """{"error":{"message":"rate limit reached"}}""")

    private fun okBody() = resp(200, chatBody("正常回复"))

    private fun session(
        queue: MutableList<Transport.RawResp>,
        minIntervalMs: Long = 0L,
        contextWindow: Int = 16384,
        maxRetries: Int = LlmPolicy.MAX_RETRIES,
        conservative: Boolean = false,
        sleeper: RecSleeper = RecSleeper(),
        gate: RateGate = RateGate(FixedClock(), RecSleeper()),
    ): Triple<LlmSession, FakeTransport, RecSleeper> {
        val fake = FakeTransport(queue)
        val s = LlmSession(
            transport = fake,
            gate = gate,
            sleeper = sleeper,
            rng = { 0.5 },
            policy = LlmSession.Policy(
                contextWindow = contextWindow,
                minIntervalMs = minIntervalMs,
                maxRetries = maxRetries,
                conservative = conservative,
            ),
        )
        return Triple(s, fake, sleeper)
    }

    private fun maxOutOf(body: String) =
        Json.parseToJsonElement(body).jsonObject["max_tokens"]!!.jsonPrimitive.int

    private fun msgOf(body: String, idx: Int) = Json.parseToJsonElement(body)
        .jsonObject["messages"]!!.jsonArray[idx].jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun `a 连续 429 后成功 退避严格为 1s 与 2s`() {
        val sleeper = RecSleeper()
        val (s, fake, _) = session(
            mutableListOf(rateLimited(), rateLimited(), okBody()), sleeper = sleeper
        )
        val out = runBlocking { s.chat("https://x/v1", "k", "m", "sys", "用户内容") }
        assertEquals("正常回复", out)
        assertEquals(3, fake.calls)
        assertEquals(listOf(1000L, 2000L), sleeper.log)
    }

    @Test
    fun `b Retry-After 头优先于退避`() {
        val sleeper = RecSleeper()
        val (s, _, _) = session(
            mutableListOf(resp(429, "{}", mapOf("retry-after" to "7")), okBody()), sleeper = sleeper
        )
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(7000L, sleeper.log.first())
    }

    @Test
    fun `b2 响应体里的 retry_after 秒数也能识别`() {
        val sleeper = RecSleeper()
        val (s, _, _) = session(
            mutableListOf(resp(429, """{"error":{"message":"slow down","retry_after":3}}"""), okBody()),
            sleeper = sleeper,
        )
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(3000L, sleeper.log.first())
    }

    @Test
    fun `c finish_reason 为 length 时带体量规则只重问一次`() {
        val (s, fake, _) = session(mutableListOf(resp(200, chatBody("半截内容", "length")), okBody()))
        val out = runBlocking {
            s.chatWithDegrade("https://x/v1", "k", "m", "系统提示", "用户内容", degradeRule = Prompts.trimRule())
        }
        assertEquals("正常回复", out)
        assertEquals(2, fake.calls)
        val secondSystem = msgOf(fake.bodies[1], 0)
        assertTrue("第二次请求应带体量规则：$secondSystem", secondSystem.contains("体量要求"))
        assertTrue("原系统提示应保留", secondSystem.startsWith("系统提示"))
    }

    @Test
    fun `c2 连续截断则抛 Truncated`() {
        val (s, fake, _) = session(
            mutableListOf(resp(200, chatBody("半截", "length")), resp(200, chatBody("还是半截", "length")))
        )
        try {
            runBlocking { s.chatWithDegrade("https://x/v1", "k", "m", "sys", "u", degradeRule = "x") }
            fail("应抛 Truncated")
        } catch (e: LlmCallError.Truncated) {
            assertEquals(2, fake.calls)
        }
    }

    @Test
    fun `d 空内容与 401 不重试`() {
        val (s1, f1, sl1) = session(mutableListOf(resp(200, chatBody(""))))
        try {
            runBlocking { s1.chat("https://x/v1", "k", "m", "sys", "u") }
            fail("应抛 EmptyContent")
        } catch (e: LlmCallError.EmptyContent) {
            assertEquals(1, f1.calls)
            assertTrue(sl1.log.isEmpty())
        }
        val (s2, f2, sl2) = session(mutableListOf(resp(401, """{"error":{"message":"bad key"}}""")))
        try {
            runBlocking { s2.chat("https://x/v1", "k", "m", "sys", "u") }
            fail("应抛 Client")
        } catch (e: LlmCallError.Client) {
            assertEquals(401, e.httpCode)
            assertEquals(1, f2.calls)
            assertTrue(sl2.log.isEmpty())
        }
    }

    @Test
    fun `d2 用尽重试次数后抛出限流错误`() {
        val sleeper = RecSleeper()
        val (s, fake, _) = session(MutableList(50) { rateLimited() }, maxRetries = 3, sleeper = sleeper)
        try {
            runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
            fail("应抛 RateLimited")
        } catch (e: LlmCallError.RateLimited) {
            assertEquals(4, fake.calls)
            assertEquals(listOf(1000L, 2000L, 4000L), sleeper.log)
            assertTrue(e.message!!.contains("限流"))
        }
    }

    @Test
    fun `d3 网络抖动可重试`() {
        val fake = object : Transport {
            var n = 0
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
                n++
                if (n == 1) throw LlmCallError.Network("Connection reset")
                return Transport.RawResp(200, chatBody("正常回复"), emptyMap())
            }
        }
        val s = LlmSession(fake, RateGate(FixedClock(), RecSleeper()), RecSleeper(), { 0.5 }, LlmSession.Policy(minIntervalMs = 0))
        assertEquals("正常回复", runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") })
    }

    @Test
    fun `e token 估算规则`() {
        assertEquals(0, Tokens.estimate(""))
        assertEquals(4, Tokens.estimate("中文中文"))
        assertEquals(2, Tokens.estimate("abcdefgh"))
        assertEquals(5, Tokens.estimate("中文 abcdefgh"))
        assertEquals(1, Tokens.estimate("，"))
        assertEquals(1, Tokens.estimate("。"))
    }

    @Test
    fun `f 共享闸门时相邻请求间隔不小于 minIntervalMs`() {
        val sleeper = RecSleeper()
        val gate = RateGate(FixedClock(1000L), sleeper)
        val slots = mutableListOf<Long>()
        runBlocking {
            repeat(3) { gate.acquire(1200L) { slot -> slots.add(slot) } }
        }
        assertEquals(listOf(1000L, 2200L, 3400L), slots)
        // 时钟冻结：第三个请求的号比"现在"晚 2400，等待量随号位累计而不是每次固定间隔
        assertEquals(listOf(1200L, 2400L), sleeper.log)
    }

    @Test
    fun `f2 退避等待不占用闸口 重试只等一个间隔`() {
        val gate = RateGate(FixedClock(0L), RecSleeper())
        val (s, fake, _) = session(mutableListOf(rateLimited(), okBody()), minIntervalMs = 1000L, gate = gate)
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(2, fake.calls)
        assertEquals("第二次发送只应推迟一个间隔，退避不叠加占位", 1000L, gate.lastSlotMs)
    }

    @Test
    fun `g 超大输入降级后仍发出请求且落进窗口`() {
        val bigUser = "很长的一段中文内容用于测试预算。".repeat(900)
        assertTrue(Tokens.estimate(bigUser) > 4000)
        val (s, fake, _) = session(mutableListOf(okBody()), contextWindow = 4000)
        val out = runBlocking { s.chat("https://x/v1", "k", "m", "系统提示词", bigUser, desiredOutTokens = 2048) }
        assertEquals("正常回复", out)
        assertEquals(1, fake.calls)
        val maxOut = maxOutOf(fake.bodies[0])
        val sys = msgOf(fake.bodies[0], 0)
        val user = msgOf(fake.bodies[0], 1)
        assertTrue("max_tokens 有下限：$maxOut", maxOut >= LlmPolicy.MIN_OUTPUT_TOKENS)
        assertTrue(
            "降级后必须落进窗口 sys=${Tokens.estimate(sys)} user=${Tokens.estimate(user)} out=$maxOut",
            Tokens.estimate(sys) + Tokens.estimate(user) + maxOut <= 4000,
        )
        assertTrue("输入被截断且以句末收束", user.length < bigUser.length && user.endsWith("）"))
    }

    @Test
    fun `g2 纯英文超长输入也会被截进窗口`() {
        val latin = "hello ".repeat(20000)
        val (s, fake, _) = session(mutableListOf(okBody()), contextWindow = 4000)
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", latin, desiredOutTokens = 1024) }
        assertEquals(1, fake.calls)
        val maxOut = maxOutOf(fake.bodies[0])
        val user = msgOf(fake.bodies[0], 1)
        assertTrue(Tokens.estimate("sys") + Tokens.estimate(user) + maxOut <= 4000)
    }

    @Test
    fun `g3 revise 级别的大输入不被预算打断`() {
        val currentMap = ("根主题\n\t一级节点\n\t\t二级叶子内容\n").repeat(120)
        val user = Prompts.reviseUser(currentMap, "思路".repeat(600), "原始内容".repeat(3000), "把第二层展开")
        val (s, fake, _) = session(mutableListOf(okBody()), contextWindow = 8192)
        val out = runBlocking { s.chat("https://x/v1", "k", "m", Prompts.generateSystem(true), user) }
        assertEquals("正常回复", out)
        assertEquals(1, fake.calls)
        val maxOut = maxOutOf(fake.bodies[0])
        assertTrue(maxOut >= LlmPolicy.MIN_OUTPUT_TOKENS)
        assertTrue(
            Tokens.estimate(msgOf(fake.bodies[0], 0)) + Tokens.estimate(msgOf(fake.bodies[0], 1)) + maxOut <= 8192,
        )
    }

    @Test
    fun `h 退避封顶与抖动边界`() {
        assertEquals(60_000L, LlmPolicy.backoffMs(30) { 0.5 })
        assertEquals(800L, LlmPolicy.backoffMs(0) { 0.0 })
        assertEquals(1200L, LlmPolicy.backoffMs(0) { 1.0 })
        assertEquals(null, LlmPolicy.retryAfterMs(null, "no json here"))
        assertEquals(5000L, LlmPolicy.retryAfterMs("5", ""))
        assertEquals(60_000L, LlmPolicy.retryAfterMs("999999", ""))
    }

    @Test
    fun `i 保守模式间隔翻倍且窗口减半`() {
        val sleeper = RecSleeper()
        val gate = RateGate(FixedClock(0L), sleeper)
        val fake = FakeTransport(mutableListOf())
        val s = LlmSession(
            fake, gate, sleeper, { 0.5 },
            LlmSession.Policy(contextWindow = 8192, minIntervalMs = 1200L, conservative = true),
        )
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
        runBlocking { s.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals("第二次取号应等 2400ms 而非 1200ms", 2400L, gate.lastSlotMs)
        assertTrue("按 4096 窗口预算", maxOutOf(fake.bodies[0]) <= 4096)
    }

    @Test
    fun `z 分段与预算联动 每块都塞得进窗口`() {
        val text = "这是第一段的内容说明。然后是第二段的补充。".repeat(500)
        val plan = TextChunker.plan(text, targetTokens = 900, maxChunks = 12)
        assertTrue(plan.size > 1)
        plan.chunks.forEach { assertTrue("块 ${it.index} 超预算", Tokens.estimate(it.text) <= 1200) }
    }
}
