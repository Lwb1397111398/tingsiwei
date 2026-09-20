package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.CancellationException

/** 每次 LLM 调用的接缝：生产由 Generator 传入（带重试与预算的 chat），单测传入可注入故障的假实现。 */
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
    val chunkCapExceeded: Boolean,
    val finalTrimmed: Boolean,
    val failureHint: String?,
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

    /** 给用户的提示：哪些段没纳入、为什么没纳入、是否缺标点、是否用了断点成果。 */
    fun notice(): String? {
        val parts = ArrayList<String>()
        if (failedSegments.isNotEmpty()) {
            parts.add("第 ${failedSegments.joinToString("、") { (it + 1).toString() }} 段没能提炼成功${failureHint?.let { "（$it）" } ?: ""}，已按其余内容生成，可点「补全」再试")
        }
        if (noBoundary) parts.add("转写文字缺少标点，分段质量受影响")
        if (finalTrimmed) parts.add("内容超出接口窗口，汇总时截掉了后半部分，建议在设置里调大「上下文窗口」")
        return if (parts.isEmpty()) null else parts.joinToString("；") + "。"
    }
}

/**
 * 长文生成流水线：语义分段 → 逐段提炼（可断点续跑）→ 最多两层归并 → 一次最终生成。
 * 分块大小与归并输入都被压在窗口预算内；预算不够时宁可多分几段，也不让下游静默截断内容。
 */
class LongTextPipeline(
    private val complete: Complete,
    private val store: SegmentStore,
    private val contextWindow: Int,
    private val conservative: Boolean = false,
) {

    val effectiveWindow: Int = LlmPolicy.windowFor(contextWindow, conservative).coerceAtLeast(1024)

    /** 最终生成要留给系统提示词 + 输出的余量 */
    private val finalReserveTokens: Int =
        minOf(2600, effectiveWindow / 2).coerceAtLeast(LlmPolicy.MIN_OUTPUT_TOKENS)

    /** 单块上限：再放大就会超窗口，只能靠多分几段 */
    val maxChunkTokens: Int = (effectiveWindow - finalReserveTokens).coerceAtLeast(256)
    val chunkTargetTokens: Int = minOf(maxChunkTokens, (effectiveWindow * 0.28).toInt().coerceAtLeast(256))
    val maxChunks: Int = if (conservative) 6 else 12
    private val mergeGroupBudget: Int get() = maxChunkTokens

    private var segmentCalls = 0
    private var reduceCalls = 0
    private var finalCalls = 0
    private var reusedSegments = 0
    private var maxInputTokens = 0
    private var failureHint: String? = null

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
        failureHint = null

        val plan = TextChunker.plan(content, chunkTargetTokens, maxChunks, maxChunkTokens)
        val fp = SegmentStore.fingerprint(content, "$chunkTargetTokens|$maxChunks|$maxChunkTokens")
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
            val attempt = askOutline(plan.chunks[i].text, i + 1, plan.size)
            attempt.fatalMessage?.let { throw LlmException(it) }
            val text = attempt.text
            if (text == null) {
                failed.add(i)
            } else {
                done[i] = text
                outlines[i] = text
                store.save(noteId, fp, done)
            }
        }
        if (outlines.isEmpty()) {
            throw LlmException("每一段都没能提炼成功${failureHint?.let { "：$it" } ?: ""}，请检查接口额度，或在设置里开启保守模式后重试")
        }

        var level = outlines.keys.sorted().mapNotNull { outlines[it] }
        var levels = 0
        while (!fitsFinal(level) && levels < MaxReduceLevels) {
            level = mergeLevel(level, levels + 1, title, onStage)
            levels++
        }

        var merged = level.joinToString("\n\n")
        val cap = maxChunkTokens
        var trimmed = false
        if (Tokens.estimate(merged) > cap) {
            merged = Tokens.truncateToTokens(merged, cap).first
            trimmed = true
        }

        val parsed = finalGenerate(merged, title, allowExpand, onStage)
        if (failed.isEmpty()) store.clear(noteId)
        return PipelineResult(
            mapText = parsed.mapText,
            thinking = parsed.thinking,
            failedSegments = failed,
            totalSegments = plan.size,
            reduceLevels = levels,
            noBoundary = plan.noBoundary,
            chunkCapExceeded = plan.chunkCapExceeded,
            finalTrimmed = trimmed,
            failureHint = failureHint,
            stats = PipelineResult.Stats(segmentCalls, reduceCalls, finalCalls, reusedSegments, maxInputTokens),
        )
    }

    private class SegmentAttempt(val text: String?, val fatalMessage: String?)

    private suspend fun askOutline(chunk: String, seq: Int, total: Int): SegmentAttempt {
        val system = Prompts.outlineSystem()
        val user = Prompts.outlineUser(chunk, seq, total)
        trackInput(system, user)
        var lastError: String? = null
        repeat(2) {
            val out = try {
                segmentCalls++
                complete.call(system, user, 0.2, OutlineOutTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError.Reject) {
                // KEY 不对 / 地址不对 / 没余额：继续跑剩下的段只是浪费时间
                return SegmentAttempt(null, e.message)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
            if (out != null && out.isNotBlank()) return SegmentAttempt(out.trim(), null)
        }
        lastError?.let { noteFailure(it) }
        return SegmentAttempt(null, null)
    }

    private suspend fun mergeLevel(
        items: List<String>,
        level: Int,
        title: String,
        onStage: suspend (String) -> Unit,
    ): List<String> {
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
            val user = Prompts.groupMergeUser(g, title)
            trackInput(system, user)
            reduceCalls++
            val merged = try {
                complete.call(system, user, 0.2, ReduceOutTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteFailure(e.message ?: e.javaClass.simpleName)
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
        finalCalls++
        var parsed = LlmOutputParser.parse(callTracked(system, user, 0.4, FinalOutTokens))
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            finalCalls++
            parsed = LlmOutputParser.parse(
                callTracked(system, user + Prompts.formatReminder(), 0.4, FinalOutTokens)
            )
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图，请在设置里换一个中文能力更强的模型再试")
        }
        return parsed
    }

    private suspend fun callTracked(system: String, user: String, temperature: Double, out: Int): String {
        trackInput(system, user)
        return complete.call(system, user, temperature, out)
    }

    private fun fitsFinal(items: List<String>): Boolean =
        Tokens.estimate(items.joinToString("\n\n")) <= maxChunkTokens

    private fun trackInput(system: String, user: String) {
        maxInputTokens = maxOf(maxInputTokens, Tokens.estimate(system) + Tokens.estimate(user))
    }

    private fun noteFailure(message: String) {
        if (failureHint.isNullOrBlank()) failureHint = message
    }

    companion object {
        /** maxChunks 上限下两层已足够；写成常量而非无限递归，避免层数失控。 */
        const val MaxReduceLevels = 2
        const val OutlineOutTokens = 900
        const val ReduceOutTokens = 1200
        const val FinalOutTokens = 2048
    }
}
