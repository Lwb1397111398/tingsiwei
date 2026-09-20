package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.CancellationException

/** 每次 LLM 调用的接缝：生产由 Generator 传入（带重试的 chat），单测传入可注入 429 的假实现。 */
fun interface Complete {
    suspend fun call(system: String, user: String, temperature: Double, desiredOutTokens: Int): String
}

class PipelineResult(
    val mapText: String,
    val thinking: String,
    val failedSegments: List<Int>,
    val totalSegments: Int,
    val reduceLevels: Int,
    val noBoundary: Boolean,
    val stats: Stats,
) {
    class Stats(
        val segmentCalls: Int,
        val reduceCalls: Int,
        val finalCalls: Int,
        val reusedSegments: Int,
        val maxInputTokens: Int,
    ) {
        val totalCalls: Int get() = segmentCalls + reduceCalls + finalCalls
    }

    /** 给用户的提示：哪些段没纳入、是否缺标点、是否用了断点成果。 */
    fun notice(): String? {
        val parts = ArrayList<String>()
        if (failedSegments.isNotEmpty()) {
            parts.add("第 ${failedSegments.joinToString("、") { (it + 1).toString() }} 段接口没响应成功，已按其余内容生成")
        }
        if (noBoundary) parts.add("转写文字缺少标点，分段质量受影响")
        return if (parts.isEmpty()) null else parts.joinToString("；") + "。"
    }
}

/**
 * 长文生成流水线：语义分段 → 逐段提炼（可断点续跑）→ 最多两层归并 → 一次最终生成。
 * 每一次调用的输入都被压在窗口预算内，且归并失败时原样上收，绝不丢内容。
 */
