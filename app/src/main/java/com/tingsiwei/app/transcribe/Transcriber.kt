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
     */
    suspend fun transcribeOffline(
        context: Context,
        audioPath: String,
        onProgress: (Float, String) -> Unit,
    ): String {
        if (!SherpaTranscriber.isModelReady(context)) {
            throw IllegalStateException("离线识别模型还没下载，请到设置页下载")
        }
        onProgress(0f, "正在加载识别模型…")
        // 常驻单例：首次调用付一次模型加载，之后复用；不在这里 release（由设置页删模型时统一放掉）
        val transcriber = withContext(Dispatchers.Default) { LocalAsrEngine.obtain(context) }
        val sb = StringBuilder()
        var blocks = 0
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
                val text = transcriber.recognizeChunk(chunk)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    sb.append(text)
                }
                blocks++
            }
        }
        return sb.toString().trim()
    }
}
