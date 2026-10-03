package com.tingsiwei.app.mindmap

/**
 * 解析 LLM 输出：约定格式为
 * <导图>
 * ...缩进树...
 * </导图>
 * <思路>
 * ...记忆叙述...
 * </思路>
 * 解析带容错：标签两侧可有空白、可被 markdown 代码块包裹、标签名可变体。
 */
object LlmOutputParser {

    data class Parsed(val mapText: String, val thinking: String)

    /** 去掉 markdown 代码围栏（``` / ```text 等），防止围栏符号被当成节点内容 */
    private fun stripFences(s: String): String =
        s.replace(Regex("```+[a-zA-Z0-9_-]*\\n?"), "").replace("```", "").trim()

    fun parse(raw: String): Parsed {
        val text = raw.trim()

        val mapTag = Regex("<\\s*导图\\s*>([\\s\\S]*?)<\\s*/\\s*导图\\s*>")
        val thinkTag = Regex("<\\s*思路\\s*>([\\s\\S]*?)<\\s*/\\s*思路\\s*>")

        val mapMatch = mapTag.find(text)
        if (mapMatch != null) {
            val mapText = stripFences(mapMatch.groupValues[1])
            val thinking = thinkTag.find(text)?.groupValues?.get(1)?.trim()
                ?: text.substring(mapMatch.range.last + 1).trim()
            if (mapText.isNotBlank()) return Parsed(mapText, thinking)
        }

        // 兜底：markdown 分节标题形式
        val headingMap = Regex("(?:#+\\s*|\\*\\*)\\s*(?:思维导图|导图)\\s*(?:\\*+)?\\s*\\n?([\\s\\S]*?)(?:\\n\\s*(?:#+|\\*\\*)\\s*(?:思路|记忆思路)|$)")
        val headingThink = Regex("(?:#+\\s*|\\*\\*)\\s*(?:思路|记忆思路)\\s*(?:\\*+)?\\s*:?\\s*\\n?([\\s\\S]*)")
        val hMap = headingMap.find(text)
        if (hMap != null && stripFences(hMap.groupValues[1]).isNotBlank()) {
            return Parsed(stripFences(hMap.groupValues[1]), headingThink.find(text)?.groupValues?.get(1)?.trim() ?: "")
        }

        // 最后兜底：整体当作导图，思路为空
        return Parsed(stripFences(text), "")
    }

    /**
     * 截断抢救：输出被长度上限砍断时标签不会闭合，按完整 parse 会退到"整体当导图"而混进标签碎片。
     * 这里按标签实际闭合情况切开，被截断的部分丢掉最后一行，解析不出结构返回 null。
     */
    fun parsePartial(raw: String): Parsed? {
        val text = stripFences(raw.trim())
        if (text.isBlank()) return null

        val closedMap = Regex("<\\s*导图\\s*>([\\s\\S]*?)<\\s*/\\s*导图\\s*>").find(text)
        val closedThink = Regex("<\\s*思路\\s*>([\\s\\S]*?)<\\s*/\\s*思路\\s*>").find(text)
        val openThink = Regex("<\\s*思路\\s*>").find(text)

        return when {
            closedMap != null -> {
                // 导图完整；思路可能被砍断
                val mapText = stripFences(closedMap.groupValues[1])
                val thinking = when {
                    closedThink != null -> closedThink.groupValues[1].trim()
                    openThink != null -> dropTruncatedTail(text.substring(openThink.range.last + 1))
                    else -> ""
                }
                if (mapText.isBlank()) null else Parsed(mapText, thinking)
            }
            // <导图> 开标签存在但没闭合：取标签后的内容当导图，后面若挂着残缺的思路一并切开
            Regex("<\\s*导图\\s*>").containsMatchIn(text) -> {
                val afterMap = text.substring(Regex("<\\s*导图\\s*>").find(text)!!.range.last + 1)
                val thinkStart = Regex("<\\s*思路\\s*>").find(afterMap)
                val mapPart: String
                val thinkPart: String
                if (thinkStart != null) {
                    mapPart = afterMap.substring(0, thinkStart.range.first)
                    thinkPart = afterMap.substring(thinkStart.range.last + 1)
                } else {
                    mapPart = afterMap
                    thinkPart = ""
                }
                val mapText = dropTruncatedTail(mapPart)
                if (mapText.isBlank()) null else Parsed(mapText, dropTruncatedTail(thinkPart))
            }
            // 连 <导图> 开标签都没有，导图救不回来
            else -> null
        }
    }

    /** 丢掉最后一段文字（被长度上限砍断的几乎总是最后一行/最后一句） */
    private fun dropTruncatedTail(s: String): String {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return ""
        val noFence = trimmed.removeSuffix("```").trim()
        val lastBreak = maxOf(noFence.lastIndexOf('\n'), noFence.lastIndexOf('。'), noFence.lastIndexOf('；'))
        return if (lastBreak > 0) noFence.substring(0, lastBreak + 1).trim() else ""
    }

    /** 把抢救出的内容包回标准标签格式，交给上层 parse 走同一条解析路径 */
    fun render(parsed: Parsed): String = buildString {
        if (parsed.mapText.isNotBlank()) {
            appendLine("<导图>")
            appendLine(parsed.mapText.trim())
            appendLine("</导图>")
        }
        if (parsed.thinking.isNotBlank()) {
            appendLine("<思路>")
            appendLine(parsed.thinking.trim())
            append("</思路>")
        }
    }.trim()
}
