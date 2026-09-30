package com.tingsiwei.app

import com.tingsiwei.app.llm.StagedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三段式生成的纯逻辑：窗口预算判定与 <思路> 正文提取。 */
class StagedFlowTest {

    @Test
    fun `128k 窗口下常见篇幅都能走三段式`() {
        assertTrue(StagedFlow.fits(131_072, 0))
        assertTrue(StagedFlow.fits(131_072, 25_756)) // 2 小时录音的转写量级
        assertTrue(StagedFlow.fits(131_072, 100_000))
    }

    @Test
    fun `预算卡在最贵的优化一步上`() {
        // 优化一步 = 原文 + 草稿(6144) + 提示余量(600) + 定稿输出(8192) = 原文 + 14936
        assertTrue(StagedFlow.fits(131_072, 131_072 - 14_936))
        assertFalse(StagedFlow.fits(131_072, 131_072 - 14_935))
        // 出图一步 = 定稿思路(8192) + 提示(600) + 导图输出(16384) = 25176，窗口比这还小就直接没戏
        assertTrue(StagedFlow.fits(25_176, 0))
        assertFalse(StagedFlow.fits(25_175, 0))
    }

    @Test
    fun `输出上限抬高后 16k 老窗口装不下三段式自动退回直路`() {
        assertFalse(StagedFlow.fits(16_384, 0))
        assertFalse(StagedFlow.fits(16_384, 4_000))
    }

    @Test
    fun `思路正文从标签里提取`() {
        assertEquals("正文内容。", StagedFlow.thinkingText("<思路>\n正文内容。\n</思路>"))
        assertEquals("正文内容。", StagedFlow.thinkingText("好的，下面是思路。\n<思路>正文内容。</思路>\n以上。"))
    }

    @Test
    fun `闭标签被截断时带回正文并丢掉残句`() {
        assertEquals("第一句。第二句。", StagedFlow.thinkingText("<思路>\n第一句。第二句。第三句没写完"))
        // 推理模型的思维链也可能占满输出：只剩半截也强过空白
        assertTrue(StagedFlow.thinkingText("<思路>\n用户给了 法理学 的内容，需要梳理").isNotEmpty())
    }

    @Test
    fun `没按格式输出时整段当思路`() {
        assertEquals("第一句。第二句。", StagedFlow.thinkingText("第一句。第二句。"))
        assertEquals("", StagedFlow.thinkingText(""))
        assertEquals("", StagedFlow.thinkingText("   "))
    }
}
