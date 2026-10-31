package com.tingsiwei.app.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 转写门面：按设置的方式把音频转成文字 */
object Transcriber {

    /**
     * 离线识别（sherpa-onnx + SenseVoice）
     * @param onProgress (0..1 真实进度, 状态描述)
     * @return 原文全文 + 播放跟随时间轴（块起止时间精确、块内按句分摊，见 [Timeline]）
     */
    suspend fun transcribeOffline(
        context: Context,
        audioPath: String,
        onProgress: (Float, String) -> Unit,
    ): Timeline.TranscriptResult {
        if (!SherpaTranscriber.isModelReady(context)) {
            throw IllegalStateException("离线识别模型还没下载，请到设置页下载")
        }
        onProgress(0f, "正在加载识别模型…")
        // 常驻单例：首次调用付一次模型加载，之后复用；不在这里 release（由设置页删模型时统一放掉）
        val transcriber = withContext(Dispatchers.Default) { LocalAsrEngine.obtain(context) }
        val sb = StringBuilder()
        val segments = ArrayList<Timeline.Segment>()
        var blocks = 0
        // 块是首尾相接从音频头顺序吐出的：累计已消费的 16k 样本数 = 当前块的音频起点
        var samplesBefore = 0L
        val ctx = coroutineContext
        withContext(Dispatchers.Default) {
            AudioDecode.forEachChunk(
                audioPath,
                isCancelled = { !ctx.isActive },
                onProgress = { frac ->
                    val pct = (frac * 100f).toInt().coerceIn(0, 100)
                    onProgress(frac, "正在识别…已完成 $pct%，已识别 ${sb.length} 字（第 ${blocks + 1} 段）")
                },
            ) { chunk ->
                val chunkStartMs = samplesBefore * 1000L / 16_000L
                samplesBefore += chunk.size
                val chunkEndMs = samplesBefore * 1000L / 16_000L
                val text = transcriber.recognizeChunk(chunk)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    sb.append(text)
                    segments += Timeline.buildSegments(text, chunkStartMs, chunkEndMs)
                }
                blocks++
            }
        }
        return Timeline.TranscriptResult(sb.toString().trim(), Timeline.encode(segments))
    }
}
