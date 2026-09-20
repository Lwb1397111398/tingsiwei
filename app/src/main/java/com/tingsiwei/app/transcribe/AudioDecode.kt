package com.tingsiwei.app.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * 把任意音频文件（m4a/mp3/wav…）解码为 16kHz 单声道 PCM，按"句子边界"逐块吐出。
 *
 * 内存：样本只留在 [PcmBuffer] 里（上限 = [maxSeconds] 秒），整段载入一小时音频要 800MB，绝不允许。
 * 出块：由 [SpeechScanner] 增量找静音中点，块长落在 target~max 之间，既不把一句话劈成两半，
 * 也不会因为长时间静音而攒出超大块。
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
            val buffer = PcmBuffer(srcRate, max + srcRate)
            val scanner = SpeechScanner(target, max, srcRate * silenceMs / 1000, silenceThresh)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()

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
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortBuf = outBuf.asShortBuffer()
                            buffer.append(shortBuf, shortBuf.remaining() / channels, channels)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        val cut = scanner.pickCut(buffer.samples(), buffer.size)
                        if (cut > 0) onChunk(buffer.takeResampled(cut))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                if (buffer.size > 0) onChunk(buffer.takeAllResampled())
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
