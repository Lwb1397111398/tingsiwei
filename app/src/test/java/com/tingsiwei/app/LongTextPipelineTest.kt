package com.tingsiwei.app

import com.tingsiwei.app.llm.Complete
import com.tingsiwei.app.llm.LlmError
import com.tingsiwei.app.llm.LlmException
import com.tingsiwei.app.llm.LongTextPipeline
import com.tingsiwei.app.llm.Prompts
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
        val failMerge: Boolean = false,
        val rejectOnce: Boolean = false,
    ) : Complete {
        var calls = 0
        val inputs = mutableListOf<Int>()
        var segmentCalls = 0
        var mergeCalls = 0

        override suspend fun call(system: String, user: String, temperature: Double, desiredOutTokens: Int): String {
            calls++
            inputs.add(Tokens.estimate(system) + Tokens.estimate(user))
            val seq = Regex("第 (\\d+)/(\\d+) 小段").find(user)?.groupValues?.get(1)?.toInt()
            if (rejectOnce && seq != null) throw LlmError.Reject(401)
            if (seq != null && seq - 1 in failSegments) throw LlmError.Server(500)
            if (failEvery > 0 && calls % failEvery == 2) throw LlmError.RateLimited(429, null)
            return when {
                seq != null -> {
                    segmentCalls++
                    "小节：第${seq}节\n" + "\t要点内容若干举例说明。\n".repeat(18)
                }
                system.contains("更高层的大纲") -> {
                    mergeCalls++
                    if (failMerge) "" else "小节：归并组${calls}\n" + "\t归并要点内容若干。\n".repeat(18)
                }
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
        assertTrue("限流确实注入过（有重试）", fake.calls > pipeline.maxChunks)
        dir.deleteRecursively()
    }

    @Test
    fun `单段永久失败时跳过该段并带上失败原因`() {
        val (store, dir) = tempStore()
        val pipeline = LongTextPipeline(FakeLLM(failSegments = setOf(6)), store, contextWindow = 16384)
        val result = runBlocking { pipeline.run(2L, lecture(40000), "标题", true) }

        assertEquals(listOf(6), result.failedSegments)
        assertTrue(TreeText.parse(result.mapText).isNotEmpty())
        val notice = result.notice()!!
        assertTrue("要指出第 7 段：$notice", notice.contains("第 7 段"))
        assertTrue("要带上失败原因：$notice", notice.contains("服务端暂时不可用"))
        assertTrue("要告诉用户能补全：$notice", notice.contains("补全"))
        dir.deleteRecursively()
    }

    @Test
    fun `续跑时只补失败段并复用已完成段`() {
        val (store, dir) = tempStore()
        val content = lecture(40000)
        val p1 = LongTextPipeline(FakeLLM(failSegments = setOf(6)), store, contextWindow = 16384)
        val total = runBlocking { p1.run(3L, content, "标题", true) }.totalSegments

        val second = FakeLLM()
        val p2 = LongTextPipeline(second, store, contextWindow = 16384)
        val r2 = runBlocking { p2.run(3L, content, "标题", true) }

        assertEquals("只应重发失败的那一段", 1, r2.stats.segmentCalls)
        assertEquals(total - 1, r2.stats.reusedSegments)
        assertEquals(0, r2.failedSegments.size)
        assertNull("全部成功后不该再有提示", r2.notice())
        assertTrue("全部完成应清掉断点文件", !File(dir, "segments-3.json").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `内容被改写后旧断点整体失效`() {
        val (store, dir) = tempStore()
        runBlocking {
            LongTextPipeline(FakeLLM(failSegments = setOf(0)), store, contextWindow = 16384)
                .run(4L, lecture(40000), "标题", true)
        }
        val p2 = LongTextPipeline(FakeLLM(), store, contextWindow = 16384)
        val r2 = runBlocking { p2.run(4L, lecture(40000) + "后来又补了一段。", "标题", true) }
        assertEquals("内容变了就必须全部重做", 0, r2.stats.reusedSegments)
        assertNotEquals(0, r2.stats.segmentCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `KEY 不对时立刻中止不去把剩下的段全打一遍`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM(rejectOnce = true)
        val pipeline = LongTextPipeline(fake, store, contextWindow = 16384)
        try {
            runBlocking { pipeline.run(7L, lecture(40000), "标题", true) }
            assertTrue("应抛异常", false)
        } catch (e: LlmException) {
            assertTrue("要带上接口给的提示：${e.message}", e.message!!.contains("API KEY"))
            assertEquals("第一段失败就该中止，不能继续打剩下的段", 1, fake.calls)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `小窗口下会分层归并但层数不超过两层`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM()
        val pipeline = LongTextPipeline(fake, store, contextWindow = 4000)
        val result = runBlocking { pipeline.run(5L, lecture(20000), "标题", true) }
        assertTrue("应当发生归并", result.reduceLevels in 1..LongTextPipeline.MaxReduceLevels)
        fake.inputs.forEach { assertTrue("小窗口下也必须留在预算内：$it", it <= 4000) }
        assertTrue("归并调用发生过", fake.mergeCalls > 0)
        dir.deleteRecursively()
    }

    @Test
    fun `窗口太小装不下时宁可多分几段也不撑破预算`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM()
        val pipeline = LongTextPipeline(fake, store, contextWindow = 4096, conservative = true)
        val result = runBlocking { pipeline.run(8L, lecture(15000), "标题", true) }
        assertTrue("段数只能超上限，块大小不能超上限：${result.totalSegments}", result.totalSegments > pipeline.maxChunks)
        assertTrue(result.chunkCapExceeded)
        val plan = TextChunker.plan(lecture(15000), pipeline.chunkTargetTokens, pipeline.maxChunks, pipeline.maxChunkTokens)
        plan.chunks.forEach { assertTrue("块 ${Tokens.estimate(it.text)} 超上限", Tokens.estimate(it.text) <= pipeline.maxChunkTokens) }
        fake.inputs.forEach { assertTrue("每次请求都要在窗口内：$it", it <= pipeline.effectiveWindow) }
        assertTrue(TreeText.parse(result.mapText).isNotEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `归并失败时原样上收不丢内容`() {
        val (store, dir) = tempStore()
        val fake = FakeLLM(failMerge = true)
        val pipeline = LongTextPipeline(fake, store, contextWindow = 4000)
        val result = runBlocking { pipeline.run(9L, lecture(20000), "标题", true) }
        assertTrue("归并失败也要出图", TreeText.parse(result.mapText).isNotEmpty())
        assertTrue(fake.mergeCalls > 0)
        assertTrue("最终输入被压进预算", result.stats.maxInputTokens <= 4000)
        dir.deleteRecursively()
    }

    @Test
    fun `短内容走直接生成不分段`() {
        val (store, dir) = tempStore()
        val pipeline = LongTextPipeline(FakeLLM(), store, contextWindow = 16384)
        val system = Prompts.generateSystem(true)
        assertTrue(pipeline.useDirectRoute("很短的一段内容。", system, 2048))
        assertTrue(!pipeline.useDirectRoute(lecture(40000), system, 2048))
        dir.deleteRecursively()
    }

    @Test
    fun `每段都失败时抛出可读错误而不是静默空图`() {
        val (store, dir) = tempStore()
        val all = (0 until 40).toSet()
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
    fun `SegmentStore 往返 损坏容错 笔记互不干扰`() {
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
        File(dir, "segments-14.json").writeText(
            """{"fingerprint":"$fp","noteId":14,"segments":{"0":"坏值", "1":{"nested":"不是字符串"}}}"""
        )
        assertEquals("单个坏值不能整份作废", mapOf(0 to "坏值"), store.load(14L, fp))
        store.clear(10L)
        assertEquals(emptyMap<Int, String>(), store.load(10L, fp))
        dir.deleteRecursively()
    }

    @Test
    fun `分段器与流水线的预算一致`() {
        val (store, dir) = tempStore()
        val pipeline = LongTextPipeline(FakeLLM(), store, contextWindow = 8192)
        val plan = TextChunker.plan(lecture(30000), pipeline.chunkTargetTokens, pipeline.maxChunks, pipeline.maxChunkTokens)
        assertTrue(plan.size <= pipeline.maxChunks || plan.chunkCapExceeded)
        assertTrue(pipeline.chunkTargetTokens <= pipeline.maxChunkTokens)
        plan.chunks.forEach { assertTrue(Tokens.estimate(it.text) <= pipeline.maxChunkTokens) }
        dir.deleteRecursively()
    }
}
