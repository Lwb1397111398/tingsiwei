package com.tingsiwei.app.transcribe

import kotlin.math.abs

/**
 * 在"当前已累积的缓冲区"里挑一个出块结束点——纯函数、只看缓冲区，
 * 所以内存占用与音频总时长无关（整段载入会吃掉几百 MB，绝不允许）。
 *
 * 返回 0 表示"还不出块，继续攒"；返回 >0 表示在該处出块。
 * 优先落在足够长静音的中点（句子天然边界），实在找不到静音才在硬上限处切。
 */
object SpeechCut {

    /** 10ms 一帧（16k 时 160 样本；源采样率越高帧越短，只影响判定粒度） */
    const val FrameSamples = 160

    fun pickCut(
        mono: ShortArray,
        len: Int,
        target: Int,
        max: Int,
        minSilence: Int,
        thresh: Int,
        min: Int = target / 2,
    ): Int {
        if (len <= 0 || max <= 0) return 0
        val force = len >= max
        if (!force && len < target) return 0
        val limit = minOf(len, max)
        // 已达硬上限时放宽到 min，但仍不产出比 min 更碎的块
        val searchFrom = if (force) min.coerceIn(1, limit) else target
        var runStart = -1
        var f = 0
        val frames = limit / FrameSamples
        while (f < frames) {
            val start = f * FrameSamples
            val end = start + FrameSamples
            var sum = 0L
            for (i in start until end) sum += abs(mono[i].toInt())
            if (sum / FrameSamples < thresh) {
                if (runStart < 0) runStart = start
                val mid = runStart + (end - runStart) / 2
                if (end - runStart >= minSilence && mid >= searchFrom) {
                    return mid.coerceIn(1, limit)
                }
            } else {
                runStart = -1
            }
            f++
        }
        // 尾部不足一帧时，硬上限仍要能出块，否则会无限攒下去
        return if (force) limit else 0
    }
}
