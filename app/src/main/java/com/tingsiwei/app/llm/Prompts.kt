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

    /** 三段式第一步：通读原文，起草思路（忠实原文，先不拓展） */
    fun draftThinkingSystem(): String = """
你是一名擅长梳理学习内容的学习助手。用户给你一段录音转写文字，请通读全文，把讲解的思路整理成一份草稿：
- 按讲解顺序展开，写清核心概念、关键例子和结论之间的逻辑，多用「因为……所以……」「在此基础上……」这类承接
- 只梳理原文实际讲到的内容，不要补充外部知识
- 用连贯的自然段书写（不要列表、不要标题），尽量完整，篇幅可以到一千字左右
- 输出必须严格遵守此格式（标签独占一行，不要套代码块）：
<思路>
草稿正文
</思路>
""".trim()

    fun draftThinkingUser(content: String): String = "请通读以下内容，整理出思路草稿：\n\n$content"

    /** 三段式第二步：对照原文自查优化，按需拓展，产出定稿思路 */
    fun refineThinkingSystem(allowExpand: Boolean): String {
        val expandRule = if (allowExpand)
            "拓展：原文没讲到、但对理解这个主题确有必要的内容（背景、前提、知识之间的联系），直接补写进思路，并在对应句子末尾标注「（拓展）」；拓展篇幅不超过原文的三分之一，不得偏离主题。"
        else
            "只优化原文内容的表达与结构，不要补充原文没有的知识，也不要标注拓展。"
        return """
你刚整理了一份思路草稿，现在请对照原始内容自查，产出定稿思路：
1. 自查：要点有没有遗漏、顺序对不对、逻辑是否连贯、概念有没有说清；
2. 重写一份更好的思路：连贯自然段（不要列表），按讲解顺序串联，多用「因为……所以……」这类承接；
3. $expandRule
输出必须严格遵守此格式（标签独占一行，不要套代码块），不要输出自查过程：
<思路>
定稿思路
</思路>
""".trim()
    }

    fun refineThinkingUser(draft: String, content: String?): String = buildString {
        appendLine("【思路草稿】")
        appendLine(draft.trim())
        if (!content.isNullOrBlank()) {
            appendLine()
            appendLine("【原始内容】")
            append(content.trim())
        }
        appendLine()
        append("请自查、优化并输出定稿思路。")
    }.trim()

    /** 长文流水线的优化步：输入是缩进大纲，输出仍是缩进大纲 */
    fun refineOutlineSystem(allowExpand: Boolean): String {
        val expandRule = if (allowExpand)
            "拓展：原文没讲到、但对理解确有必要的内容可以直接补进大纲，行尾标注「（拓展）」；新增行数不超过原大纲的三分之一，不得偏离主题。"
        else
            "只优化大纲的结构与措辞，不要补充没有的知识。"
        return """
下面是一份按录音顺序提炼的缩进大纲（TAB 缩进表示层级），请自查并优化成定稿大纲：
- 检查要点有没有遗漏、归组是否合理、层级是否清晰，修正后完整输出
- 保留全部关键术语、数字、法条名、例子和结论，不得丢内容；保持原有先后顺序
- $expandRule
- 输出格式与输入相同：TAB 缩进大纲，第一行保留「小节：主题概括」，不要输出解释、标签或代码块
""".trim()
    }

    fun refineOutlineUser(outlineText: String, title: String): String = buildString {
        if (title.isNotBlank()) appendLine("整体主题提示：${title.trim()}").appendLine()
        appendLine("【原大纲】")
        append(outlineText.trim())
        appendLine()
        append("请自查、优化并输出定稿大纲。")
    }.trim()

    /** 三段式第三步：只根据定稿思路生成导图，思路不再重写，保证图文一致 */
    fun mapFromThinkingSystem(): String = """
你是一名擅长把内容整理成知识结构的学习助手。用户给你一份已经定稿的思路，请把它整理成层级思维导图：
- 第一行是总主题（内容的核心话题）
- 节点文字精炼，一般不超过 20 个字，只留关键词，不要整句照搬
- 层级按思路的逻辑关系组织，同级节点按思路中出现的顺序排列
- 思路中标注了「（拓展）」的内容同样整理进导图，并在对应拓展分支的最上层节点末尾加「（拓展）」
- 不要新增思路里没有的内容，不要输出思路
输出必须严格遵守此格式（标签独占一行，不要套代码块）：
<导图>
总主题
	子主题
		细节
</导图>
""".trim()

    fun mapFromThinkingUser(thinking: String, title: String): String = buildString {
        if (title.isNotBlank()) appendLine("这次内容的主题（来自录音标题）：${title.trim()}").appendLine()
        appendLine("【定稿思路】")
        appendLine(thinking.trim())
        appendLine()
        append("请把这份思路整理成思维导图。")
    }.trim()

    /** 只产出导图时的格式纠正提示（提到思路反而会诱导模型把思路也写一遍） */
    fun mapOnlyReminder(): String =
        "\n\n（注意：上一次输出格式不对。请只输出 <导图>…</导图> 一部分，导图用 TAB 缩进，不要输出思路或其他内容。）"

    /** 最小化改写：模型只输出编辑指令，程序本地应用，未点名节点逐字保留 */
    fun reviseDiffSystem(): String = """
你是学习助手的「编辑模式」。给你一张带路径编号的思维导图（每行开头的 1、1.2、2.3.1 是节点地址）和当前思路，请按修改要求做**最小化编辑**：
- 只输出修改指令，一行一条，放在 <修改> 标签里：
改 <路径> = 新的节点文字
删 <路径>
加 <路径> = 新增子节点文字
- 路径必须来自给定编号，指向修改前的树；「删」会连同子树一起删除
- 重画某棵子树：先逐条「删」它的子节点，再逐条「加」新子节点
- 未被指令点名的节点会逐字保留——绝不要顺手润色、合并或重排
- 思路基本不用动；确需同步时，在 </修改> 之后另起 <思路>…</思路> 给出整段新文字
- 只有整体重构级别的改动，才放弃指令、改为输出完整 <导图>…</导图> 与 <思路>…</思路>
- 不要输出解释、不要套代码块
""".trim()

    fun reviseDiffUser(numberedMap: String, thinking: String, suggestion: String): String = buildString {
        appendLine("【当前思维导图（行首为节点路径编号）】")
        appendLine(numberedMap)
        if (thinking.isNotBlank()) {
            appendLine()
            appendLine("【当前思路】")
            appendLine(thinking)
        }
        appendLine()
        appendLine("【我的修改要求】")
        appendLine(suggestion)
        append("请按最小化编辑输出 <修改> 指令块。")
    }.trim()

    /** diff 输出没法应用时的纠正提示 */
    fun diffReminder(): String =
        "\n\n（注意：上一次的输出无法应用。请只输出 <修改>…</修改> 指令块，每行形如「改 <路径> = 新文字」「删 <路径>」「加 <路径> = 新文字」；确属整体重构才输出完整 <导图>…</导图> 与 <思路>…</思路>。）"

    /** diff 输出被截断时的降级规则：指令继续压缩，压不下去就升级全量 */
    fun diffTrimRule(): String =
        "体量要求：指令行最多 20 条；确实需要更多改动，请改为输出完整 <导图>…</导图> 与 <思路>…</思路>。"

    /** 全量兜底路径的硬规则：只改建议涉及的部分，其余逐字保留 */
    fun majorKeepRule(): String =
        "修改纪律：只按我的要求修改涉及的节点与思路，其余节点逐字保留，不得顺手润色或调整层级。"

    /** 按用户要求修改现有导图（全量重发的兜底路径） */
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
        appendLine("请按我的要求修改思维导图和思路（可以增删节点、调整层级、改写措辞、重写思路），保持上述输出格式，完整输出修改后的全部内容。思路与导图中标注「（拓展）」的内容，如我没有要求删除请保留标注。")
    }
}
