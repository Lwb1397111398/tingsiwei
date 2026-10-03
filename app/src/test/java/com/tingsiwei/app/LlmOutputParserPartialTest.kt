package com.tingsiwei.app

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 截断抢救解析：输出被长度上限砍断时标签不闭合，抢救要能拿出大半个导图而不是整段作废 */
class LlmOutputParserPartialTest {

    @Test
    fun `导图未闭合时取标签后的内容并丢掉被截断的尾行`() {
        val raw = "<导图>\n罪刑法定原则\n\t含义\n\t\t法无明文规定不为罪\n\t要求\n\t\t成文的法\n\t\t正当程序\n\t\t正当"
        val parsed = LlmOutputParser.parsePartial(raw)!!
        assertTrue(TreeText.parse(parsed.mapText).isNotEmpty())
        assertTrue("被拦腰截断的最后一行要丢掉：${parsed.mapText}", !parsed.mapText.endsWith("正当"))
        assertTrue("完整行要保留", parsed.mapText.contains("成文的法"))
        assertEquals("", parsed.thinking)
    }

    @Test
    fun `导图闭合但思路被截断时思路丢尾行`() {
        val raw = "<导图>\n总主题\n\t分支\n</导图>\n<思路>\n因为法律要成文所以是罪刑法定。接着要说的"
        val parsed = LlmOutputParser.parsePartial(raw)!!
        assertTrue(parsed.mapText.contains("总主题"))
        assertTrue("残句要丢掉：${parsed.thinking}", !parsed.thinking.endsWith("要说的"))
        assertTrue(parsed.thinking.startsWith("因为"))
    }

    @Test
    fun `标签完整时按完整解析不丢内容`() {
        val raw = "<导图>\n总主题\n\t分支\n</导图>\n<思路>\n完整的思路。\n</思路>"
        val parsed = LlmOutputParser.parsePartial(raw)!!
        assertEquals("完整的思路。", parsed.thinking)
        assertTrue(parsed.mapText.contains("分支"))
    }

    @Test
    fun `markdown 围栏和空白不挡抢救`() {
        val raw = "```text\n<导图>\n总主题\n\t分支一\n\t分支二\n"
        val parsed = LlmOutputParser.parsePartial(raw)!!
        assertTrue(parsed.mapText.contains("分支一"))
    }

    @Test
    fun `完全解析不出结构时返回null`() {
        assertNull(LlmOutputParser.parsePartial(""))
        assertNull(LlmOutputParser.parsePartial("   "))
        assertNull(LlmOutputParser.parsePartial("<思路>思路先出来了导图没了"))
    }

    @Test
    fun `render 出来的内容能被完整解析走同一条路`() {
        val salvaged = LlmOutputParser.parsePartial("<导图>\n总主题\n\t分支\n\t\t叶子\n")!!
        val reparsed = LlmOutputParser.parse(LlmOutputParser.render(salvaged))
        assertEquals(salvaged.mapText, reparsed.mapText)
        assertEquals(salvaged.thinking, reparsed.thinking)
        assertTrue(TreeText.parse(reparsed.mapText).isNotEmpty())
    }
}