class LongTextPipeline(
    private val complete: Complete,
    private val store: SegmentStore,
    private val contextWindow: Int,
    private val conservative: Boolean = false,
) {

    val effectiveWindow: Int = if (conservative) contextWindow / 2 else contextWindow
    val chunkTargetTokens: Int = (effectiveWindow * 0.28).toInt().coerceIn(500, 3000)
    val maxChunks: Int = if (conservative) 6 else 12

    private val finalReserveTokens = 2600
    private val mergeGroupBudget get() = (effectiveWindow - finalReserveTokens).coerceAtLeast(1200)

    private var segmentCalls = 0
    private var reduceCalls = 0
    private var finalCalls = 0
    private var reusedSegments = 0
    private var maxInputTokens = 0

    /** 短到一次调用就能装下时，不必走分段流水线。 */
    fun useDirectRoute(content: String, systemPrompt: String, desiredOutTokens: Int): Boolean =
        Tokens.estimate(content) + Tokens.estimate(systemPrompt) + desiredOutTokens <= effectiveWindow

    suspend fun run(
        noteId: Long,
        content: String,
        title: String,
        allowExpand: Boolean,
        onStage: suspend (String) -> Unit = {},
    ): PipelineResult {
        segmentCalls = 0; reduceCalls = 0; finalCalls = 0; reusedSegments = 0; maxInputTokens = 0
        val plan = TextChunker.plan(content, chunkTargetTokens, maxChunks)
        val fp = SegmentStore.fingerprint(content, "$chunkTargetTokens|$maxChunks|$effectiveWindow")
        val done = HashMap(store.load(noteId, fp))
        val outlines = LinkedHashMap<Int, String>()
        val failed = ArrayList<Int>()

        for (i in plan.chunks.indices) {
            val cached = done[i]
            if (cached != null) {
                reusedSegments++
                outlines[i] = cached
                onStage("已复用第 ${i + 1}/${plan.size} 段的断点成果")
                continue
            }
            onStage("正在提炼第 ${i + 1}/${plan.size} 段…")
            val text = askOutline(plan.chunks[i].text, i + 1, plan.size)
            if (text == null) failed.add(i) else {
                done[i] = text
                outlines[i] = text
                store.save(noteId, fp, done)
            }
        }
        if (outlines.isEmpty()) throw LlmException("每一段都没能提炼成功，请检查接口额度，或在设置里开启保守模式后重试")

        var level = outlines.keys.sorted().mapNotNull { outlines[it] }
        var levels = 0
        while (!fitsFinal(level) && levels < MaxReduceLevels) {
            level = mergeLevel(level, levels + 1, onStage)
            levels++
        }
        check(levels <= MaxReduceLevels) { "归并层数不应超过 $MaxReduceLevels 层" }

        val parsed = finalGenerate(level.joinToString("\n\n"), title, allowExpand, onStage)
        if (failed.isEmpty()) store.clear(noteId)
        return PipelineResult(
            mapText = parsed.mapText,
            thinking = parsed.thinking,
            failedSegments = failed,
            totalSegments = plan.size,
            reduceLevels = levels,
            noBoundary = plan.noBoundary,
            stats = PipelineResult.Stats(segmentCalls, reduceCalls, finalCalls, reusedSegments, maxInputTokens),
        )
    }

    private suspend fun askOutline(chunk: String, seq: Int, total: Int): String? {
        val system = Prompts.outlineSystem()
        val user = Prompts.outlineUser(chunk, seq, total)
        trackInput(system, user)
        repeat(2) {
            val out = try {
                segmentCalls++
                complete.call(system, user, 0.2, 900)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@repeat
            if (out.isNotBlank()) return out.trim()
        }
        return null
    }

    private suspend fun mergeLevel(items: List<String>, level: Int, onStage: suspend (String) -> Unit): List<String> {
        val groups = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var tokens = 0
        for (item in items) {
            val t = Tokens.estimate(item)
            if (current.isNotEmpty() && tokens + t > mergeGroupBudget) {
                groups.add(current)
                current = ArrayList()
                tokens = 0
            }
            current.add(item)
            tokens += t
        }
        if (current.isNotEmpty()) groups.add(current)
        if (groups.size <= 1) return items

        val system = Prompts.groupMergeSystem()
        val out = ArrayList<String>()
        groups.forEachIndexed { i, g ->
            onStage("正在汇总第 ${i + 1}/${groups.size} 组（第 $level 层）…")
            val user = Prompts.groupMergeUser(g, "")
            trackInput(system, user)
            reduceCalls++
            val merged = try {
                complete.call(system, user, 0.2, 1200)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            // 归并失败就把这一组原样上收，内容不能丢
            out.add(merged.trim().ifBlank { g.joinToString("\n\n") })
        }
        return out
    }

    private suspend fun finalGenerate(
        outlineText: String,
        title: String,
        allowExpand: Boolean,
        onStage: suspend (String) -> Unit,
    ): LlmOutputParser.Parsed {
        onStage("正在汇总生成导图…")
        val system = Prompts.generateSystem(allowExpand)
        val user = Prompts.finalUser(outlineText, title)
        trackInput(system, user)
        finalCalls++
        var parsed = LlmOutputParser.parse(call(system, user, 0.4, 2048))
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            finalCalls++
            parsed = LlmOutputParser.parse(
                call(
                    system,
                    user + "\n\n（注意：上一次输出格式不对。请严格输出 <导图>…</导图> 与 <思路>…</思路> 两部分，导图用 TAB 缩进。）",
                    0.4,
                    2048,
                )
            )
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图，请在设置里换一个中文能力更强的模型再试")
        }
        return parsed
    }

    private suspend fun call(system: String, user: String, temperature: Double, out: Int): String {
        trackInput(system, user)
        return complete.call(system, user, temperature, out)
    }

    private fun fitsFinal(items: List<String>): Boolean =
        Tokens.estimate(items.joinToString("\n\n")) + finalReserveTokens <= effectiveWindow

    private fun trackInput(system: String, user: String) {
        maxInputTokens = maxOf(maxInputTokens, Tokens.estimate(system) + Tokens.estimate(user))
    }

    companion object {
        /** maxChunks 上限下两层已足够；写成常量而非无限递归，避免层数失控。 */
        const val MaxReduceLevels = 2
    }
}
