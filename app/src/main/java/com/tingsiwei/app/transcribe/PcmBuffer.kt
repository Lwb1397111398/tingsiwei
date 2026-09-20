package com.tingsiwei.app.transcribe

import java.nio.ShortBuffer

/**
 * 解码后 PCM 的累积缓冲：单声道 ShortArray + 线性插值重采样到 16k。
 * 只保留"还没出块"的样本，因此峰值占用与音频总时长无关——一小时音频绝不整体载入。
 */
class PcmBuffer(private val srcRate: Int, initialCapacity: Int) {

    private var data = ShortArray(initialCapacity.coerceAtLeast(8192))
    var size = 0
        private set

    /** 历史最高占用（样本数），用来断言内存有界 */
    var highWater = 0
        private set

    fun samples(): ShortArray = data

    fun ensureRoom(extra: Int) {
        if (size + extra > data.size) {
            data = data.copyOf(((size + extra) * 2).coerceAtLeast(8192))
        }
    }

    /** 从解码输出追加（多声道自动下混为均值单声道） */
    fun append(src: ShortBuffer, frameCount: Int, channels: Int) {
        if (frameCount <= 0) return
        ensureRoom(frameCount)
        if (channels == 1) {
            src.get(data, size, frameCount)
        } else {
            val tmp = ShortArray(frameCount)
            var idx = 0
            for (f in 0 until frameCount) {
                var acc = 0
                for (ch in 0 until channels) acc += src.get(idx + ch).toInt()
                tmp[f] = (acc / channels).toShort()
                idx += channels
            }
            System.arraycopy(tmp, 0, data, size, frameCount)
            src.position(src.position() + frameCount * channels)
        }
        size += frameCount
        if (size > highWater) highWater = size
    }

    /** 取走前 [count] 个源样本（重采样成 16k float），剩余样本前移 */
    fun takeResampled(count: Int): FloatArray {
        val take = minOf(count, size)
        if (take <= 0) return FloatArray(0)
        val out = resample(take)
        val remain = size - take
        if (remain > 0) System.arraycopy(data, take, data, 0, remain)
        size = remain.coerceAtLeast(0)
        return out
    }

    fun takeAllResampled(): FloatArray = takeResampled(size)

    private fun resample(count: Int): FloatArray {
        if (srcRate == TargetRate) {
            val out = FloatArray(count)
            for (i in 0 until count) out[i] = data[i].toFloat() / 32768f
            return out
        }
        val outLen = (count.toLong() * TargetRate / srcRate).toInt()
        val out = FloatArray(outLen)
        val step = srcRate.toDouble() / TargetRate
        var pos = 0.0
        for (i in 0 until outLen) {
            val i0 = pos.toInt().coerceAtMost(count - 1)
            val i1 = minOf(i0 + 1, count - 1)
            val s0 = data[i0].toFloat() / 32768f
            val s1 = data[i1].toFloat() / 32768f
            out[i] = s0 + (s1 - s0) * (pos - i0).toFloat()
            pos += step
        }
        return out
    }

    companion object {
        const val TargetRate = 16000
    }
}
