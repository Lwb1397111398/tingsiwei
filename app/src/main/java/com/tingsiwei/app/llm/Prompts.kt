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

    /** 输出被截断时的降级规则：让模型主动压缩体量，而不是硬截。 */
    fun trimRule(): String =
        "体量要求：导图每个节点不超过 12 个字，思路控制在 250 字以内；节点宁少勿断，标签必须闭合。"

    /** 模型没按 <导图>/<思路> 格式返回时的纠正提示 */
    fun formatReminder(): String =
        "\n\n（注意：上一次输出格式不对。请严格输出 <导图>…</导图> 与 <思路>…</思路> 两部分，导图用 TAB 缩进。）"

    /** 单段提炼：小节标题 + TAB 大纲 */
    fun outlineSystem(): String = """
你是知识提炼助手。用户给你一段较长录音中的其中一小段转写文字，请提炼成可供后续合并的大纲：
第一行输出「小节：<不超过 12 字的主题概括>」
之后用 TAB 缩进层级列出这段讲到的要点：
- 保留关键术语、数字、法条名、例子和结论，去掉口水话和重复
- 只提炼这段里实际讲到的内容，不要补充没讲到的知识
- 不要输出思路、不要输出任何标签或解释、不要输出代码块
""".trim()

    fun outlineUser(chunk: String, seq: Int, total: Int): String =
        "这是全文第 $seq/$total 小段（前后另有内容，只需提炼本段）：\n\n$chunk"

    /** 多份小节大纲 → 一份中层大纲（递归 reduce 的一层） */
    fun groupMergeSystem(): String = """
以下是同一次录音按顺序分小段提炼出的多份大纲。请把相邻内容归并成一份更高层的大纲：
- 第一行输出「小节：<整体概括>」
- 用 TAB 缩进层级，合并同类项、去掉重复，但不得丢掉术语、数字、法条名和结论
- 保持原有先后顺序，不要输出解释、标签或代码块
""".trim()

    fun groupMergeUser(outlines: List<String>, hint: String): String = buildString {
        if (hint.isNotBlank()) appendLine("整体主题提示：$hint").appendLine()
        appendLine("共 ${outlines.size} 份小节大纲，按顺序如下：")
        outlines.forEachIndexed { i, o -> appendLine(); appendLine("〔${i + 1}〕").appendLine(o.trim()) }
    }.trim()

    /** 最终生成：输入已经是提炼过的大纲（而非原始长文） */
    fun finalUser(outlineText: String, title: String): String = buildString {
        if (title.isNotBlank()) appendLine("这次内容的主题（来自录音标题）：${title.trim()}").appendLine()
        appendLine("下面是按录音顺序提炼的小节大纲，请据此产出完整的导图与思路：")
        appendLine()
        append(outlineText.trim())
    }.trim()

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
