package com.tingsiwei.app.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 转写门面：按设置的方式把音频转成文字 */
object Transcriber {

    /**
     * 离线识别（sherpa-onnx + SenseVoice）
     * @param onProgress (0..1, 状态描述)
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
        val dir = SherpaTranscriber.modelDir(context).absolutePath
        val transcriber = withContext(Dispatchers.Default) { SherpaTranscriber(dir) }
        try {
            val sb = StringBuilder()
            withContext(Dispatchers.Default) {
                AudioDecode.forEachChunk(audioPath) { chunk ->
                    val text = transcriber.recognizeChunk(chunk)
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                        sb.append(text)
                    }
                    val chars = sb.length
                    onProgress(0f, "已识别 $chars 字…")
                }
            }
            return sb.toString().trim()
        } finally {
            transcriber.release()
        }
    }
}
