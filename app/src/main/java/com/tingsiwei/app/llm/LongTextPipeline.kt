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
    val maxChunks: Int,
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

    /** 给用户的提示：哪些段没纳入、为什么没纳入、是否缺标点、段数是否超上限、汇总是否被截。 */
    fun notice(): String? {
        val parts = ArrayList<String>()
        if (failedSegments.isNotEmpty()) {
            parts.add(
                "第 ${failedSegments.joinToString("、") { (it + 1).toString() }} 段没能提炼成功" +
                    "${failureHint?.let { "（$it）" } ?: ""}，已按其余内容生成，可点「补全」再试"
            )
        }
        if (noBoundary) parts.add("转写文字缺少标点，分段质量受影响")
        if (chunkCapExceeded) parts.add("内容很长，已拆成 $totalSegments 段（超过预期的 $maxChunks 段），会多花些时间")
        if (finalTrimmed) parts.add("内容超出接口窗口，汇总时截掉了后半部分，建议在设置里调大「上下文窗口」或关掉保守模式")
        return if (parts.isEmpty()) null else parts.joinToString("；") + "。"
    }
}

/**
 * 长文生成流水线：语义分段 → 逐段提炼（可断点续跑）→ 最多两层归并 → 一次最终生成。
 * 预算是硬约束：分块大小、归并输入、最终输入都被压在窗口以内，压不下就宁可多分几段；
 * 真的不得不截断时（窗口小到装不下），明确告诉用户截了（finalTrimmed）。
 */
