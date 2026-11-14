package com.tingsiwei.app

import com.tingsiwei.app.diagram.DetailDoc
import com.tingsiwei.app.diagram.DetailItem
import com.tingsiwei.app.diagram.DetailParser
import com.tingsiwei.app.diagram.composeFullText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详细讲解 JSON 容错解析测试。与 DiagramParserTest 同一门派：规矩的、裹代码块的、
 * 前后带话的、被截断的、标题对不上的——解析器每种都要接得住；
 * 补充小节标记与文字版拼接也在这里钉住。
 */
class DetailParserTest {

    private fun itemJson(title: String, detail: String, extra: Boolean? = null) = buildString {
        append("""{"title":"$title","detail":"$detail"""")
        if (extra != null) append(""","extra":$extra""")
        append("}")
    }

    private fun docBody(vararg itemJsons: String) =
        """{"items":[${itemJsons.joinToString(",")}]}"""

    // ---------- 正常路径 ----------

    @Test
    fun `正常解析两条详解`() {
        val raw = docBody(
            itemJson("民法的调整对象", "民法调整平等主体之间的人身关系与财产关系。"),
            itemJson("基本原则", "平等原则、自愿原则、公平原则、诚信原则。"),
        )
        val doc = DetailParser.parse(raw)!!
        assertEquals(2, doc.items.size)
        assertEquals("民法的调整对象", doc.items[0].title)
        assertTrue(doc.items[0].detail.startsWith("民法调整"))
        assertFalse(doc.items[0].extra)
    }

    @Test
    fun `代码块包裹的 JSON`() {
        val raw = "```json\n${docBody(itemJson("a", "正文。"))}\n```"
        val doc = DetailParser.parse(raw)
        assertNotNull(doc)
        assertEquals("a", doc!!.items[0].title)
    }

    @Test
    fun `前后带解释文字时抽出 JSON 对象`() {
        val raw = "好的，以下是详细讲解：\n\n${docBody(itemJson("a", "正文。"))}\n\n希望对你有帮助！"
        val doc = DetailParser.parse(raw)
        assertNotNull(doc)
        assertEquals("a", doc!!.items[0].title)
    }

    // ---------- 容错：截断抢救 ----------

    @Test
    fun `截断的 JSON 丢掉最后半个条目，救回前面的完整条目`() {
        // 最后一条 detail 写到一半被截断（引号未闭合）
        val raw = docBody(
            itemJson("完整节", "这一节是好的。"),
            """{"title":"被砍节","detail":"这段文字写到一半就被截""",
        )
        val doc = DetailParser.parse(raw)
        assertNotNull(doc)
        assertEquals(1, doc!!.items.size)
        assertEquals("完整节", doc.items[0].title)
    }

    @Test
    fun `截断到连一个完整条目都没有返回 null`() {
        val raw = """{"items":[{"title":"只有开"""
        assertNull(DetailParser.parse(raw))
    }

    // ---------- 容错：坏数据降级 ----------

    @Test
    fun `完全不是 JSON 返回 null`() {
        assertNull(DetailParser.parse("抱歉，我无法处理这个请求。"))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(DetailParser.parse(""))
        assertNull(DetailParser.parse("   "))
        assertNull(DetailParser.decode(null))
    }

    @Test
    fun `损坏的存库数据返回 null`() {
        assertNull(DetailParser.decode("不是 JSON 的乱码"))
        assertNull(DetailParser.decode("{\"items\": 42}"))
    }

    @Test
    fun `标题正文全空的条目被丢弃`() {
        val raw = docBody(
            itemJson("", ""),
            itemJson("有效条目", "正文。"),
        )
        val doc = DetailParser.parse(raw)!!
        assertEquals(1, doc.items.size)
        assertEquals("有效条目", doc.items[0].title)
    }

    @Test
    fun `多出来的未知字段不影响解析`() {
        val raw = docBody("""{"title":"a","detail":"正文。","summary":"多余字段"}""")
        val doc = DetailParser.parse(raw)!!
        assertEquals("a", doc.items[0].title)
    }

    // ---------- 编解码往返 ----------

    @Test
    fun `encode 后 decode 往返一致`() {
        val doc = DetailParser.parse(
            docBody(
                itemJson("a", "正文一。"),
                itemJson("b", "正文二。"),
            )
        )!!
        val roundTrip = DetailParser.decode(DetailParser.encode(doc))!!
        assertEquals(doc, roundTrip)
    }

    // ---------- markExtras：补充小节标记 ----------

    @Test
    fun `对得上的小节保持非 extra，对不上的标为补充`() {
        val doc = DetailParser.parse(
            docBody(
                itemJson("基本原则", "讲解一。"),
                itemJson("无因管理", "录音里讲了但总结漏掉的话题。"),
            )
        )!!
        val marked = DetailParser.markExtras(doc, listOf("基本原则", "民法的调整对象"))!!
        assertEquals(2, marked.items.size)
        assertFalse(marked.items[0].extra)
        assertTrue(marked.items[1].extra)
    }

    @Test
    fun `标题前后空白不影响匹配`() {
        val doc = DetailParser.parse(docBody(itemJson(" 基本原则 ", "讲解。")))!!
        val marked = DetailParser.markExtras(doc, listOf("基本原则"))!!
        assertFalse(marked.items[0].extra)
    }

    @Test
    fun `markExtras 重算 extra 标记，不信任 LLM 自带的值`() {
        // LLM 把标题匹配的条目标成 extra=true：以程序比对结果为准，改回 false
        val doc = DetailDoc(listOf(DetailItem(title = "基本原则", detail = "讲解。", extra = true)))
        val marked = DetailParser.markExtras(doc, listOf("基本原则"))!!
        assertFalse(marked.items[0].extra)
    }

    @Test
    fun `全部标题都对不上返回 null（模型重写了整套标题，宁可失败不乱收）`() {
        val doc = DetailParser.parse(
            docBody(itemJson("第一节", "讲解一。"), itemJson("第二节", "讲解二。"))
        )!!
        assertNull(DetailParser.markExtras(doc, listOf("基本原则", "民法的调整对象")))
    }

    @Test
    fun `空条目列表返回 null`() {
        assertNull(DetailParser.markExtras(DetailDoc(emptyList()), listOf("基本原则")))
    }

    // ---------- composeFullText：文字版拼接 ----------

    @Test
    fun `拼接格式为中文序号、标题、正文，节间空行`() {
        val doc = DetailDoc(
            listOf(
                DetailItem(title = "基本原则", detail = "平等原则是指……"),
                DetailItem(title = "诉讼时效", detail = "普通诉讼时效为三年。"),
            )
        )
        val text = composeFullText(doc)
        assertEquals("一、基本原则\n平等原则是指……\n\n二、诉讼时效\n普通诉讼时效为三年。", text)
    }

    @Test
    fun `超过二十节退回阿拉伯数字序号`() {
        val items = (1..21).map { DetailItem(title = "第${it}节", detail = "正文。") }
        val text = composeFullText(DetailDoc(items))
        assertTrue(text.contains("二十、第20节"))
        assertTrue(text.contains("21、第21节"))
    }

    @Test
    fun `标题为空的条目只拼正文`() {
        val doc = DetailDoc(listOf(DetailItem(title = "", detail = "只有正文的条目。")))
        assertEquals("只有正文的条目。", composeFullText(doc))
    }
}
