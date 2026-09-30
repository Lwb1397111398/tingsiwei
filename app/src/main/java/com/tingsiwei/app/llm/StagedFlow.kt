package com.tingsiwei.app.llm

/**
 * 三段式生成的纯逻辑部分：预算判定与思路提取，不碰接口，便于单测。
 * 三段 = 起草思路 → 自查优化（按需拓展）→ 按思路出导图。
 */
internal object StagedFlow {

    /** 第一步起草思路的输出上限（推理模型思维链计入，太小连"想"都装不下） */
    const val DraftOutTokens = 6144

    /** 第二步优化拓展的输出上限：拓展允许比草稿更长 */
    const val RefineOutTokens = 8192

    /** 每步系统提示词与包装文字的粗略余量 */
    const val StageSlack = 600

    /**
     * 窗口装得下三段式里最贵的一步才走三段式：
     * 起草 = 原文 + 提示 + 草稿输出；优化 = 原文 + 草稿 + 提示 + 定稿输出；出图 = 定稿思路 + 提示 + 导图输出。
     * 装不下就退回一次成稿（或长文流水线），不要让中间某步被接口静默截输入。
     */
    fun fits(window: Int, contentTokens: Int): Boolean = maxOf(
        contentTokens + StageSlack + DraftOutTokens,
        contentTokens + DraftOutTokens + StageSlack + RefineOutTokens,
        RefineOutTokens + StageSlack + LongTextPipeline.FinalOutTokens,
    ) <= window

    /**
     * 从模型输出里提取 <思路> 正文。草稿/定稿都要求包标签输出，
     * 但推理模型可能把思维链一起吐出来、或截断时标签没闭合——这里都尽量带回正文。
     */
    fun thinkingText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        Regex("<\\s*思路\\s*>([\\s\\S]*?)<\\s*/\\s*思路\\s*>").find(text)?.let {
            return it.groupValues[1].trim()
        }
        // 开标签在、闭标签被截掉：取标签后的正文，丢掉被砍断的尾巴
        Regex("<\\s*思路\\s*>").find(text)?.let {
            val body = text.substring(it.range.last + 1).trim()
            if (body.isNotEmpty()) return dropTruncatedTail(body)
        }
        // 根本没按格式输出：整段当思路（宁可要一段带杂释的文字，也不要空白）
        return dropTruncatedTail(text)
    }

    /** 被长度上限砍断的几乎总是最后一句：退到最近的句读 */
    private fun dropTruncatedTail(s: String): String {
        val trimmed = s.trim().removeSuffix("```").trim()
        if (trimmed.isEmpty()) return ""
        val lastBreak = maxOf(trimmed.lastIndexOf('\n'), trimmed.lastIndexOf('。'), trimmed.lastIndexOf('；'))
        return if (lastBreak > 0) trimmed.substring(0, lastBreak + 1).trim() else trimmed
    }
}
