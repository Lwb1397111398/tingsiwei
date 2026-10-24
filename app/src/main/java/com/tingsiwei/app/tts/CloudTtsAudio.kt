package com.tingsiwei.app.tts

/**
 * 云端朗读的音频纯函数：WAV（RIFF）解析与线性重采样。
 * 在线朗读首选流式 PCM；渠道不支持时自动改要 WAV 整段，这里负责把 WAV 还原成
 * 16bit 单声道 PCM 并重采样到 CloudTts.SAMPLE_RATE——播放层始终按 24k 建轨道，
 * 不用关心服务端实际给了什么采样率。
 */
object CloudTtsAudio {

    class WavException(message: String) : RuntimeException(message)

    data class Wav(val sampleRate: Int, val pcm: ShortArray)

    /**
     * 解析 16bit PCM 的 WAV 字节（RIFF 头 + fmt 块 + data 块）。
     * 立体声降混成单声道；非 16bit 或结构不对抛 [WavException]（message 给用户看）。
     */
    fun parseWav(bytes: ByteArray): Wav {
        if (bytes.size < 44 || !asciiAt(bytes, 0, "RIFF") || !asciiAt(bytes, 8, "WAVE")) {
            throw WavException("返回的不是 WAV 音频")
        }
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leInt(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) throw WavException("WAV 头不完整")
                    channels = leShort(bytes, body + 2).toInt()
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14).toInt()
                }
                "data" -> {
                    dataStart = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size % 2) // 块按 2 字节对齐
        }
        if (dataStart < 0) throw WavException("WAV 里没有音频数据块")
        if (bits != 16) throw WavException("暂只支持 16bit 音频（实际 ${bits}bit）")
        if (channels !in 1..2) throw WavException("不支持的声道数：$channels")
        if (sampleRate <= 0) throw WavException("WAV 采样率非法")
        val frames = dataLen / (2 * channels)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            out[i] = if (channels == 1) {
                leShort(bytes, dataStart + i * 2)
            } else {
                ((leShort(bytes, dataStart + i * 4) + leShort(bytes, dataStart + i * 4 + 2)) / 2).toShort()
            }
        }
        return Wav(sampleRate, out)
    }

    /** 线性插值重采样；同采样率或空输入原样返回 */
    fun resample(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || src.isEmpty() || fromRate <= 0 || toRate <= 0) return src
        val outLen = Math.max(1, Math.round(src.size.toDouble() * toRate / fromRate).toInt())
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val frac = pos - i0
            val s0 = src[i0]
            val s1 = if (i0 + 1 < src.size) src[i0 + 1] else s0
            out[i] = ((1 - frac) * s0 + frac * s1).toInt().toShort()
        }
        return out
    }

    /** 小端字节流转 16bit 采样；奇数结尾丢弃半个采样 */
    fun decodePcmLe(bytes: ByteArray): ShortArray {
        val frames = bytes.size / 2
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            out[i] = (((bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort())
        }
        return out
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun leShort(bytes: ByteArray, pos: Int): Short {
        if (pos + 1 >= bytes.size) return 0
        return ((bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun leInt(bytes: ByteArray, pos: Int): Int {
        var v = 0
        for (i in 3 downTo 0) {
            v = (v shl 8) or (bytes.getOrElse(pos + i) { 0 }.toInt() and 0xFF)
        }
        return v
    }
}
