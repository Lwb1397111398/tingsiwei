package com.tingsiwei.app.llm

/** LLM 提示词。输出格式：<导图>TAB缩进树</导图> + <思路>记忆叙述</思路> */
object Prompts {

    fun generateSystem(allowExpand: Boolean): String {
        val expandRule = if (allowExpand)
            "在忠实原文的基础上，可以把与主题直接相关的知识自然补充进导图（与原文内容融合，不必标注哪些是拓展），但不得偏离原文主题。"
        else
            "只整理原文中实际讲到的内容，不要补充原文没有的知识。"
        return """
你是一名擅长把口语内容整理成知识结构的学习助手。用户给你一段录音转写文字或笔记，请完成两件事：

1. 思维导图：整理成层级导图，格式为「一行一个节点，用 TAB 缩进表示层级」：
- 第一行是总主题（内容的核心话题）
- 节点文字精炼，一般不超过 20 个字
- 同级节点按讲解顺序排列，层级按逻辑关系组织
- $expandRule
2. 思路：写一段 200~500 字的连贯自然段（不要列表），像讲故事一样把导图内容按顺序串联起来，多用「因为……所以……」「在此基础上……」这类承接，用于帮助记忆背诵。

输出必须严格遵守此格式（标签独占一行，不要套代码块）：
<导图>
总主题
	子主题
		细节
</导图>
<思路>
一段连贯的记忆叙述
</思路>
""".trim()
    }

    fun generateUser(content: String): String = "请整理以下内容：\n\n$content"

    /** 长文分块：提炼单段大纲 */
    fun chunkSystem(): String = """
你是知识提炼助手。请把用户给的这段录音转写文字提炼成层级大纲：
- 一行一个要点，用 TAB 缩进表示层级
- 只提炼这段里实际讲到的内容，保留关键数字、术语和例子
- 不要输出思路，不要输出任何标签或解释
""".trim()

    fun chunkUser(chunk: String): String = chunk

    /** 长文分块：合并多段大纲 */
    fun mergeSystem(allowExpand: Boolean): String = """
以下是同一次录音/文章分段提炼出的多份大纲。请把它们合并、去重、整理成一份完整的思维导图和记忆思路。

${generateSystem(allowExpand)}
""".trim()

    fun mergeUser(outlines: String, hint: String): String =
        (if (hint.isNotBlank()) "整体主题提示：$hint\n\n" else "") + "分段大纲如下：\n\n$outlines"

    /** 按用户要求修改现有导图 */
    fun reviseUser(
        currentMap: String,
        currentThinking: String,
        originalContent: String,
        suggestion: String,
    ): String = buildString {
        appendLine("【当前思维导图】")
        appendLine(currentMap)
        appendLine()
        appendLine("【当前思路】")
        appendLine(currentThinking)
        if (originalContent.isNotBlank()) {
            appendLine()
            appendLine("【原始内容（供参考，可截断）】")
            appendLine(originalContent.take(6000))
        }
        appendLine()
        appendLine("【我的要求】")
        appendLine(suggestion)
        appendLine()
        appendLine("请按我的要求修改思维导图和思路（可以增删节点、调整层级、改写措辞、重写思路），保持上述输出格式，完整输出修改后的全部内容。")
    }
}
