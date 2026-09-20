package com.tingsiwei.app.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * 把任意音频文件（m4a/mp3/wav…）解码为 16kHz 单声道 PCM，按块吐出，
 * 避免整段长录音一次性载入内存（一小时音频约 830MB，绝不能整体加载）。
 */
object AudioDecode {

    /**
     * 解码音频文件，每累计约 [chunkSeconds] 秒源音频就回调一次 16k 单声道 float（-1..1）。
     * @param onProgress 0..1（按已解码时长 / 总时长）
     */
    fun forEachChunk(
        path: String,
        chunkSeconds: Int = 15,
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

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()

                val chunkSourceSamples = srcRate.toLong() * chunkSeconds
                // 累积的下混后单声道样本
                var mono = ShortArray((chunkSourceSamples * 1.2).toInt().coerceAtLeast(8192))
                var monoLen = 0

                fun flushChunk(forceResampleTail: Boolean) {
                    if (monoLen <= 0) return
                    if (!forceResampleTail && monoLen < chunkSourceSamples) return
                    // 线性插值重采样到 16k
                    val outLen = (monoLen.toLong() * 16000 / srcRate).toInt()
                    if (outLen <= 0) return
                    val out = FloatArray(outLen)
                    val step = srcRate.toDouble() / 16000.0
                    var pos = 0.0
                    for (i in 0 until outLen) {
                        val i0 = pos.toInt()
                        val frac = (pos - i0).toFloat()
                        val s0 = mono[i0].toFloat() / 32768f
                        val s1 = (if (i0 + 1 < monoLen) mono[i0 + 1] else mono[monoLen - 1]).toFloat() / 32768f
                        out[i] = s0 + (s1 - s0) * frac
                        pos += step
                    }
                    onChunk(out)
                    // 保留未消费的尾部样本
                    val consumed = outLen.toLong() * srcRate / 16000
                    val remain = monoLen - consumed.toInt()
                    if (remain > 0) System.arraycopy(mono, consumed.toInt(), mono, 0, remain)
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
                    when {
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val shortBuf = outBuf.asShortBuffer()
                            val frameCount = shortBuf.remaining() / channels
                            // 下混为单声道
                            if (monoLen + frameCount > mono.size) {
                                mono = mono.copyOf((monoLen + frameCount).toInt())
                            }
                            val tmp = ShortArray(frameCount)
                            if (channels == 1) {
                                shortBuf.get(tmp)
                            } else {
                                var sIdx = 0
                                for (fr in 0 until frameCount) {
                                    var acc = 0
                                    for (ch in 0 until channels) {
                                        acc += shortBuf.get(sIdx + ch).toInt()
                                    }
                                    tmp[fr] = (acc / channels).toShort()
                                    sIdx += channels
                                }
                                shortBuf.position(shortBuf.position() + frameCount * channels)
                            }
                            System.arraycopy(tmp, 0, mono, monoLen, frameCount)
                            monoLen += frameCount
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0 && info.presentationTimeUs > 0) {
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            flushChunk(forceResampleTail = false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) {
                            // 等待 EOS 输出
                        }
                    }
                }
                flushChunk(forceResampleTail = true)
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
