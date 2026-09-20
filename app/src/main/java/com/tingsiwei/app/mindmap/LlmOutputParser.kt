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
}
