package com.tingsiwei.app

import com.tingsiwei.app.llm.ClockSource
import com.tingsiwei.app.llm.Complete
import com.tingsiwei.app.llm.LlmEndpoint
import com.tingsiwei.app.llm.LlmError
import com.tingsiwei.app.llm.LlmException
import com.tingsiwei.app.llm.LlmPolicy
import com.tingsiwei.app.llm.LlmSession
import com.tingsiwei.app.llm.LongTextPipeline
import com.tingsiwei.app.llm.OkHttpTransport
import com.tingsiwei.app.llm.Prompts
import com.tingsiwei.app.llm.RateGate
import com.tingsiwei.app.llm.SegmentStore
import com.tingsiwei.app.llm.Sleeper
import com.tingsiwei.app.llm.TextChunker
import com.tingsiwei.app.llm.Tokens
import com.tingsiwei.app.llm.Transport
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * T1 弹性层：429 退避、Retry-After、截断降级、节流闸、预算降级、错误文案不泄漏 KEY。
 * 时钟与等待都可注入并可精确断言，测试内零真实 sleep。
 */
class LlmPolicyTest {

    /** 时钟 + 等待二合一：每次 sleep 推进时间，于是"第几次请求发生在第几毫秒"可断言 */
    private class FakeTime(start: Long = 0L) : ClockSource, Sleeper {
        var now = start
        val waits = mutableListOf<Long>()
        override fun nowMs(): Long = now
        override suspend fun sleep(ms: Long) {
            waits.add(ms)
            now += ms
        }
    }

