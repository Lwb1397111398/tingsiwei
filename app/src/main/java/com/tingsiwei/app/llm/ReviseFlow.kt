package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText

/** 改写结果：Success 交给 Generator 写库；Fail 的 reason 由 Generator 包「修改失败：…」前缀 */
sealed class ReviseResult {
    class Success(val forest: List<TreeNote>, val thinking: String?, val notice: String?) : ReviseResult()
    class Fail(val reason: String) : ReviseResult()
}

/**
 * 改写的纯决策层（仿 StagedFlow，不碰接口与库，测试注入假 [Ask]）：
 * diff 主问 → 提醒重问 → 全量降级；diff 模式截断按行抢救。
 * 最多两次 diff 问 + 一次全量问；任何失败都不产出半截导图。
 */
internal object ReviseFlow {

    interface Ask {
        /** diff 模式：chatWithDegrade 的降级规则用 Prompts.diffTrimRule() */
        suspend fun diff(system: String, user: String): String

        /** 全量兜底：现行"整图重发"，默认体量规则 */
        suspend fun full(system: String, user: String): String
    }

    data class Context(
        val forest: List<TreeNote>,
        val thinking: String,
        val originalContent: String,
        val suggestion: String,
        /** 全量兜底的 system：generateSystem(allowExpand) + majorKeepRule，由 Generator 拼 */
        val fullSystem: String,
    )

    suspend fun run(ctx: Context, ask: Ask): ReviseResult {
        val system = Prompts.reviseDiffSystem()
        val user = Prompts.reviseDiffUser(MapDiffText.render(ctx.forest), ctx.thinking, ctx.suggestion)

        var (decision, salvage) = askSalvaged(ask, system, user)
        if (decision.mode == ReviseOutput.Mode.Invalid) {
            val retry = askSalvaged(ask, system, user + Prompts.diffReminder())
            decision = retry.first
            salvage = salvage || retry.second
        }
        // 半截整图绝不覆盖完好旧图：只有逐行原子的 diff 才允许抢救
        if (salvage && decision.mode == ReviseOutput.Mode.Full) return fallback(ctx, ask)

        return when (decision.mode) {
            ReviseOutput.Mode.Diff -> applyDiff(ctx, ask, decision, salvage)
            ReviseOutput.Mode.Full -> acceptFull(decision.parsed, salvage) ?: fallback(ctx, ask)
            ReviseOutput.Mode.Invalid -> fallback(ctx, ask)
        }
    }

    /** 问一次 diff；被截断则丢掉未闭合尾行按完整行抢救，返回（判定, 是否抢救） */
    private suspend fun askSalvaged(ask: Ask, system: String, user: String): Pair<ReviseOutput.Decision, Boolean> =
        try {
            ReviseOutput.classify(ask.diff(system, user)) to false
        } catch (e: LlmError.Truncated) {
            ReviseOutput.classify(dropIncompleteLastLine(e.partialContent)) to true
        }

    private suspend fun applyDiff(
        ctx: Context,
        ask: Ask,
        decision: ReviseOutput.Decision,
        salvage: Boolean,
    ): ReviseResult {
        val (ops, badLines) = MapEdit.parseAll(decision.editBlock)
        if (ops.isEmpty()) return fallback(ctx, ask)
        val applied = MapEditApplier.apply(ctx.forest, ops) ?: return fallback(ctx, ask)
        return ReviseResult.Success(
            applied.forest,
            decision.parsed.thinking.ifBlank { null },
            buildNotice(applied.report, badLines, salvage),
        )
    }

    private fun acceptFull(parsed: LlmOutputParser.Parsed, salvage: Boolean): ReviseResult? {
        val forest = TreeText.parse(parsed.mapText)
        if (forest.isEmpty()) return null
        return ReviseResult.Success(
            forest,
            parsed.thinking.ifBlank { null },
            if (salvage) "输出被长度上限截断，已按完整部分应用，建议检查导图是否完整" else null,
        )
    }

    /** 全量兜底：现行"整图重发"，system 带逐字保留硬规则 */
    private suspend fun fallback(ctx: Context, ask: Ask): ReviseResult {
        val user = Prompts.reviseUser(
            TreeText.serialize(ctx.forest), ctx.thinking, ctx.originalContent, ctx.suggestion,
        )
        val raw = try {
            ask.full(ctx.fullSystem, user)
        } catch (e: LlmError.Truncated) {
            return ReviseResult.Fail("本次改动输出太长被截断。可以把要求拆小一点再试")
        }
        val parsed = LlmOutputParser.parse(raw)
        val forest = TreeText.parse(parsed.mapText)
        if (forest.isEmpty()) return ReviseResult.Fail("AI 返回的导图是空的，请重试或换个说法")
        return ReviseResult.Success(forest, parsed.thinking.ifBlank { null }, null)
    }

    /** 截断输出的尾行几乎总是没写完：丢掉未闭合尾行，其余完整指令行照常可用 */
    private fun dropIncompleteLastLine(partial: String): String {
        if (partial.isEmpty() || partial.endsWith("\n")) return partial
        val cut = partial.lastIndexOf('\n')
        return if (cut < 0) "" else partial.substring(0, cut + 1)
    }

    private fun buildNotice(report: MapEditApplier.Report, badLines: Int, salvage: Boolean): String? {
        val problems = ArrayList<String>()
        val skipped = report.ignored + badLines
        if (skipped > 0) problems.add("$skipped 处位置没对上或被忽略")
        if (salvage) problems.add("输出被截断，未送达的指令没有应用")
        if (problems.isEmpty()) return null
        return "本次应用 ${report.applied} 处修改，${problems.joinToString("，")}"
    }
}
