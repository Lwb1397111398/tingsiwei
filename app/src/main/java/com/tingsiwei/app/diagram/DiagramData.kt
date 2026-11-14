package com.tingsiwei.app.diagram

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 图示类型常量（LLM 输出与渲染分发共用同一套字面量） */
object DiagramType {
    const val FLOW = "flow"           // 线性流程：步骤 → 步骤
    const val TIMELINE = "timeline"   // 时间轴：时间点 + 事件
    const val MATRIX = "matrix"       // 对比矩阵：列头 × 维度行
    const val HIERARCHY = "hierarchy" // 层级/分类：根 + 两层分支
    const val DECISION = "decision"   // 条件判断：满足 / 不满足两条路径
}

/**
 * 思路分段图示的数据模型。LLM 只产 JSON 结构（结构层），界面用 Compose 原生渲染（渲染层），
 * 两者通过这份模型解耦——借鉴 flowmotion-skill 的「结构优先，渲染在后」。
 *
 * Diagram 故意用单一扁平结构 + type 字符串区分五种图，而不是 sealed class 多态：
 * LLM 输出的 JSON 常有轻微不规范（type 拼错、字段缺失、混入解释文字），
 * 扁平结构配合字段默认值与逐段校验，救回来的概率远高于「整体解析失败」。
 */
@Serializable
data class Diagram(
    val type: String = "",
    val title: String = "",
    /** flow：线性步骤，按顺序排列 */
    val steps: List<String> = emptyList(),
    /** timeline：时间轴事件（t = 时间点/阶段名） */
    val items: List<DiagramItem> = emptyList(),
    /** matrix：columns 是对比对象（列头），rows 是对比维度行 */
    val columns: List<String> = emptyList(),
    val rows: List<MatrixRow> = emptyList(),
    /** hierarchy：root 是总概念，children 是下一层分支（可再带一层子项） */
    val root: String = "",
    val children: List<DiagramBranch> = emptyList(),
    /** decision：question 是判断条件，yes/no 是两条路径的步骤 */
    val question: String = "",
    val yes: List<String> = emptyList(),
    val no: List<String> = emptyList(),
)

@Serializable
data class DiagramItem(val t: String = "", val text: String = "")

@Serializable
data class MatrixRow(val label: String = "", val cells: List<String> = emptyList())

@Serializable
data class DiagramBranch(val text: String = "", val children: List<String> = emptyList())

/** 思路的一个小节：一个知识点 + 可选配图（diagram = null 即该节不值得画图） */
@Serializable
data class DiagramSection(
    val title: String = "",
    val text: String = "",
    val diagram: Diagram? = null,
)

@Serializable
data class DiagramDoc(val sections: List<DiagramSection> = emptyList())

/**
 * 图示 JSON 的编解码与容错解析。容错链：
 * 剥代码围栏 → 抽出首个 JSON 对象（LLM 爱在前后加话）→ 解析；
 * JSON 被截断时丢掉最后半个 section 把前面完整的段救回来（同 LlmOutputParser.parsePartial 的传统）；
 * 每段的 diagram 再过一遍「最小可画」校验——不够画一张图的宁可置 null，不出半成品图。
 */
object DiagramParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** LLM 原文 → DiagramDoc；完全救不回来返回 null，由调用方决定重问或放弃 */
    fun parse(raw: String): DiagramDoc? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val body = extractJsonObject(text) ?: salvageTruncated(text) ?: return null
        val doc = try {
            json.decodeFromString<DiagramDoc>(body)
        } catch (_: Exception) {
            return null
        }
        return clean(doc)
    }

    /** 落库编码：标准化后的 JSON，读回时走同一条 [parse] 容错路径 */
    fun encode(doc: DiagramDoc): String = json.encodeToString(doc)

    /** 从 notes.diagramJson 读回；损坏输入返回 null，思路页回退整段显示 */
    fun decode(stored: String?): DiagramDoc? = stored?.let(::parse)

    /** 过滤：diagram 不够画的置 null，正文标题全空的段丢弃；空文档返回 null */
    private fun clean(doc: DiagramDoc): DiagramDoc? {
        val sections = doc.sections
            .map { it.copy(diagram = it.diagram?.takeIf(::usable)) }
            .filter { it.text.isNotBlank() || it.title.isNotBlank() }
        return if (sections.isEmpty()) null else DiagramDoc(sections)
    }

    /**
     * 各类型的最小可画条件：图里的实质内容至少要有 2 个节点（矩阵除外——
     * 它的价值在「对照」，哪怕只有 1 行维度也算成立）。
     */
    private fun usable(d: Diagram): Boolean = when (d.type) {
        DiagramType.FLOW -> d.steps.count { it.isNotBlank() } >= 2
        DiagramType.TIMELINE -> d.items.count { it.text.isNotBlank() } >= 2
        DiagramType.MATRIX ->
            d.columns.count { it.isNotBlank() } >= 2 &&
                d.rows.any { it.label.isNotBlank() || it.cells.any { c -> c.isNotBlank() } }
        DiagramType.HIERARCHY -> d.root.isNotBlank() && d.children.count { it.text.isNotBlank() } >= 2
        DiagramType.DECISION ->
            d.question.isNotBlank() &&
                (d.yes.any { it.isNotBlank() } || d.no.any { it.isNotBlank() })
        else -> false // 未知类型一律不画
    }

    /** 从混着解释文字的原文里抽出第一个完整闭合的 JSON 对象（花括号配对，跳过字符串字面量） */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            when {
                escaped -> escaped = false
                inString && text[i] == '\\' -> escaped = true
                inString && text[i] == '"' -> inString = false
                inString -> {} // 字符串内的其他字符（含 { 与 }）不参与括号计数
                text[i] == '"' -> inString = true
                text[i] == '{' -> depth++
                text[i] == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null // 没闭合：多半是输出被截断，交给 salvage
    }

    /**
     * 截断抢救：整体 JSON 没闭合时，从 "sections" 数组里逐个收集完整闭合的元素，
     * 丢掉被砍断的最后一截，把前面的段闭合回去。一个完整段都没有则放弃。
     */
    private fun salvageTruncated(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val body = text.substring(start)
        val secIdx = body.indexOf("\"sections\"")
        if (secIdx < 0) return null
        val arrStart = body.indexOf('[', secIdx)
        if (arrStart < 0) return null
        val elements = StringBuilder()
        var i = arrStart + 1
        var count = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] != '{') break
            val elStart = i
            var depth = 0
            var inStr = false
            var esc = false
            var closed = false
            while (i < body.length) {
                val c = body[i]
                when {
                    esc -> esc = false
                    inStr && c == '\\' -> esc = true
                    inStr && c == '"' -> inStr = false
                    inStr -> {} // 同上：字符串内其他字符不参与括号计数
                    c == '"' -> inStr = true
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) {
                            closed = true
                            i++
                            break
                        }
                    }
                }
                i++
            }
            if (!closed) break // 最后一个元素被截断：丢掉
            if (count > 0) elements.append(',')
            elements.append(body.substring(elStart, i))
            count++
        }
        if (count == 0) return null
        return "{\"sections\":[$elements]}"
    }
}
