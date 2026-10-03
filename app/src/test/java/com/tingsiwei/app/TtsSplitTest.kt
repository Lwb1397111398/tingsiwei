package com.tingsiwei.app

import com.tingsiwei.app.tts.splitForSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 朗读长文分块：系统引擎单条 utterance 4000 字上限，必须按句子边界切块排队 */
class TtsSplitTest {

    @Test
    fun `短文本不分块`() {
        assertEquals(listOf("你好。"), splitForSpeech("你好。"))
        assertEquals(emptyList<String>(), splitForSpeech("   "))
    }

    @Test
    fun `长文本按句子边界切且每块不超上限`() {
        val sentence = "这是一个用来测试的完整句子，里面带一点长度。"
        val text = sentence.repeat(1000) // 约 2.2 万字
        val chunks = splitForSpeech(text, maxChars = 3000)
        assertTrue("应该切成多块：${chunks.size}", chunks.size > 5)
        chunks.forEach { assertTrue("块超上限：${it.length}", it.length <= 3000) }
        assertEquals("拼回去不丢字", text.replace("\n", "").length, chunks.joinToString("").length)
        chunks.forEach { assertTrue("要收在句末：${it.takeLast(3)}", it.last() == '。') }
    }

    @Test
    fun `没有句读时硬切不丢字`() {
        val text = "甲".repeat(7000)
        val chunks = splitForSpeech(text, maxChars = 3000)
        assertEquals(3, chunks.size)
        assertEquals(7000, chunks.sumOf { it.length })
    }

    @Test
    fun `离线引擎的小块切分`() {
        val sentence = "完整的一句话。"
        val text = sentence.repeat(300) // 2100 字
        val chunks = splitForSpeech(text, maxChars = 320)
        assertTrue("切成多块：${chunks.size}", chunks.size > 5)
        chunks.forEach { assertTrue("块超上限：${it.length}", it.length <= 320) }
        assertEquals("拼回去不丢字", text.length, chunks.joinToString("").length)
        chunks.forEach { assertEquals('。', it.last()) }
    }
}
