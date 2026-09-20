package com.tingsiwei.app

import com.tingsiwei.app.llm.Complete
import com.tingsiwei.app.llm.LlmCallError
import com.tingsiwei.app.llm.LlmException
import com.tingsiwei.app.llm.LongTextPipeline
import com.tingsiwei.app.llm.SegmentStore
import com.tingsiwei.app.llm.TextChunker
import com.tingsiwei.app.llm.Tokens
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T3 长文流水线：429 注入下仍能出图、单段失败可跳过、断点续跑、指纹失效、归并层数受控。 */
class LongTextPipelineTest {

    private class FakeLLM(
        val failSegments: Set<Int> = emptySet(),
        val failEvery: Int = 0,
    ) : Complete {
        var calls = 0
        val inputs = mutableListOf<Int>()

        override suspend fun call(system: String, user: String, temperature: Double, desiredOutTokens: Int): String {
            calls++
            inputs.add(Tokens.estimate(system) + Tokens.estimate(user))
            val seq = Regex("第 (\\d+)/(\\d+) 小段").find(user)?.groupValues?.get(1)?.toInt()
            if (seq != null && seq - 1 in failSegments) throw LlmCallError.Server(500, "boom")
            if (failEvery > 0 && calls % failEvery == 2) throw LlmCallError.RateLimited(429, null, "rate limited")
            return when {
                seq != null -> "小节：第${seq}节\n" + "\t要点内容若干举例说明。\n".repeat(18)
                system.contains("更高层的大纲") -> "小节：归并组${calls}\n" + "\t归并要点内容若干。\n".repeat(18)
                else -> "<导图>\n总主题\n\t分支一\n\t\t叶子\n\t分支二\n</导图>\n<思路>\n因为所以的记忆叙述\n</思路>"
            }
        }
    }