    private class FakeTransport(private val time: FakeTime, private val queue: MutableList<Transport.RawResp>) : Transport {
        var calls = 0
        val bodies = mutableListOf<String>()
        val times = mutableListOf<Long>()
        override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
            calls++
            bodies.add(body)
            times.add(time.nowMs())
            return if (queue.isNotEmpty()) queue.removeAt(0) else Transport.RawResp(200, chatBody("兜底回复"), emptyMap())
        }
        // 单测只覆盖 chat 路径；listModels 走 GET 时不经此桩
        override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
            throw AssertionError("单测不应触发 GET 请求")
    }

    private class H(val time: FakeTime, val transport: FakeTransport, val session: LlmSession)

    private fun h(
        vararg responses: Transport.RawResp,
        minIntervalMs: Long = 0L,
        contextWindow: Int = 16384,
        maxRetries: Int = LlmPolicy.MAX_RETRIES,
        conservative: Boolean = false,
    ): H {
        val time = FakeTime()
        val transport = FakeTransport(time, responses.toMutableList())
        val session = LlmSession(
            transport = transport,
            gate = RateGate(time, time),
            sleeper = time,
            rng = { 0.5 },
            policy = LlmSession.Policy(contextWindow, minIntervalMs, maxRetries, conservative),
        )
        return H(time, transport, session)
    }

    private fun resp(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        Transport.RawResp(code, body, headers)

    private fun limited(retryAfter: String? = null) = resp(
        429,
        """{"error":{"message":"rate limit reached"}}""",
        if (retryAfter == null) emptyMap() else mapOf("retry-after" to retryAfter),
    )

    private fun ok() = resp(200, chatBody("正常回复"))

    private fun maxOut(body: String) = Json.parseToJsonElement(body).jsonObject["max_tokens"]!!.jsonPrimitive.int

    private fun msg(body: String, idx: Int) = Json.parseToJsonElement(body)
        .jsonObject["messages"]!!.jsonArray[idx].jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun `a 连续 429 后成功 退避严格为 1s 与 2s`() {
        val x = h(limited(), limited(), ok())
        assertEquals("正常回复", runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "用户内容") })
        assertEquals(3, x.transport.calls)
        assertEquals(listOf(1000L, 2000L), x.time.waits)
    }

    @Test
    fun `b Retry-After 头优先于退避`() {
        val x = h(limited("7"), ok())
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(7000L, x.time.waits.first())
    }

    @Test
    fun `b2 响应体里的 retry_after 秒数也能识别`() {
        val x = h(resp(429, """{"error":{"message":"slow","retry_after":3}}"""), ok())
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(3000L, x.time.waits.first())
    }

    @Test
    fun `b3 Retry-After 为 0 时不能绕过退避变成狂打`() {
        val x = h(limited("0"), limited(), ok())
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(3, x.transport.calls)
        assertEquals("两次都必须走指数退避", listOf(1000L, 2000L), x.time.waits)
    }

    @Test
    fun `c finish_reason 为 length 时带体量规则只重问一次`() {
        val x = h(resp(200, chatBody("半截内容", "length")), ok())
        val out = runBlocking {
            x.session.chatWithDegrade("https://x/v1", "k", "m", "系统提示", "用户内容", degradeRule = Prompts.trimRule())
        }
        assertEquals("正常回复", out)
        assertEquals(2, x.transport.calls)
        val second = msg(x.transport.bodies[1], 0)
        assertTrue("第二次请求应带体量要求", second.contains("体量要求"))
        assertTrue("原系统提示应保留", second.startsWith("系统提示"))
    }

    @Test
    fun `c2 连续截断则抛 Truncated`() {
        val x = h(resp(200, chatBody("半截", "length")), resp(200, chatBody("还是半截", "length")))
        try {
            runBlocking { x.session.chatWithDegrade("https://x/v1", "k", "m", "sys", "u", degradeRule = "x") }
            fail("应抛 Truncated")
        } catch (e: LlmError.Truncated) {
            assertEquals(2, x.transport.calls)
        }
    }

    @Test
    fun `c3 截断重试会抬高输出上限`() {
        // 窗口给足，否则两发都被预算夹到同一值、看不出抬升
        val x = h(resp(200, chatBody("半截内容", "length")), ok(), contextWindow = 65_536)
        runBlocking { x.session.chatWithDegrade("https://x/v1", "k", "m", "系统提示", "用户内容", degradeRule = "体量") }
        assertEquals(2, x.transport.calls)
        val first = maxOut(x.transport.bodies[0])
        val second = maxOut(x.transport.bodies[1])
        assertTrue("第二次 max_tokens 要抬高：$first -> $second", second > first)
    }

    @Test
    fun `c4 两次都截断时异常带回较长的部分内容`() {
        // 第二次更长：带回第二次的
        val x = h(resp(200, chatBody("短的部分", "length")), resp(200, chatBody("明显更长一点的部分内容", "length")))
        try {
            runBlocking { x.session.chatWithDegrade("https://x/v1", "k", "m", "sys", "u", degradeRule = "x") }
            fail("应抛 Truncated")
        } catch (e: LlmError.Truncated) {
            assertEquals("明显更长一点的部分内容", e.partialContent)
        }
        // 第二次反而更短：带回第一次的
        val y = h(resp(200, chatBody("第一次就挺长的部分内容", "length")), resp(200, chatBody("短", "length")))
        try {
            runBlocking { y.session.chatWithDegrade("https://x/v1", "k", "m", "sys", "u", degradeRule = "x") }
            fail("应抛 Truncated")
        } catch (e: LlmError.Truncated) {
            assertEquals("第一次就挺长的部分内容", e.partialContent)
        }
    }

    @Test
    fun `c5 渠道硬帽回 400 自动退回 8192 档再问一次`() {
        val x = h(resp(400, """{"error":{"message":"max_tokens exceeds model limit"}}"""), ok())
        val out = runBlocking {
            x.session.chatWithDegrade("https://x/v1", "k", "m", "系统提示", "用户内容", degradeRule = "体量要求")
        }
        assertEquals("正常回复", out)
        assertEquals(2, x.transport.calls)
        assertTrue("默认请求就该带 16384 档", maxOut(x.transport.bodies[0]) > LlmPolicy.REJECT_FALLBACK_OUTPUT_TOKENS)
        assertEquals(
            "400 后第二发应退回保守档", LlmPolicy.REJECT_FALLBACK_OUTPUT_TOKENS,
            maxOut(x.transport.bodies[1]),
        )
        assertTrue("退回档带体量规则", msg(x.transport.bodies[1], 0).contains("体量要求"))
    }

    @Test
    fun `c6 401 与输出帽无关仍然快速失败`() {
        val x = h(resp(401, """{"error":{"message":"invalid api key"}}"""))
        try {
            runBlocking { x.session.chatWithDegrade("https://x/v1", "bad", "m", "sys", "u") }
            fail("应抛 Reject")
        } catch (e: LlmError.Reject) {
            assertEquals("认证失败不该借道退档重试", 1, x.transport.calls)
        }
    }

    @Test
    fun `d 空内容与 401 不重试且不泄漏 KEY`() {
        val empty = h(resp(200, chatBody("")))
        try {
            runBlocking { empty.session.chat("https://x/v1", "k", "m", "sys", "u") }
            fail("应抛 EmptyContent")
        } catch (e: LlmError.EmptyContent) {
            assertEquals(1, empty.transport.calls)
            assertTrue(empty.time.waits.isEmpty())
        }
        val denied = h(resp(401, """{"error":{"message":"bad key"}}"""))
        try {
            runBlocking { denied.session.chat("https://x/v1", "SECRET-KEY-VALUE", "m", "sys", "u") }
            fail("应抛 Reject")
        } catch (e: LlmError.Reject) {
            assertEquals(401, e.httpCode)
            assertEquals(1, denied.transport.calls)
            assertTrue("不该有等待", denied.time.waits.isEmpty())
            assertTrue("提示要能指导用户：${e.message}", e.message!!.contains("API KEY"))
            assertFalse("错误信息不能泄漏 KEY", e.message!!.contains("SECRET-KEY-VALUE"))
        }
        assertFalse("请求体里不应出现 KEY", denied.transport.bodies.any { it.contains("SECRET-KEY-VALUE") })
    }

    @Test
    fun `d2 用尽重试次数后抛出限流错误`() {
        val x = h(*Array(50) { limited() }, maxRetries = 3)
        try {
            runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
            fail("应抛 RateLimited")
        } catch (e: LlmError.RateLimited) {
            assertEquals(4, x.transport.calls)
            assertEquals(listOf(1000L, 2000L, 4000L), x.time.waits)
            assertTrue(e.message!!.contains("限流"))
        }
    }

    @Test
    fun `d3 网络抖动可重试`() {
        val time = FakeTime()
        val flaky = object : Transport {
            var n = 0
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
                n++
                if (n == 1) throw LlmError.Network()
                return Transport.RawResp(200, chatBody("正常回复"), emptyMap())
            }
            override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
                throw AssertionError("单测不应触发 GET 请求")
        }
        val session = LlmSession(flaky, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(minIntervalMs = 0))
        assertEquals("正常回复", runBlocking { session.chat("https://x/v1", "k", "m", "sys", "u") })
    }

    @Test
    fun `d4 超时类状态码可重试 其余 4xx 不重试`() {
        for (code in listOf(408, 425, 500, 503)) {
            val x = h(resp(code, "x"), ok())
            runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
            assertEquals("$code 应重试一次", 2, x.transport.calls)
        }
        for (code in listOf(400, 404, 451)) {
            val x = h(resp(code, "x"), ok())
            try {
                runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
                fail("$code 不该被当成可重试")
            } catch (e: LlmError.Reject) {
                assertEquals(1, x.transport.calls)
            }
        }
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
    fun `e2 截断保证结果不超上限且收在边界`() {
        val text = "句子结束了。".repeat(2000)
        val (cut, trimmed) = Tokens.truncateToTokens(text, 300)
        assertTrue(trimmed)
        assertTrue("截断后仍超：${Tokens.estimate(cut)}", Tokens.estimate(cut) <= 300)
        val (same, notTrimmed) = Tokens.truncateToTokens("短内容。", 300)
        assertEquals("短内容。", same)
        assertFalse(notTrimmed)
    }

    @Test
    fun `f 共享闸门时相邻请求间隔不小于 minIntervalMs`() {
        val time = FakeTime(1000L)
        val gate = RateGate(time, time)
        val slots = mutableListOf<Long>()
        runBlocking { repeat(3) { gate.acquire(1200L) { slot -> slots.add(slot) } } }
        assertEquals(listOf(1000L, 2200L, 3400L), slots)
        assertEquals(listOf(1200L, 1200L), time.waits)
    }

    @Test
    fun `f2 连续取号互不重复且严格递增一个间隔`() {
        val time = FakeTime()
        val gate = RateGate(time, time)
        val slots = mutableListOf<Long>()
        runBlocking { repeat(8) { gate.acquire(500L) { s -> slots.add(s) } } }
        assertEquals(8, slots.toSet().size)
        assertEquals((0 until 8).map { it * 500L }, slots)
    }

    @Test
    fun `f3 退避等待不占用闸口 重试只等一个间隔`() {
        val x = h(limited(), ok(), minIntervalMs = 1000L)
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals(2, x.transport.calls)
        assertEquals(
            "第 2 次发送应在退避结束时（1000）；若退避还占了闸口会变成 2000",
            listOf(0L, 1000L),
            x.transport.times,
        )
    }

    @Test
    fun `g 超大输入降级后仍发出请求且落进窗口`() {
        val bigUser = "很长的一段中文内容用于测试预算。".repeat(900)
        assertTrue(Tokens.estimate(bigUser) > 4000)
        val x = h(ok(), contextWindow = 4000)
        assertEquals(
            "正常回复",
            runBlocking { x.session.chat("https://x/v1", "k", "m", "系统提示词", bigUser, desiredOutTokens = 2048) },
        )
        assertEquals(1, x.transport.calls)
        val out = maxOut(x.transport.bodies[0])
        val sys = msg(x.transport.bodies[0], 0)
        val user = msg(x.transport.bodies[0], 1)
        assertTrue("max_tokens 有下限：$out", out >= LlmPolicy.MIN_OUTPUT_TOKENS)
        assertTrue(
            "降级后必须落进窗口 sys=${Tokens.estimate(sys)} user=${Tokens.estimate(user)} out=$out",
            Tokens.estimate(sys) + Tokens.estimate(user) + out <= 4000,
        )
        assertTrue("输入被截断", user.length < bigUser.length && user.endsWith("）"))
    }

    @Test
    fun `g2 纯英文超长输入也会被截进窗口`() {
        val latin = "hello ".repeat(20000)
        val x = h(ok(), contextWindow = 4000)
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", latin, desiredOutTokens = 1024) }
        assertEquals(1, x.transport.calls)
        val out = maxOut(x.transport.bodies[0])
        assertTrue(Tokens.estimate("sys") + Tokens.estimate(msg(x.transport.bodies[0], 1)) + out <= 4000)
    }

    @Test
    fun `g3 revise 级别的大输入不被预算打断`() {
        val currentMap = ("根主题\n\t一级节点\n\t\t二级叶子内容\n").repeat(120)
        val user = Prompts.reviseUser(currentMap, "思路".repeat(600), "原始内容".repeat(3000), "把第二层展开")
        val x = h(ok(), contextWindow = 8192)
        assertEquals("正常回复", runBlocking { x.session.chat("https://x/v1", "k", "m", Prompts.generateSystem(true), user) })
        assertEquals(1, x.transport.calls)
        val out = maxOut(x.transport.bodies[0])
        assertTrue(out >= LlmPolicy.MIN_OUTPUT_TOKENS)
        assertTrue(Tokens.estimate(msg(x.transport.bodies[0], 0)) + Tokens.estimate(msg(x.transport.bodies[0], 1)) + out <= 8192)
    }

    @Test
    fun `g4 system 本身超长时缩 system 并把余量还给输出`() {
        val hugeSystem = "系统说明。".repeat(3000)
        val x = h(ok(), contextWindow = 4000)
        runBlocking { x.session.chat("https://x/v1", "k", "m", hugeSystem, "小请求内容。", desiredOutTokens = 2048) }
        val out = maxOut(x.transport.bodies[0])
        val sys = msg(x.transport.bodies[0], 0)
        assertTrue("system 应被压缩：${Tokens.estimate(sys)}", Tokens.estimate(sys) < Tokens.estimate(hugeSystem))
        assertTrue(Tokens.estimate(sys) + Tokens.estimate(msg(x.transport.bodies[0], 1)) + out <= 4000)
    }

    @Test
    fun `h 退避封顶与 Retry-After 解析边界`() {
        assertEquals(60_000L, LlmPolicy.backoffMs(30) { 0.5 })
        assertEquals(800L, LlmPolicy.backoffMs(0) { 0.0 })
        assertEquals(1200L, LlmPolicy.backoffMs(0) { 1.0 })
        assertEquals(null, LlmPolicy.retryAfterMs(null, "no json here"))
        assertEquals(null, LlmPolicy.retryAfterMs("0", ""))
        assertEquals(null, LlmPolicy.retryAfterMs("abc", ""))
        assertEquals(5000L, LlmPolicy.retryAfterMs("5", ""))
        assertEquals(60_000L, LlmPolicy.retryAfterMs("999999", ""))
    }

    @Test
    fun `i 保守模式间隔翻倍且窗口减半`() {
        val x = h(minIntervalMs = 1200L, contextWindow = 8192, conservative = true)
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertEquals("第二次取号应等 2400ms 而非 1200ms", listOf(0L, 2400L), x.transport.times)
        assertTrue("按 4096 窗口预算", maxOut(x.transport.bodies[0]) <= 4096)
    }

    @Test
    fun `k 畸形响应形状都收敛成分级错误`() {
        val shapes = listOf(
            """{"choices":[{"message":"oops"}]}""",
            """{"choices":[{"message":{"content":123}}]}""",
            """{"choices":[]}""",
            """{"detail":"quota exceeded"}""",
            """not json at all""",
        )
        for (body in shapes) {
            val x = h(resp(200, body))
            try {
                runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
                fail("不应成功：$body")
            } catch (e: LlmError) {
                assertTrue("错误应不可重试，实为 ${e.javaClass.simpleName}", !e.retryable)
                assertTrue("文案必须是中文：${e.message}", e.message!!.first().isLetter() && !e.message!!.contains("Exception"))
                assertEquals(1, x.transport.calls)
            }
        }
    }

    @Test
    fun `z 分段与预算联动 每块都塞得进窗口`() {
        val text = "这是第一段的内容说明。然后是第二段的补充。".repeat(500)
        val plan = TextChunker.plan(text, targetTokens = 900, maxChunks = 12, maxTargetTokens = 1100)
        assertTrue(plan.size > 1)
        assertTrue("放大后不得超过单块上限", plan.targetTokens <= 1100)
        plan.chunks.forEach { assertTrue("块 ${it.index} 超预算", Tokens.estimate(it.text) <= 1100) }
    }

    @Test
    fun `zz 接上真实会话层 429 注入下整条链路仍出图`() {
        val content = (1..1200).joinToString("") { "第${it}小节讲了罪刑法定原则的含义和例子。" }
        val dir = File(System.getProperty("java.io.tmpdir"), "tsw-e2e-${System.nanoTime()}")
        val time = FakeTime()
        var n = 0
        val transport = object : Transport {
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
                n++
                if (n % 3 == 0) return resp(429, """{"error":{"message":"rate limit"}}""")
                val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1]
                    .jsonObject["content"]!!.jsonPrimitive.content
                val out = when {
                    user.contains("小段") -> "小节：某节\n\t要点：罪刑法定\n\t\t法无明文规定不为罪"
                    user.contains("份小节大纲") -> "小节：归并\n\t要点\n\t\t细节"
                    else -> "<导图>\n罪刑法定原则\n\t含义\n\t\t法无明文规定不为罪\n\t要求\n</导图>\n<思路>\n因为法律没有明文规定就不为罪，所以要记牢。\n</思路>"
                }
                return resp(200, chatBody(out.replace("\n", "\\n")))
            }
            override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
                throw AssertionError("本测试不应触发 GET 请求")
        }
        val session = LlmSession(transport, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(16384, 0L))
        val pipeline = LongTextPipeline(
            complete = Complete { system, user, out ->
                session.chat("https://x/v1", "k", "m", system, user, out)
            },
            store = SegmentStore(dir),
            contextWindow = 16384,
        )
        val result = runBlocking { pipeline.run(99L, content, "刑法总复习", true) }
        assertTrue("必须出图", TreeText.parse(result.mapText).isNotEmpty())
        assertTrue("限流时确实自动等待过：${time.waits}", time.waits.isNotEmpty())
        assertTrue(
            "每次请求输入都要在窗口内，实为 ${result.stats.maxInputTokens}",
            result.stats.maxInputTokens <= 16384,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `zz2 生产路径上 KEY 不对也必须两段内停手`() {
        // 走真实的 LlmSession（不是假 Complete），验证 Reject 能穿过客户端边界被流水线识别
        val time = FakeTime()
        var posts = 0
        val unauthorized = object : Transport {
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
                posts++
                return resp(401, """{"error":{"message":"invalid api key"}}""")
            }
            override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
                throw AssertionError("本测试不应触发 GET 请求")
        }
        val session = LlmSession(unauthorized, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(16384, 0L))
        val dir = File(System.getProperty("java.io.tmpdir"), "tsw-401-${System.nanoTime()}")
        val pipeline = LongTextPipeline(
            complete = Complete { system, user, out ->
                session.chat("https://x/v1", "bad-key", "m", system, user, out)
            },
            store = SegmentStore(dir),
            contextWindow = 16384,
        )
        try {
            runBlocking { pipeline.run(77L, lecture(40000), "标题", true) }
            fail("应中止")
        } catch (e: LlmException) {
            assertTrue("要带上接口给的提示：${e.message}", e.message!!.contains("API KEY"))
            assertEquals("连着两段被拒就该停手，不能把 12 段全打一遍", 2, posts)
            assertTrue("不该有任何退避等待", time.waits.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `m 接口地址规范化`() {
        assertEquals("https://a.com/v1", LlmEndpoint.normalize("a.com"))
        assertEquals("https://a.com/v1", LlmEndpoint.normalize("https://a.com/"))
        assertEquals("https://a.com/v1", LlmEndpoint.normalize("https://a.com/v1"))
        assertEquals("https://a.com/x/v1", LlmEndpoint.normalize("https://a.com/x/v1"))
        assertEquals("http://127.0.0.1:8080/v1", LlmEndpoint.normalize("http://127.0.0.1:8080"))
        assertEquals(
            "https://a.com/v1",
            LlmEndpoint.normalize("https://a.com/v1/chat/completions"),
        )
        assertEquals("", LlmEndpoint.normalize("   "))
    }

    @Test
    fun `m2 地址不成形时不重试直接给中文提示`() {
        val time = FakeTime()
        // 用真实传输层：OkHttp 在解析非法地址时就抛，不需要联网
        val session = LlmSession(OkHttpTransport, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(16384, 0L))
        try {
            runBlocking { session.chat("not a url", "k", "m", "sys", "u") }
            fail("应抛 BadUrl")
        } catch (e: LlmError.BadUrl) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("接口地址"))
            assertTrue("非法地址不该退避重试", time.waits.isEmpty())
        }
    }

    @Test
    fun `m4 模型列表必须用GET并解析模型id`() {
        val time = FakeTime()
        var method = ""
        val t = object : Transport {
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp {
                method = "POST"
                return Transport.RawResp(404, """{"error":{"code":5,"message":"NOT_FOUND"}}""", emptyMap())
            }
            override fun get(url: String, headers: Map<String, String>): Transport.RawResp {
                method = "GET"
                assertEquals("https://token.sensenova.cn/v1/models", url)
                return Transport.RawResp(
                    200,
                    """{"data":[{"id":"glm-5.2"},{"id":"deepseek-v4-flash"},{"name":"kimi-k3"}]}""",
                    emptyMap(),
                )
            }
        }
        val session = LlmSession(t, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(16384, 0L))
        val models = runBlocking { session.listModels("https://token.sensenova.cn/v1", "k") }
        assertEquals("模型列表是只读端点，必须 GET（POST 会被不少渠道回 404）", "GET", method)
        assertEquals(listOf("glm-5.2", "deepseek-v4-flash", "kimi-k3"), models)
    }

    @Test
    fun `m5 渠道不给模型列表时 404 要翻译成人话而不是让用户怀疑地址`() {
        val time = FakeTime()
        val notFound = object : Transport {
            override fun post(url: String, headers: Map<String, String>, body: String): Transport.RawResp =
                Transport.RawResp(404, "x", emptyMap())
            override fun get(url: String, headers: Map<String, String>): Transport.RawResp =
                Transport.RawResp(404, """{"error":{"code":5,"message":"NOT_FOUND"}}""", emptyMap())
        }
        val session = LlmSession(notFound, RateGate(time, time), time, { 0.5 }, LlmSession.Policy(16384, 0L))
        try {
            runBlocking { session.listModels("https://x/v1", "k") }
            fail("应抛出提示")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("手动输入模型名"))
            assertFalse(e.message!!.contains("接口地址不对"))
        }
    }

    @Test
    fun `m6 请求体不得携带temperature 严格渠道只收默认采样参数`() {
        // 商汤 kimi-k3 等渠道对非 1 的 temperature 直接回 400，宁可少发也不碰各家参数红线
        val x = h(ok())
        runBlocking { x.session.chat("https://x/v1", "k", "m", "sys", "u") }
        assertFalse(
            "temperature 字段会让 kimi-k3 等严格渠道回 400：${x.transport.bodies[0]}",
            x.transport.bodies[0].contains("temperature"),
        )
    }

    private fun lecture(chars: Int): String {
        val unit = "老师在这里讲解了一个知识点并举了例子，然后给出结论和注意事项。"
        val sb = StringBuilder()
        while (sb.length < chars) sb.append(unit).append('\n')
        return sb.substring(0, chars)
    }
}

private fun chatBody(content: String, finish: String = "stop") =
    """{"choices":[{"finish_reason":"$finish","message":{"content":"$content"}}]}"""
