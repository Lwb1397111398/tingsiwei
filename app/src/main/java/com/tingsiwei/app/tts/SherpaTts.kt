package com.tingsiwei.app.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * sherpa-onnx 离线朗读（kokoro-int8-multi-lang-v1_1：中英双语，模型由设置页下载）。
 * 配置对齐官方 demo：lexicon 三件套（zh/us-en/gb-en 逗号分隔），rule_fsts=phone/date/number-zh.fst，
 * dict 用仓库自带的 jieba 词典，espeak-ng-data 提供英文注音。
 */
class SherpaTts(modelDir: String, numThreads: Int = 2) {

    val tts: OfflineTts

    init {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    lexicon = listOf("lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")
                        .joinToString(",") { File(modelDir, it).absolutePath },
                    dictDir = File(modelDir, "dict").absolutePath,
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
                .joinToString(",") { File(modelDir, it).absolutePath },
            maxNumSentences = 1,
            // kokoro 爱在句读处拖长停顿，压一半明显更跟手；0.3 以下会显得喘不上气
            silenceScale = 0.5f,
        )
        tts = OfflineTts(assetManager = null, config = config)
    }

    companion object {
        /** 默认音色：v1.1 里 sid 0-2 是英文女声，3 起才是中文女声（zf_001） */
        const val VOICE_ID = 3
    }

    val sampleRate: Int get() = tts.sampleRate()

    fun release() = tts.release()
}
