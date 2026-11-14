package com.tingsiwei.app

import com.tingsiwei.app.diagram.Diagram
import com.tingsiwei.app.diagram.DiagramParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图示 JSON 容错解析测试。LLM 输出五路不齐：规矩的、裹代码块的、前后带话的、被截断的、
 * type 拼错的——解析器每种都要接得住，救不回的段宁可置 null 也不出半成品图。
 */
class DiagramParserTest {

    private fun flowJson(title: String = "诉讼流程", steps: String = "\"起诉\",\"开庭\",\"判决\"") =
        """{"type":"flow","title":"$title","steps":[$steps]}"""

    private fun docBody(vararg sectionJsons: String) =
        """{"sections":[${sectionJsons.joinToString(",")}]}"""

    // ---------- 正常路径 ----------

    @Test
    fun `正常解析 - 流程图小节`() {
        val raw = docBody(
            """{"title":"程序起点","text":"起诉是诉讼的起点。","diagram":${flowJson()}}""",
            """{"title":"管辖","text":"先看管辖法院。","diagram":null}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertEquals(2, doc.sections.size)
        assertEquals("程序起点", doc.sections[0].title)
        val d = doc.sections[0].diagram!!
        assertEquals("flow", d.type)
        assertEquals(listOf("起诉", "开庭", "判决"), d.steps)
        assertNull(doc.sections[1].diagram)
    }

    @Test
    fun `五种图类型全部解析`() {
        val raw = docBody(
            """{"title":"a","text":"…","diagram":${flowJson()}}""",
            """{"title":"b","text":"…","diagram":{"type":"timeline","items":[{"t":"1997","text":"刑法修订"},{"t":"2011","text":"修正案八"}]}}""",
            """{"title":"c","text":"…","diagram":{"type":"matrix","title":"甲乙对比","columns":["甲","乙"],"rows":[{"label":"构成","cells":["四个要件","三阶层"]}]}}""",
            """{"title":"d","text":"…","diagram":{"type":"hierarchy","root":"犯罪构成","children":[{"text":"四要件","children":["主体","客体"]},{"text":"三阶层"}]}}""",
            """{"title":"e","text":"…","diagram":{"type":"decision","question":"是否构成正当防卫","yes":["不负刑责"],"no":["考虑防卫过当"]}}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertEquals(5, doc.sections.size)
        assertEquals(listOf("timeline", "matrix", "hierarchy", "decision"),
            doc.sections.drop(1).map { it.diagram?.type })
        assertEquals("甲", doc.sections[2].diagram!!.columns[0])
        assertEquals("主体", doc.sections[3].diagram!!.children[0].children[0])
        assertEquals("不负刑责", doc.sections[4].diagram!!.yes[0])
    }

    // ---------- 容错：包裹与废话 ----------

    @Test
    fun `代码块包裹的 JSON`() {
        val raw = "```json\n${docBody("""{"title":"a","text":"正文","diagram":${flowJson()}}""")}\n```"
        val doc = DiagramParser.parse(raw)
        assertNotNull(doc)
        assertEquals(1, doc!!.sections.size)
        assertEquals("flow", doc.sections[0].diagram?.type)
    }

    @Test
    fun `前后带解释文字时抽出 JSON 对象`() {
        val raw = "好的，以下是拆分结果：\n\n${docBody("""{"title":"a","text":"正文","diagram":null}""")}\n\n希望对你有帮助！"
        val doc = DiagramParser.parse(raw)
        assertNotNull(doc)
        assertEquals("a", doc!!.sections[0].title)
    }

    // ---------- 容错：截断抢救 ----------

    @Test
    fun `截断的 JSON 丢掉最后半个段落，救回前面的完整段`() {
        // 第二段的 diagram 被砍断：diagram 对象只有一半
        val raw = docBody(
            """{"title":"完整段","text":"这段是好的。","diagram":${flowJson()}}""",
            """{"title":"被砍段","text":"这段断了。","diagram":{"type":"timeline","items":[{"t":"199""",
        )
        val doc = DiagramParser.parse(raw)
        assertNotNull(doc)
        assertEquals(1, doc!!.sections.size)
        assertEquals("完整段", doc.sections[0].title)
        assertEquals("flow", doc.sections[0].diagram?.type)
    }

    @Test
    fun `截断时字符串中间被砍也能丢弃该段`() {
        // 段落文字本身被砍断（引号未闭合）
        val raw = docBody(
            """{"title":"a","text":"好的段落。","diagram":null}""",
            """{"title":"b","text":"这段文字写到一半就被截""",
        )
        val doc = DiagramParser.parse(raw)
        assertNotNull(doc)
        assertEquals(1, doc!!.sections.size)
        assertEquals("a", doc.sections[0].title)
    }

    // ---------- 容错：坏数据降级 ----------

    @Test
    fun `未知 type 的 diagram 置 null，section 保留`() {
        val raw = docBody(
            """{"title":"a","text":"正文。","diagram":{"type":"mindmap-v2","nodes":["x"]}}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertEquals(1, doc.sections.size)
        assertNull(doc.sections[0].diagram)
    }

    @Test
    fun `图内容不足 - 流程只有一步则不画`() {
        val raw = docBody(
            """{"title":"a","text":"正文。","diagram":${flowJson(steps = "\"只有一步\"")}}""",
        )
        assertNull(DiagramParser.parse(raw)!!.sections[0].diagram)
    }

    @Test
    fun `图内容不足 - 层级缺根或分支不够则不画`() {
        val raw = docBody(
            """{"title":"a","text":"…","diagram":{"type":"hierarchy","children":[{"text":"没有根"}]}}""",
            """{"title":"b","text":"…","diagram":{"type":"hierarchy","root":"有根","children":[{"text":"只有一支"}]}}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertNull(doc.sections[0].diagram)
        assertNull(doc.sections[1].diagram)
    }

    @Test
    fun `决策图缺 question 则不画`() {
        val raw = docBody(
            """{"title":"a","text":"…","diagram":{"type":"decision","yes":["一步"],"no":["两步"]}}""",
        )
        assertNull(DiagramParser.parse(raw)!!.sections[0].diagram)
    }

    @Test
    fun `矩阵只要有一行维度就成立`() {
        val raw = docBody(
            """{"title":"a","text":"…","diagram":{"type":"matrix","columns":["甲","乙"],"rows":[{"label":"核心","cells":["A","B"]}]}}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertNotNull(doc.sections[0].diagram)
    }

    @Test
    fun `缺字段按默认值解析不抛异常`() {
        val raw = docBody("""{"title":"a","text":"正文"}""")
        val doc = DiagramParser.parse(raw)!!
        assertEquals(1, doc.sections.size)
        assertNull(doc.sections[0].diagram)
    }

    @Test
    fun `标题正文全空的段被丢弃`() {
        val raw = docBody(
            """{"title":"","text":"","diagram":null}""",
            """{"title":"有效段","text":"正文。","diagram":null}""",
        )
        val doc = DiagramParser.parse(raw)!!
        assertEquals(1, doc.sections.size)
        assertEquals("有效段", doc.sections[0].title)
    }

    // ---------- 彻底失败 ----------

    @Test
    fun `完全不是 JSON 返回 null`() {
        assertNull(DiagramParser.parse("抱歉，我无法处理这个请求。"))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(DiagramParser.parse(""))
        assertNull(DiagramParser.parse("   "))
        assertNull(DiagramParser.decode(null))
    }

    @Test
    fun `截断到连一个完整段都没有返回 null`() {
        val raw = """{"sections":[{"title":"只有开""""
        assertNull(DiagramParser.parse(raw))
    }

    @Test
    fun `损坏的存库数据返回 null`() {
        assertNull(DiagramParser.decode("不是 JSON 的乱码"))
        assertNull(DiagramParser.decode("{\"sections\": 42}"))
    }

    // ---------- 编解码往返 ----------

    @Test
    fun `encode 后 decode 往返一致`() {
        val doc = DiagramParser.parse(
            docBody(
                """{"title":"a","text":"正文。","diagram":${flowJson()}}""",
                """{"title":"b","text":"第二段。","diagram":null}""",
            )
        )!!
        val stored = DiagramParser.encode(doc)
        val roundTrip = DiagramParser.decode(stored)!!
        assertEquals(doc, roundTrip)
    }

    @Test
    fun `encode 产出可直接被 parse 接受`() {
        val doc = DiagramParser.parse(docBody("""{"title":"a","text":"正文","diagram":${flowJson()}}"""))!!
        val again = DiagramParser.parse(DiagramParser.encode(doc))!!
        assertEquals("flow", again.sections[0].diagram?.type)
    }

    // ---------- Diagram 模型默认值（渲染层兜底依赖） ----------

    @Test
    fun `Diagram 全字段默认值构造`() {
        val d = Diagram()
        assertEquals("", d.type)
        assertTrue(d.steps.isEmpty())
        assertTrue(d.rows.isEmpty())
    }
}
