package com.tingsiwei.app

import com.tingsiwei.app.llm.TextChunker
import com.tingsiwei.app.llm.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 语义分段：句边界、重叠、可还原、块数收敛、无边界退级。 */
class TextChunkerTest {

    private fun sentences(n: Int): String =
        (1..n).joinToString("") { "第${it}个小节里讲了若干知识点并给出例子。" }

    private val strong = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')

    @Test
    fun `空文本得到单个空块`() {
        val plan = TextChunker.plan("   ", targetTokens = 200, maxChunks = 12)
        assertEquals(1, plan.size)
        assertEquals("", plan.chunks[0].text)
        assertEquals(0, plan.chunks[0].overlapChars)
    }

    @Test
    fun `短文本不拆分`() {
        val text = "只有一句话的内容。"
        val plan = TextChunker.plan(text, targetTokens = 900, maxChunks = 12)
        assertEquals(1, plan.size)
        assertEquals(text, plan.chunks[0].text)
        assertEquals(0, plan.chunks[0].overlapChars)
    }

    @Test
    fun `恰好等于预算时只出一块`() {
        val text = sentences(6)
        val plan = TextChunker.plan(text, targetTokens = Tokens.estimate(text), maxChunks = 12)
        assertEquals(1, plan.size)
    }

    @Test
    fun `长文本会分块`() {
        val plan = TextChunker.plan(sentences(40), targetTokens = 100, maxChunks = 60)
        assertTrue("应当分块", plan.size > 5)
    }

    @Test
    fun `除最后一块外每块都以句末标点收束`() {
        val plan = TextChunker.plan(sentences(40), targetTokens = 100, maxChunks = 60)
        plan.chunks.dropLast(1).forEach {
            assertTrue("块 ${it.index} 切断在句中：…${it.text.takeLast(6)}", it.text.last() in strong)
        }
    }

    @Test
    fun `去掉重叠后拼接可还原原文`() {
        val text = sentences(40)
        val plan = TextChunker.plan(text, targetTokens = 120, maxChunks = 60)
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `相邻块的重叠等于上一块尾部`() {
        val plan = TextChunker.plan(sentences(40), targetTokens = 120, maxChunks = 60)
        assertTrue(plan.size > 1)
        var withOverlap = 0
        for (i in 1 until plan.chunks.size) {
            val cur = plan.chunks[i]
            val prev = plan.chunks[i - 1]
            if (cur.overlapChars > 0) {
                withOverlap++
                assertEquals(
                    "块 $i 的重叠应等于块 ${i - 1} 的尾部",
                    prev.text.takeLast(cur.overlapChars),
                    cur.text.take(cur.overlapChars),
                )
                assertTrue("重叠必须收在句末", cur.text[cur.overlapChars - 1] in strong)
            }
        }
        assertTrue("绝大多数相邻块都应带重叠", withOverlap >= plan.size - 2)
    }

    @Test
    fun `每块严格不超预算`() {
        val text = sentences(60)
        val target = 120
        val plan = TextChunker.plan(text, targetTokens = target, maxChunks = 60)
        plan.chunks.forEach {
            assertTrue("块 ${it.index}=${Tokens.estimate(it.text)} 超预算", Tokens.estimate(it.text) <= target)
        }
    }

    @Test
    fun `重叠放不下时放弃重叠 绝不超上限`() {
        // 短尾句(21 token) 会被选作重叠，但下一句 95 token 加上去就是 116 > 100：
        // 老实现会照拼，块就超预算并被下游静默截断
        val short = "短句。".repeat(7)
        val long = "长".repeat(94) + "。"
        val text = (short + long).repeat(6)
        val target = 100
        assertTrue("构造不成立：${Tokens.estimate(short)}+${Tokens.estimate(long)}", Tokens.estimate(short) + Tokens.estimate(long) > target)
        val plan = TextChunker.plan(text, targetTokens = target, maxChunks = 60)
        assertTrue(plan.size > 1)
        plan.chunks.forEach {
            assertTrue("块 ${it.index}=${Tokens.estimate(it.text)} 超上限 $target", Tokens.estimate(it.text) <= target)
        }
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `重叠部分不重复计入总量`() {
        val text = sentences(30)
        val plan = TextChunker.plan(text, targetTokens = 150, maxChunks = 60)
        val sum = plan.chunks.sumOf { Tokens.estimate(it.text) - Tokens.estimate(it.text.take(it.overlapChars)) }
        assertTrue("去重后总 token 应等于原文：$sum vs ${Tokens.estimate(text)}", sum <= Tokens.estimate(text) + 2)
    }

    @Test
    fun `块数收敛到 maxChunks 并自动放大预算`() {
        val text = sentences(200)
        val plan = TextChunker.plan(text, targetTokens = 80, maxChunks = 4)
        assertTrue("应收到 4 块以内，实为 ${plan.size}", plan.size <= 4)
        assertTrue("收敛时 target 必须上调", plan.targetTokens > 80)
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `无标点长文退级为硬切并标记 noBoundary`() {
        val text = "字".repeat(3000)
        val plan = TextChunker.plan(text, targetTokens = 200, maxChunks = 60)
        assertTrue(plan.noBoundary)
        assertTrue(plan.size > 5)
        assertEquals(text, plan.stripped())
        plan.chunks.forEach {
            assertTrue("硬切后仍超预算：${Tokens.estimate(it.text)}", Tokens.estimate(it.text) <= 200)
        }
    }

    @Test
    fun `无句号但有逗号时按逗号退级`() {
        val text = "这里有一长串没有句号的文字，中间用逗号隔开，".repeat(120)
        val plan = TextChunker.plan(text, targetTokens = 200, maxChunks = 60)
        assertTrue(plan.size > 3)
        assertFalse("有逗号就不该判成无边界硬切", plan.noBoundary)
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `中英混排可还原且不误判为超长`() {
        val text = "刑法的 function 是保护法益。This clause protects legal interests。罪刑法定 principle 要求明文规定。".repeat(30)
        val plan = TextChunker.plan(text, targetTokens = 200, maxChunks = 60)
        assertTrue(plan.size > 2)
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `保留原始换行结构`() {
        val text = (1..40).joinToString("\n") { "第 $it 行转写内容。" }
        val plan = TextChunker.plan(text, targetTokens = 120, maxChunks = 60)
        assertEquals(text.trim().count { it == '\n' }, plan.stripped().count { it == '\n' })
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `块序号连续递增`() {
        val plan = TextChunker.plan(sentences(50), targetTokens = 90, maxChunks = 60)
        assertEquals(List(plan.size) { it }, plan.chunks.map { it.index })
    }

    @Test
    fun `单句超长时仍向前推进不死循环`() {
        val text = "这是一个特别长的句子".repeat(40) + "。" + "短的收尾。"
        val plan = TextChunker.plan(text, targetTokens = 60, maxChunks = 60)
        assertTrue(plan.size >= 2)
        assertEquals(text.trim(), plan.stripped())
    }

    @Test
    fun `多段落混合转写不丢内容`() {
        val text = buildString {
            repeat(25) {
                appendLine("第${it}段开头。这是中间的一句说明文字，带了逗号。这是结尾的一句。")
                if (it % 5 == 0) appendLine()
            }
        }
        val plan = TextChunker.plan(text, targetTokens = 150, maxChunks = 60)
        assertEquals(text.trim(), plan.stripped())
        assertTrue(plan.size in 2..60)
    }
}
