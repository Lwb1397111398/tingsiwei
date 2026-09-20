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
    private var wasForced = false

    fun reset() {
        scannedFrames = 0
        runStart = -1
        wasForced = false
    }

    fun pickCut(mono: ShortArray, len: Int): Int {
        if (len <= 0 || max <= 0) return 0
        val force = len >= max
        if (!force && len < target) return 0
        val limit = minOf(len, max)
        if (force != wasForced) {
            // 判定口径刚切换（攒到硬上限后允许更早切）：之前的帧是按更严的下界扫的，重扫一遍。
            // 每出一次块最多发生一次，整体仍是线性，不会退化成平方。
            scannedFrames = 0
            runStart = -1
            wasForced = force
        }
        val searchFrom = if (force) min.coerceIn(1, limit) else target
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
