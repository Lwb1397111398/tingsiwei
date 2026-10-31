package com.tingsiwei.app.transcribe

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * sherpa-onnx + SenseVoice 离线识别。
 * 模型：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09（约 226MB，首次使用时下载）。
 */
class SherpaTranscriber(modelDir: String, numThreads: Int = 2) {

    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath,
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = numThreads,
                modelType = "sense-voice",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    fun release() = recognizer.release()

    /** 识别一个 16k 单声道 float 块 */
    fun recognizeChunk(samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, 16000)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    companion object {
        fun modelDir(context: Context): File =
            File(context.filesDir, "asr/sensevoice").apply { mkdirs() }

        fun isModelReady(context: Context): Boolean {
            val dir = modelDir(context)
            // 门槛 = 声明的完整体积：旧判据 200MB 会把下载中断留下的半截文件当成「已就绪」
            return File(dir, "model.int8.onnx").length() >= ModelManager.MODEL_BYTES &&
                File(dir, "tokens.txt").length() > 100_000
        }
    }
}

/**
 * 离线识别引擎单例：模型加载要几秒、session 占内存 ~230MB，加载一次常驻复用，
 * 连续转写多篇（启动续跑时最明显）不再每篇白付一次加载。
 * 与 tts.LocalTtsEngine 同一成例；设置页删除模型后必须调 [release]，否则内存里仍持有已删文件。
 */
object LocalAsrEngine {
    @Volatile
    private var instance: SherpaTranscriber? = null

    fun obtain(context: Context): SherpaTranscriber =
        instance ?: synchronized(this) {
            instance ?: SherpaTranscriber(SherpaTranscriber.modelDir(context).absolutePath).also { instance = it }
        }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}
