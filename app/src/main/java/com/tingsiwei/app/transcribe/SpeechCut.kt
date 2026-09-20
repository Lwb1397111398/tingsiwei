package com.tingsiwei.app.transcribe

import kotlin.math.abs

/**
 * 在"当前已累积的缓冲区"里挑一个出块结束点——只看缓冲区不看整条音频，
 * 所以内存占用与音频总时长无关（整段载入会吃掉几百 MB，绝不允许）。
 *
 * 出块点优先落在足够长静音的中点（句子天然边界）；找不到静音才在硬上限处切。
 * 增量扫描：每次只扫新到的帧，避免每个解码缓冲都从头扫一遍把代价变成平方级。
 * 返回 0 表示"还不出块，继续攒"；返回 >0 表示在该处出块（内部已复位，调用方前移缓冲区即可继续）。
 */
class SpeechScanner(
    private val target: Int,
    private val max: Int,
    private val minSilence: Int,
    private val thresh: Int,
    /** 硬上限下也接受更早的静音，但不产出比 min 更碎的块 */
    private val min: Int = maxOf(1, target / 2),
) {

    private var scannedFrames = 0
    private var runStart = -1

    fun reset() {
        scannedFrames = 0
        runStart = -1
    }

    fun pickCut(mono: ShortArray, len: Int): Int {
        if (len <= 0 || max <= 0) return 0
        val force = len >= max
        if (!force && len < target) return 0
        val limit = minOf(len, max)
        // 统一用 min 作下界（而不是"没到 target 前用 target"）：
        // 否则增量扫描会跳过当初"太早"的静音点，攒到硬上限后只能在句中硬切，比一次扫描更差
        val searchFrom = min.coerceIn(1, limit)
        val frames = limit / FrameSamples
        var f = scannedFrames
        while (f < frames) {
            val start = f * FrameSamples
            val end = start + FrameSamples
            var sum = 0L
            for (i in start until end) sum += abs(mono[i].toInt())
            if (sum / FrameSamples < thresh) {
                if (runStart < 0) runStart = start
                val mid = runStart + (end - runStart) / 2
                if (end - runStart >= minSilence && mid >= searchFrom) {
                    reset()
                    return mid.coerceIn(1, limit)
                }
            } else {
                runStart = -1
            }
            f++
        }
        scannedFrames = frames
        return if (force) limit.also { reset() } else 0
    }

    companion object {
        /** 10ms 一帧（16k 时 160 样本；源采样率越高帧越短，只影响判定粒度） */
        const val FrameSamples = 160
    }
}