    private fun tempStore(): Pair<SegmentStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "tsw-test-${System.nanoTime()}")
        return SegmentStore(dir) to dir
    }

    private fun lecture(chars: Int): String {
        val unit = "老师在这里讲解了一个知识点并举了例子，然后给出结论和注意事项。"
        val sb = StringBuilder()
        while (sb.length < chars) sb.append(unit).append('\n')
        return sb.substring(0, chars)
    }

    @Test
    fun `四万字长文在持续限流下仍能出图且每次输入都在窗口内`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM(failEvery = 4)
        val pipeline = LongTextPipeline(fake, store, contextWindow = 16384)
        val result = runBlocking { pipeline.run(1L, lecture(40000), "刑法总复习", true) }

        assertTrue("导图不能为空", TreeText.parse(result.mapText).isNotEmpty())
        assertTrue(result.thinking.contains("记忆叙述"))
        assertEquals(0, result.failedSegments.size)
        assertTrue("调用次数必须受控：${fake.calls}", fake.calls <= 60)
        assertTrue("归并层数受控：${result.reduceLevels}", result.reduceLevels <= LongTextPipeline.MaxReduceLevels)
        fake.inputs.forEachIndexed { i, tokens ->
            assertTrue("第 $i 次请求输入 $tokens 超出窗口", tokens <= 16384)
        }
        // 限流确实注入过：段调用数 > 段数（有重试）
        assertTrue("应触发过重试", fake.calls > pipeline.maxChunks)
        dir.deleteRecursively()
    }

    @Test
    fun `单段永久失败时跳过该段并如实报告`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM(failSegments = setOf(6))
        val pipeline = LongTextPipeline(fake, store, contextWindow = 16384)
        val result = runBlocking { pipeline.run(2L, lecture(40000), "标题", true) }

        assertEquals(listOf(6), result.failedSegments)
        assertTrue(TreeText.parse(result.mapText).isNotEmpty())
        assertTrue(result.notice()!!.contains("第 7 段"))
        dir.deleteRecursively()
    }

    @Test
    fun `续跑时只补失败段并复用已完成段`() {
        val (store, dir) = tempStore()
        val content = lecture(40000)
        val first = FakeLLM(failSegments = setOf(6))
        val p1 = LongTextPipeline(first, store, contextWindow = 16384)
        val r1 = runBlocking { p1.run(3L, content, "标题", true) }
        val total = r1.totalSegments

        val second = FakeLLM()
        val p2 = LongTextPipeline(second, store, contextWindow = 16384)
        val r2 = runBlocking { p2.run(3L, content, "标题", true) }

        assertEquals(1, r2.stats.segmentCalls)
        assertEquals(total - 1, r2.stats.reusedSegments)
        assertEquals(0, r2.failedSegments.size)
        assertNull("全部成功后不该再有提示", r2.notice())
        assertEquals("全部完成应清掉断点文件", 0, File(dir, "segments-3.json").exists().compareTo(false))
        dir.deleteRecursively()
    }

    @Test
    fun `内容被改写后旧断点整体失效`() {
        val (store, dir) = tempStore()
        val fake1 = FakeLLM(failSegments = setOf(0))
        val p1 = LongTextPipeline(fake1, store, contextWindow = 16384)
        runBlocking { p1.run(4L, lecture(40000), "标题", true) }

        val fake2 = FakeLLM()
        val p2 = LongTextPipeline(fake2, store, contextWindow = 16384)
        val r2 = runBlocking { p2.run(4L, lecture(40000) + "后来又补了一段。", "标题", true) }
        assertEquals("内容变了就必须全部重做", 0, r2.stats.reusedSegments)
        assertNotEquals(0, r2.stats.segmentCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `小窗口下会分层归并但层数不超过两层`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM()
        val pipeline = LongTextPipeline(fake, store, contextWindow = 4000)
        val result = runBlocking { pipeline.run(5L, lecture(20000), "标题", true) }
        assertTrue("应当发生归并", result.reduceLevels >= 1)
        assertTrue(result.reduceLevels <= LongTextPipeline.MaxReduceLevels)
        assertEquals(12, result.totalSegments)
        fake.inputs.forEach { assertTrue("小窗口下也必须留在预算内：$it", it <= 4000) }
        dir.deleteRecursively()
    }

    @Test
    fun `短内容走直接生成不分段`() {
        val (store, dir) = tempStore()
        val pipeline = LongTextPipeline(FakeLLM(), store, contextWindow = 16384)
        val system = com.tingsiwei.app.llm.Prompts.generateSystem(true)
        assertTrue(pipeline.useDirectRoute("很短的一段内容。", system, 2048))
        assertTrue(!pipeline.useDirectRoute(lecture(40000), system, 2048))
        dir.deleteRecursively()
    }

    @Test
    fun `每段都失败时抛出可读错误而不是静默空图`() {
        val (store, dir) = tempStore()
        val all = (0 until 12).toSet()
        val pipeline = LongTextPipeline(FakeLLM(failSegments = all), store, contextWindow = 16384)
        try {
            runBlocking { pipeline.run(6L, lecture(40000), "标题", true) }
            assertTrue("应抛异常", false)
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("每一段"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `SegmentStore 往返 损坏容错 以及笔记互不干扰`() {
        val (store, dir) = tempStore()
        val fp = SegmentStore.fingerprint("内容A", "p1")
        store.save(10L, fp, mapOf(0 to "大纲0", 2 to "大纲2"))
        assertEquals(mapOf(0 to "大纲0", 2 to "大纲2"), store.load(10L, fp))
        assertEquals("指纹不符必须全部作废", emptyMap<Int, String>(), store.load(10L, "other"))
        assertEquals("别的笔记读不到", emptyMap<Int, String>(), store.load(11L, fp))

        dir.mkdirs()
        File(dir, "segments-12.json").writeText("{ 这不是合法 json ")
        assertEquals("损坏文件不能抛异常", emptyMap<Int, String>(), store.load(12L, fp))
        File(dir, "segments-13.json").writeText(
            """{"fingerprint":"$fp","noteId":13,"segments":{"0":"", "x":"坏键", "1":"有效"}}"""
        )
        assertEquals(mapOf(1 to "有效"), store.load(13L, fp))
        store.clear(10L)
        assertEquals(emptyMap<Int, String>(), store.load(10L, fp))
        dir.deleteRecursively()
    }

    @Test
    fun `分段器与流水线的预算一致`() {
        val (store, dir) = tempStore()
        val pipeline = LongTextPipeline(FakeLLM(), store, contextWindow = 8192)
        val plan = TextChunker.plan(lecture(30000), pipeline.chunkTargetTokens, pipeline.maxChunks)
        assertTrue(plan.size <= pipeline.maxChunks)
        assertTrue(pipeline.chunkTargetTokens in 500..3000)
        dir.deleteRecursively()
    }
}
