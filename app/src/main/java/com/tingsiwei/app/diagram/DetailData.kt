package com.tingsiwei.app.diagram

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 详细讲解的数据模型：对照录音转写原文，为思路的每个小节补全被总结压缩掉的细节；
 * 录音里有、总结却整个漏掉的话题以 extra 条目追加（界面挂「补充」徽标）。
 * 听书读的文字版与「复制全文」按钮都由这份文本本地拼出（[composeFullText]），
 * 不额外调 AI——一份内容两种形态（点开卡片看详解 / 整篇连读），天然对齐。
 */
@Serializable
data class DetailItem(
    val title: String = "",
    val detail: String = "",
    /** true = 总结里漏掉的整个话题，AI 追加的补充小节（与现有小节标题对不上即判定） */
    val extra: Boolean = false,
)

@Serializable
data class DetailDoc(val items: List<DetailItem> = emptyList())

/**
 * 详解 JSON 的编解码与容错解析。容错链与 [DiagramParser] 同一门派：
 * 剥代码围栏 → 抽出首个 JSON 对象（LLM 爱在前后加话）→ 解析；
 * JSON 被截断时丢掉最后半个 item 把前面完整的救回来；
 * 标题与现有小节对不上的条目视为补充小节（[markExtras]），一条都对不上则整份作废。
 */
object DetailParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** LLM 原文 → DetailDoc；完全救不回来返回 null，由调用方决定重问或放弃 */
    fun parse(raw: String): DetailDoc? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val body = DiagramParser.extractJsonObject(text) ?: salvageTruncated(text) ?: return null
        val doc = try {
            json.decodeFromString<DetailDoc>(body)
        } catch (_: Exception) {
            return null
        }
        return clean(doc)
    }

    /** 落库编码：标准化后的 JSON，读回时走同一条 [parse] 容错路径 */
    fun encode(doc: DetailDoc): String = json.encodeToString(doc)

    /** 从 notes.detailJson 读回；损坏输入返回 null，界面回退思路原文 */
    fun decode(stored: String?): DetailDoc? = stored?.let(::parse)

    /**
     * 补充小节标记：与给定小节标题（trim 后比对）对得上的条目 extra=false，
     * 对不上的标 extra=true（保持 LLM 输出顺序，渲染时排在现有小节后面）。
     * 一条都对不上时返回 null——多半是模型重写了整套标题，按补充全收会与现有小节大量重复，
     * 宁可当失败走重问/放弃，也不给用户一份错位的讲解。
     */
    fun markExtras(doc: DetailDoc, sectionTitles: List<String>): DetailDoc? {
        val items = doc.items
            .map { it.copy(title = it.title.trim(), detail = it.detail.trim()) }
            .filter { it.title.isNotEmpty() || it.detail.isNotEmpty() }
        if (items.isEmpty()) return null
        val known = sectionTitles.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val marked = items.map { it.copy(extra = it.title !in known) }
        return if (marked.any { !it.extra }) DetailDoc(marked) else null
    }

    /** 过滤：标题正文全空的条目丢弃；空文档返回 null */
    private fun clean(doc: DetailDoc): DetailDoc? {
        val items = doc.items
            .map { it.copy(title = it.title.trim(), detail = it.detail.trim()) }
            .filter { it.title.isNotEmpty() || it.detail.isNotEmpty() }
        return if (items.isEmpty()) null else DetailDoc(items)
    }

    /** 截断抢救：整体 JSON 没闭合时，丢掉 "items" 里被砍断的最后一截，救回前面完整的条目 */
    private fun salvageTruncated(text: String): String? = DiagramParser.salvageArray(text, "items")
}

// ---------- 文字版拼接 ----------

private val CnIndex = listOf(
    "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
)

private fun cnIndex(n: Int): String = if (n in 1..CnIndex.size) CnIndex[n - 1] else n.toString()

/**
 * 把详解拼成适合听书与复制的纯文字版：每节「中文序号、标题、正文」一段，节间空行。
 * 序号用中文数字——TTS 朗读「1、」容易读成「一点」，「一、」天然顺口；标题独立成行，
 * 收听时是天然的段落提示，复制出来也是一份结构完整的笔记。
 */
fun composeFullText(doc: DetailDoc): String = doc.items
    .filter { it.title.isNotEmpty() || it.detail.isNotEmpty() }
    .mapIndexed { i, item ->
        val head = if (item.title.isNotEmpty()) "${cnIndex(i + 1)}、${item.title}" else ""
        if (head.isNotEmpty() && item.detail.isNotEmpty()) "$head\n${item.detail}"
        else if (head.isNotEmpty()) head
        else item.detail
    }
    .filter { it.isNotBlank() }
    .joinToString("\n\n")
