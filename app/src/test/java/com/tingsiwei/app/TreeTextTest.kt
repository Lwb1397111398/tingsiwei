package com.tingsiwei.app

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeTextTest {

    @Test
    fun `parse 单根缩进树`() {
        val text = "刑法\n\t功能\n\t\t保护法益\n\t\t保障人权\n\t原则\n\t\t罪刑法定"
        val forest = TreeText.parse(text)
        assertEquals(1, forest.size)
        assertEquals("刑法", forest[0].topic)
        assertEquals(2, forest[0].children.size)
        assertEquals("保护法益", forest[0].children[0].children[0].topic)
        assertEquals("罪刑法定", forest[0].children[1].children[0].topic)
    }

    @Test
    fun `parse 多个顶层主题`() {
        val text = "主题A\n\t子1\n主题B\n\t子2"
        val forest = TreeText.parse(text)
        assertEquals(2, forest.size)
        assertEquals("子2", forest[1].children[0].topic)
    }

    @Test
    fun `parse 空格缩进与空行与 markdown 列表符号`() {
        val text = """
            根
                - 子节点
                * 子节点2

                    深层
        """.trimIndent()
        val forest = TreeText.parse(text)
        assertEquals(1, forest.size)
        val kids = forest[0].children
        assertEquals(2, kids.size)
        assertEquals("子节点", kids[0].topic)
        assertEquals("子节点2", kids[1].topic)
        // 更深的行挂到最近的一个同级节点下
        assertEquals("深层", kids[1].children[0].topic)
    }

    @Test
    fun `toMapData 单根不加虚拟根`() {
        val forest = listOf(TreeNote("根", mutableListOf(TreeNote("子"))))
        val (json, virtualRoot) = TreeText.toMapData(forest, "标题")
        assertEquals(false, virtualRoot)
        assertTrue(json.contains("\"topic\":\"根\""))
        assertTrue(json.contains("me-root"))
    }

    @Test
    fun `toMapData 多根包虚拟根且可逆`() {
        val text = "主题A\n\t子1\n主题B\n\t子2"
        val forest = TreeText.parse(text)
        val (json, virtualRoot) = TreeText.toMapData(forest, "我的笔记")
        assertTrue(virtualRoot)
        // 虚拟根的 topic 是笔记标题
        assertTrue(json.contains("\"topic\":\"我的笔记\""))
        // 序列化回去要还原为多根文本
        val back = TreeText.fromMapData(json, virtualRoot)
        assertEquals(text, back)
    }

    @Test
    fun `单根 round trip`() {
        val text = "刑法\n\t功能\n\t\t保护法益\n\t原则"
        val forest = TreeText.parse(text)
        val (json, virtualRoot) = TreeText.toMapData(forest, "t")
        assertEquals(text, TreeText.fromMapData(json, virtualRoot))
    }

    @Test
    fun `serialize 节点内部换行替换为空格`() {
        val note = TreeNote("多行\n节点")
        assertEquals("多行 节点", TreeText.serialize(listOf(note)))
    }

    @Test
    fun `parse 剥掉 markdown 标题符与加粗`() {
        val text = "## 根\n\t**子节点**\n\t\t*强调*文字"
        val forest = TreeText.parse(text)
        assertEquals("根", forest[0].topic)
        assertEquals("子节点", forest[0].children[0].topic)
        assertEquals("强调*文字", forest[0].children[0].children[0].topic)
    }

    @Test
    fun `parse 负号开头的普通文字不受列表符影响`() {
        val forest = TreeText.parse("-5度以下的低温")
        assertEquals("-5度以下的低温", forest[0].topic)
    }
}

class LlmOutputParserTest {

    @Test
    fun `标准标签格式`() {
        val raw = """
            <导图>
            刑法
            	功能
            </导图>
            <思路>
            刑法的诞生源于……
            </思路>
        """.trimIndent()
        val parsed = LlmOutputParser.parse(raw)
        assertEquals("刑法\n\t功能", parsed.mapText)
        assertEquals("刑法的诞生源于……", parsed.thinking)
    }

    @Test
    fun `标签被代码块包裹且有空白变体`() {
        val raw = "好的，以下是整理结果：\n```xml\n< 导图 >\n根\n\t子\n< /导图 >\n< 思路 >\n叙述\n< /思路 >\n```"
        val parsed = LlmOutputParser.parse(raw)
        assertEquals("根\n\t子", parsed.mapText)
        assertEquals("叙述", parsed.thinking)
    }

    @Test
    fun `缺思路标签时取剩余部分`() {
        val raw = "<导图>\n根\n</导图>\n这是思路内容。"
        val parsed = LlmOutputParser.parse(raw)
        assertEquals("根", parsed.mapText)
        assertEquals("这是思路内容。", parsed.thinking)
    }

    @Test
    fun `无标签时整体当导图`() {
        val raw = "根\n\t子"
        val parsed = LlmOutputParser.parse(raw)
        assertEquals("根\n\t子", parsed.mapText)
        assertEquals("", parsed.thinking)
    }

    @Test
    fun `导图标签内部带代码围栏会被剥掉`() {
        val raw = "<导图>\n```\n根\n\t子\n```\n</导图>\n<思路>\n叙述\n</思路>"
        val parsed = LlmOutputParser.parse(raw)
        assertEquals("根\n\t子", parsed.mapText)
        assertEquals("叙述", parsed.thinking)
    }
}
