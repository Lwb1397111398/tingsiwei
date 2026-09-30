package com.tingsiwei.app

import com.tingsiwei.app.llm.LlmError
import com.tingsiwei.app.llm.ReviseFlow
import com.tingsiwei.app.llm.ReviseResult
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改写决策层的失败链语义：diff 主问 → 提醒重问 → 全量降级；
 * diff 截断逐行抢救可用，全量截断绝不应用半截导图；未点名节点逐字不变。
 */
class ReviseFlowTest {

    private fun ctx() = ReviseFlow.Context(
        forest = listOf(
            TreeNote("A", mutableListOf(TreeNote("A1"), TreeNote("A2"))),
            TreeNote("B"),
        ),
        thinking = "旧思路",
        originalContent = "原文若干",
        suggestion = "把 A2 改成 A2改",
        fullSystem = "FULL-SYS",
    )

    /** 记录调用的假 Ask：按脚本回话，LlmError 实例按异常抛出 */
    private class FakeAsk(vararg replies: Any) : ReviseFlow.Ask {
        val diffCalls = ArrayList<Pair<String, String>>()
        val fullCalls = ArrayList<Pair<String, String>>()
        private val queue = ArrayDeque<Any>().apply { replies.forEach { add(it) } }

        override suspend fun diff(system: String, user: String): String {
            diffCalls += system to user
            return take()
        }

        override suspend fun full(system: String, user: String): String {
            fullCalls += system to user
            return take()
        }

        private fun take(): String = when (val r = if (queue.isEmpty()) "改 1 = 兜底" else queue.removeFirst()) {
            is LlmError -> throw r
            else -> r as String
        }
    }

    private fun ReviseResult.expectSuccess(): ReviseResult.Success {
        assertTrue("期望 Success，实际 $this", this is ReviseResult.Success)
        return this as ReviseResult.Success
    }

    @Test fun diffHappyPathKeepsUntouchedNodesByteIdentical() = runBlocking {
        val ask = FakeAsk("<修改>\n改 1.2 = A2改\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("A", "\tA1", "\tA2改", "B"), TreeText.serialize(out.forest).lines())
        assertNull(out.thinking) // 没给新思路 = 保留旧思路，由上层 orElse
        assertNull(out.notice)
        assertEquals(1, ask.diffCalls.size)
        assertTrue(ask.diffCalls.first().second.contains("1.2"))
    }

    @Test fun thinkingTagReplacesThinking() = runBlocking {
        val ask = FakeAsk("<修改>\n改 1.2 = A2改\n</修改>\n<思路>\n新思路一段\n</思路>")
        assertEquals("新思路一段", ReviseFlow.run(ctx(), ask).expectSuccess().thinking)
    }

    @Test fun invalidOutputRetriedWithReminderThenApplied() = runBlocking {
        val ask = FakeAsk("抱歉做不到", "<修改>\n加 2 = B1\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(2, ask.diffCalls.size)
        assertTrue(ask.diffCalls[1].second.contains("无法应用"))
        assertEquals(listOf("A", "\tA1", "\tA2", "B", "\tB1"), TreeText.serialize(out.forest).lines())
    }

    @Test fun truncatedSalvagesCompleteLines() = runBlocking {
        // partialContent 最后一行没写完 = 被砍断，必须丢掉；前面的完整指令照常应用
        val ask = FakeAsk(LlmError.Truncated("<修改>\n改 1.1 = A1改名\n删 1.2"))
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("A", "\tA1改名", "\tA2", "B"), TreeText.serialize(out.forest).lines())
        assertTrue(out.notice!!.contains("截断"))
        assertEquals(1, ask.diffCalls.size)
    }

    @Test fun salvagedWholeMapNeverAppliedDirectly() = runBlocking {
        // 半截 <导图> 抢救出来也不能覆盖完好旧图：转全量兜底重问
        val ask = FakeAsk(
            LlmError.Truncated("<导图>\n根\n\t半截"),
            "<导图>\n完整新图\n\t子\n</导图>",
        )
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("完整新图", "\t子"), TreeText.serialize(out.forest).lines())
        assertEquals(1, ask.fullCalls.size)
    }

    @Test fun bothDiffAttemptsUselessFallsBackToFullRewrite() = runBlocking {
        val ask = FakeAsk(
            "胡言乱语", "还是胡言乱语",
            "<导图>\n新根\n\t新子\n</导图>\n<思路>\n全新思路\n</思路>",
        )
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(1, ask.fullCalls.size)
        // 兜底用的就是调用方拼好的 fullSystem（Generator 负责把 majorKeepRule 接进去）
        assertEquals("FULL-SYS", ask.fullCalls.first().first)
        assertEquals(listOf("新根", "\t新子"), TreeText.serialize(out.forest).lines())
        assertEquals("全新思路", out.thinking)
    }

    @Test fun fullRewriteAlsoTruncatedFailsWithoutTouchingOldMap() = runBlocking {
        val ask = FakeAsk("无", "无", LlmError.Truncated("<导图>\n半截"))
        val r = ReviseFlow.run(ctx(), ask)
        assertTrue(r is ReviseResult.Fail)
        assertTrue((r as ReviseResult.Fail).reason.contains("拆小"))
    }

    @Test fun wholeMapDeleteRejectedFallsBackToFullRewrite() = runBlocking {
        val ask = FakeAsk(
            "<修改>\n删 1\n删 2\n</修改>",
            "<导图>\n根\n</导图>",
        )
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(1, ask.fullCalls.size) // 空图守卫拒应用，走了兜底
        assertEquals("根", out.forest.single().topic)
    }

    @Test fun modelDirectlyEmitsWholeMapTreatedAsReplacement() = runBlocking {
        val ask = FakeAsk("<导图>\n主题\n\t甲\n</导图>\n<思路>\n段\n</思路>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("主题", "\t甲"), TreeText.serialize(out.forest).lines())
        assertEquals(1, ask.diffCalls.size) // classify 出 Full 直接采纳，不再提醒重问
        assertEquals("段", out.thinking)
    }

    @Test fun ignoredPathsReportedInNotice() = runBlocking {
        val ask = FakeAsk("<修改>\n改 9.9 = 不存在\n改 1.2 = A2改\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertTrue(out.notice!!.contains("1 处"))
    }
}
