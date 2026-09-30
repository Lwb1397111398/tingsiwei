package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.CancellationException

/** 每次 LLM 调用的接缝：生产由 Generator 传入（带重试与预算的 chat），单测传入可注入故障的假实现。 */
fun interface Complete {
    suspend fun call(system: String, user: String, desiredOutTokens: Int): String
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
    /** 最终生成被截断但已抢救出部分导图：给用户一句提示，别让他以为是完整版 */
    val outputTruncated: Boolean = false,
) {
    class Stats(
        val segmentCalls: Int,
        val reduceCalls: Int,
        val finalCalls: Int,
        val reusedSegments: Int,
        val maxInputTokens: Int,
        val reusedMergeGroups: Int = 0,
        val refineCalls: Int = 0,
    ) {
        val totalCalls: Int get() = segmentCalls + reduceCalls + refineCalls + finalCalls
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
        if (outputTruncated) parts.add("最终输出超出长度上限，已尽量保留生成的导图，可在导图页检查并让 AI 补全")
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
    private var refineCalls = 0
    private var finalCalls = 0
    private var reusedSegments = 0
    private var reusedMergeGroups = 0
    private var maxInputTokens = 0
    private var failureHint: String? = null
    private var consecutiveFatal = 0
    private var consecutiveFailed = 0
    private var outputTruncated = false

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
        segmentCalls = 0; reduceCalls = 0; refineCalls = 0; finalCalls = 0; reusedSegments = 0; reusedMergeGroups = 0
        maxInputTokens = 0
        failureHint = null
        consecutiveFatal = 0
        consecutiveFailed = 0
        outputTruncated = false

        val system = Prompts.generateSystem(allowExpand)
        if (effectiveWindow < Tokens.estimate(system) + LlmPolicy.MIN_OUTPUT_TOKENS + InputSlack) {
            throw LlmException(
                "上下文窗口太小（$effectiveWindow token），装不下提示词和输出。请在设置里调大「上下文窗口」或关掉保守模式"
            )
        }
        val plan = TextChunker.plan(content, chunkTargetTokens, maxChunks, maxChunkTokens)
        val fp = SegmentStore.fingerprint(content, "$chunkTargetTokens|$maxChunks|$maxChunkTokens")
        val snap = store.load(noteId, fp)
        val done = HashMap(snap.segments)
        val mergeCache = HashMap(snap.merge)
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
                store.save(noteId, fp, done, mergeCache)
            }
        }
        if (outlines.isEmpty()) {
            throw LlmException("每一段都没能提炼成功${failureHint?.let { "：$it" } ?: ""}，请检查接口额度，或在设置里开启保守模式后重试")
        }

        var level = outlines.keys.sorted().mapNotNull { outlines[it] }
        var levels = 0
        while (!fitsBudget(level) && levels < MaxReduceLevels) {
            level = mergeLevel(level, levels + 1, title, onStage, mergeCache) { key, text ->
                mergeCache[key] = text
                store.save(noteId, fp, done, mergeCache)
            }
            levels++
        }

        val refined = refineOutline(level, title, allowExpand, onStage)
        val generated = finalGenerate(refined, title, allowExpand, onStage)
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
            outputTruncated = generated.outputTruncated,
            stats = PipelineResult.Stats(
                segmentCalls, reduceCalls, finalCalls, reusedSegments, maxInputTokens, reusedMergeGroups, refineCalls
            ),
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
                complete.call(system, user, MergeOutTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError.Truncated) {
                // 抬高重试后仍截断的提炼：带回来的部分大纲照样能用（只丢了这一段的尾巴），强过整段作废
                if (e.partialContent.isNotBlank()) {
                    consecutiveFatal = 0
                    return SegmentAttempt(e.partialContent.trim(), null)
                }
                fatal = e.message
                null
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
        cache: Map<String, String>,
        onMerged: (key: String, text: String) -> Unit,
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
            // 归并组按成员内容寻址缓存：续跑时同样的输入直接复用，不再重打接口
            val key = SegmentStore.fingerprintForGroup(g, level)
            val cached = cache[key]
            if (cached != null) {
                reusedMergeGroups++
                out.add(cached)
                onStage("已复用归并断点：第 ${i + 1}/${groups.size} 组（第 $level 层）")
                return@forEachIndexed
            }
            onStage("正在汇总第 ${i + 1}/${groups.size} 组（第 $level 层）…")
            val user = Prompts.groupMergeUser(g, title)
            trackInput(system, user)
            reduceCalls++
            val merged = try {
                complete.call(system, user, MergeOutTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError.Truncated) {
                // 截断的归并结果带回来就用：比原样上收少一层重复，尾巴丢了不影响结构
                e.partialContent.trim()
            } catch (e: Exception) {
                noteFailure(e.message ?: e.javaClass.simpleName)
                ""
            }
            // 归并失败就把这一组原样上收，内容不能丢；原样上收的结果不进断点，下次还给它归并的机会
            val text = merged.trim()
            if (text.isNotBlank()) {
                out.add(text)
                onMerged(key, text)
            } else {
                out.add(g.joinToString("\n\n"))
            }
        }
        return out
    }

    /**
     * 定稿前的优化步（对应「自己不断优化思路」）：把逐段提炼/归并得到的大纲整体自查一遍
     * （遗漏、归组、层级），按设置补必要拓展。输入输出都是缩进大纲；
     * 窗口装不下或调用失败就原样跳过，绝不让优化步把已到手的大纲弄丢。
     */
    private suspend fun refineOutline(
        items: List<String>,
        title: String,
        allowExpand: Boolean,
        onStage: suspend (String) -> Unit,
    ): List<String> {
        val joined = items.joinToString("\n\n")
        val system = Prompts.refineOutlineSystem(allowExpand)
        val user = Prompts.refineOutlineUser(joined, title)
        if (Tokens.estimate(system) + Tokens.estimate(user) + StagedFlow.RefineOutTokens > effectiveWindow) {
            return items
        }
        onStage(if (allowExpand) "正在自查优化并拓展思路…" else "正在自查优化思路…")
        trackInput(system, user)
        refineCalls++
        val out = try {
            complete.call(system, user, StagedFlow.RefineOutTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmError.Truncated) {
            // 被截断的定稿大纲照样能用：只丢了尾巴，强过整步作废
            e.partialContent
        } catch (e: Exception) {
            null
        }
        val text = out?.trim().orEmpty()
        return if (text.isBlank()) items else listOf(text)
    }

    private class Generated(val parsed: LlmOutputParser.Parsed, val trimmed: Boolean, val outputTruncated: Boolean = false)

    private suspend fun finalGenerate(
        levels: List<String>,
        title: String,
        allowExpand: Boolean,
        onStage: suspend (String) -> Unit,
    ): Generated {
        onStage("正在汇总生成导图…")
        val system = Prompts.generateSystem(allowExpand)
        val joined = levels.joinToString("\n\n")
        val systemTokens = Tokens.estimate(system)
        val userOverhead = Tokens.estimate(Prompts.finalUser("", title))

        // 输出预算分两条路：大纲装得下窗口时按体量抬升（导图该多大就多大），装不下时维持
        // 原预算截断输入并如实报告——此时再抬输出只会挤掉更多原文，得不偿失
        val roomWithoutTrim = effectiveWindow - systemTokens - Tokens.estimate(joined) - userOverhead
        val out: Int
        val (outlineText, trimmed) = if (roomWithoutTrim >= finalOutTokens) {
            out = (Tokens.estimate(joined) * FinalOutRatio).toInt()
                .coerceIn(finalOutTokens, minOf(effectiveWindow / 2, roomWithoutTrim).coerceAtLeast(finalOutTokens))
            joined to false
        } else {
            // 归并两层仍装不下时，这里截断并如实报告——不留给下游静默截
            out = finalOutTokens
            val inputCap = (effectiveWindow - systemTokens - out - userOverhead).coerceAtLeast(256)
            Tokens.truncateToTokens(joined, inputCap)
        }
        val user = Prompts.finalUser(outlineText, title)
        finalCalls++
        var parsed = LlmOutputParser.parse(callOrSalvage(system, user, out))
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            finalCalls++
            parsed = LlmOutputParser.parse(
                callOrSalvage(system, user + Prompts.formatReminder(), out)
            )
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图，请在设置里换一个中文能力更强的模型再试")
        }
        return Generated(parsed, trimmed)
    }

    /**
     * 最终生成的调用：抬高输出预算后仍被截断时，抢救带回的部分内容
     * （<导图> 没闭合也能解析出大半个导图），只有完全解析不出结构才把异常往上抛。
     * 可重试错误（限流/网络抖动）在这里多试一次——前面几十趟都熬过来了，定稿最不能白跑。
     */
    private suspend fun callOrSalvage(system: String, user: String, out: Int): String {
        repeat(2) { attempt ->
            try {
                return callTracked(system, user, out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmError.Truncated) {
                val salvaged = LlmOutputParser.parsePartial(e.partialContent)
                val mapOk = salvaged != null && TreeText.parse(salvaged.mapText).isNotEmpty()
                if (!mapOk) throw e
                outputTruncated = true
                return LlmOutputParser.render(salvaged)
            } catch (e: LlmError) {
                if (!e.retryable || attempt == 1) throw e
            }
        }
        throw LlmError.Network() // 循环上界兜底，实际到不了
    }

    private suspend fun callTracked(system: String, user: String, out: Int): String {
        trackInput(system, user)
        return complete.call(system, user, out)
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

        /**
         * 单段提炼/归并的输出预算。推理模型的思维链计入输出上限（实测几百到上千 token 起步），
         * 旧的 1200 让提炼调用在"想"的环节就撞顶，是截断报错最主要的来源。
         */
        const val MergeOutTokens = 4096
        const val FinalOutTokens = 16_384

        /** 定稿输出随大纲体量抬升的比例：大纲 5000 token 时输出预算约 3000 */
        const val FinalOutRatio = 0.6

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