class LongTextPipeline(
    private val complete: Complete,
    private val store: SegmentStore,
    private val contextWindow: Int,
    private val conservative: Boolean = false,
) {

    val effectiveWindow: Int = LlmPolicy.windowFor(contextWindow, conservative).coerceAtLeast(1024)

    /** 最终一次生成要留给"系统提示词 + 输出"的余量 */
    private val finalReserveTokens: Int =
        minOf(FinalOutTokens + SystemPromptSlack, effectiveWindow / 2).coerceAtLeast(MergeOutTokens / 2)

    /** 最终生成的输出上限 */
    private val finalOutTokens: Int =
        (finalReserveTokens - SystemPromptSlack).coerceIn(LlmPolicy.MIN_OUTPUT_TOKENS, FinalOutTokens)

    /** 单块上限：再放大就会超窗口，只能靠多分几段 */
    val maxChunkTokens: Int = (effectiveWindow - finalReserveTokens).coerceAtLeast(256)
    val chunkTargetTokens: Int = minOf(maxChunkTokens, (effectiveWindow * 0.28).toInt().coerceAtLeast(256))
    val maxChunks: Int = if (conservative) 6 else 12

    private var segmentCalls = 0
    private var reduceCalls = 0
    private var finalCalls = 0
    private var reusedSegments = 0
    private var maxInputTokens = 0
    private var failureHint: String? = null
    private var consecutiveFatal = 0
    private var consecutiveFailed = 0

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
        consecutiveFatal = 0
        consecutiveFailed = 0

        val system = Prompts.generateSystem(allowExpand)
        if (effectiveWindow < Tokens.estimate(system) + LlmPolicy.MIN_OUTPUT_TOKENS + InputSlack) {
            throw LlmException(
                "上下文窗口太小（$effectiveWindow token），装不下提示词和输出。请在设置里调大「上下文窗口」或关掉保守模式"
            )
        }
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
                consecutiveFailed++
                // 额度耗尽/持续限流时别再往下打：最多 3 段连败就收手，成果已在断点里
                if (consecutiveFailed >= MaxConsecutiveFailedSegments) {
                    throw LlmException(
                        "连续 $consecutiveFailed 段都没能提炼成功${failureHint?.let { "（$it）" } ?: ""}" +
                            "，已停止以免继续浪费接口额度。稍后可点「补全」从断点继续"
                    )
                }
            } else {
                consecutiveFailed = 0
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
        while (!fitsBudget(level) && levels < MaxReduceLevels) {
            level = mergeLevel(level, levels + 1, title, onStage)
            levels++
        }

        val generated = finalGenerate(level, title, allowExpand, onStage)
        if (failed.isEmpty()) store.clear(noteId)
        return PipelineResult(
            mapText = generated.parsed.mapText,
            thinking = generated.parsed.thinking,
            failedSegments = failed,
            totalSegments = plan.size,
            maxChunks = maxChunks,
            reduceLevels = levels,
            noBoundary = plan.noBoundary,
            chunkCapExceeded = plan.chunkCapExceeded,
            finalTrimmed = generated.trimmed,
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
        var fatal: String? = null
        repeat(2) {
            if (fatal != null) return@repeat
            val out = try {
                segmentCalls++
                complete.call(system, user, 0.2, MergeOutTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError) {
                // KEY/地址/坏格式类错误重试没有意义，再打一次也只是浪费
                if (!e.retryable) fatal = e.message else lastError = e.message
                null
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
            if (out != null && out.isNotBlank()) {
                consecutiveFatal = 0
                return SegmentAttempt(out.trim(), null)
            }
        }
        fatal?.let { message ->
            // 连续多段都是不可重试错误 = 配置/额度问题，停手；偶发一段坏就只跳过那一段
            consecutiveFatal++
            noteFailure(message)
            if (consecutiveFatal >= MaxConsecutiveFatal) return SegmentAttempt(null, message)
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
            if (current.isNotEmpty() && tokens + t > maxChunkTokens) {
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
                complete.call(system, user, 0.2, MergeOutTokens)
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

    private class Generated(val parsed: LlmOutputParser.Parsed, val trimmed: Boolean)

    private suspend fun finalGenerate(
        levels: List<String>,
        title: String,
        allowExpand: Boolean,
        onStage: suspend (String) -> Unit,
    ): Generated {
        onStage("正在汇总生成导图…")
        val system = Prompts.generateSystem(allowExpand)
        // 归并两层仍装不下时，这里截断并如实报告——不留给下游静默截
        val inputCap = (effectiveWindow - finalReserveTokens - Tokens.estimate(system)).coerceAtLeast(256)
        val joined = levels.joinToString("\n\n")
        val (outlineText, trimmed) = if (Tokens.estimate(joined) > inputCap) {
            Tokens.truncateToTokens(joined, inputCap)
        } else {
            joined to false
        }
        val user = Prompts.finalUser(outlineText, title)
        finalCalls++
        var parsed = LlmOutputParser.parse(callTracked(system, user, 0.4, finalOutTokens))
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            finalCalls++
            parsed = LlmOutputParser.parse(
                callTracked(system, user + Prompts.formatReminder(), 0.4, finalOutTokens)
            )
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图，请在设置里换一个中文能力更强的模型再试")
        }
        return Generated(parsed, trimmed)
    }

    private suspend fun callTracked(system: String, user: String, temperature: Double, out: Int): String {
        trackInput(system, user)
        return complete.call(system, user, temperature, out)
    }

    /** 归并后的这一层，能否连同系统提示词与输出一起装进窗口 */
    private fun fitsBudget(items: List<String>): Boolean {
        val system = Prompts.generateSystem(true)
        return Tokens.estimate(items.joinToString("\n\n")) + Tokens.estimate(system) <= maxChunkTokens
    }

    private fun trackInput(system: String, user: String) {
        maxInputTokens = maxOf(maxInputTokens, Tokens.estimate(system) + Tokens.estimate(user))
    }

    private fun noteFailure(message: String) {
        if (failureHint.isNullOrBlank()) failureHint = message
    }

    companion object {
        /** maxChunks 上限下两层已足够；写成常量而非无限递归，避免层数失控。 */
        const val MaxReduceLevels = 2
        const val MergeOutTokens = 1200
        const val FinalOutTokens = 2048

        /** generateSystem 提示词 + finalUser 包装的粗略体量 */
        const val SystemPromptSlack = 700

        /** 判定"窗口太小"时额外要求的余量 */
        const val InputSlack = 512

        /** 连续几次不可重试错误就停手（单次失败只跳过那一段） */
        const val MaxConsecutiveFatal = 2

        /** 连续几段提炼不出来就收手，避免把额度打光 */
        const val MaxConsecutiveFailedSegments = 3
    }
}
