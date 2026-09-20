package com.tingsiwei.app.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * 把任意音频文件（m4a/mp3/wav…）解码为 16kHz 单声道 PCM，按"句子边界"逐块吐出，
 * 避免整段长录音一次性载入内存（一小时音频约 830MB，绝不能整体加载）。
 *
 * 出块点由 [SpeechCut] 在静音中点选择：块长落在 target~max 之间，
 * 既不把一句话劈成两半，也不会因为长时间静音而攒出超大块。
 */
object AudioDecode {

    fun forEachChunk(
        path: String,
        targetSeconds: Int = 15,
        maxSeconds: Int = 30,
        silenceMs: Int = 220,
        silenceThresh: Int = 600,
        onProgress: (Float) -> Unit = {},
        onChunk: (FloatArray) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            require(trackIndex >= 0) { "文件里没有音频轨道" }
            extractor.selectTrack(trackIndex)
            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val target = srcRate * targetSeconds
            val max = (srcRate * maxSeconds).coerceAtLeast(target + 1)
            val minSilence = srcRate * silenceMs / 1000

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()

                var mono = ShortArray(max.coerceAtLeast(8192) + srcRate)
                var monoLen = 0

                /** 把缓冲区前 [count] 个源采样重采样成 16k float 交出去，其余留在缓冲区头部 */
                fun emit(count: Int) {
                    if (count <= 0) return
                    val take = minOf(count, monoLen)
                    val outLen = (take.toLong() * 16000 / srcRate).toInt()
                    if (outLen > 0) {
                        val out = FloatArray(outLen)
                        val step = srcRate.toDouble() / 16000.0
                        var pos = 0.0
                        for (i in 0 until outLen) {
                            val i0 = pos.toInt().coerceAtMost(take - 1)
                            val i1 = minOf(i0 + 1, take - 1)
                            val s0 = mono[i0].toFloat() / 32768f
                            val s1 = mono[i1].toFloat() / 32768f
                            out[i] = s0 + (s1 - s0) * (pos - i0).toFloat()
                            pos += step
                        }
                        onChunk(out)
                    }
                    val remain = monoLen - take
                    if (remain > 0) System.arraycopy(mono, take, mono, 0, remain)
                    monoLen = remain.coerceAtLeast(0)
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val shortBuf = outBuf.asShortBuffer()
                        val frameCount = shortBuf.remaining() / channels
                        if (monoLen + frameCount > mono.size) {
                            mono = mono.copyOf((monoLen + frameCount) * 2)
                        }
                        if (channels == 1) {
                            shortBuf.get(mono, monoLen, frameCount)
                        } else {
                            val tmp = ShortArray(frameCount)
                            var sIdx = 0
                            for (fr in 0 until frameCount) {
                                var acc = 0
                                for (ch in 0 until channels) acc += shortBuf.get(sIdx + ch).toInt()
                                tmp[fr] = (acc / channels).toShort()
                                sIdx += channels
                            }
                            shortBuf.position(shortBuf.position() + frameCount * channels)
                            System.arraycopy(tmp, 0, mono, monoLen, frameCount)
                        }
                        monoLen += frameCount
                        codec.releaseOutputBuffer(outIdx, false)
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        val cut = SpeechCut.pickCut(mono, monoLen, target, max, minSilence, silenceThresh)
                        if (cut > 0) emit(cut)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                if (monoLen > 0) emit(monoLen)
            } finally {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
