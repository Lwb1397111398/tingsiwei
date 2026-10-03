package com.tingsiwei.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tingsiwei.app.llm.LlmPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 转写方式 */
object TranscribeMode {
    const val OFFLINE = "offline" // 内置离线模型（sherpa-onnx）
    const val SYSTEM = "system"   // 手机自带语音识别
}

/** 朗读引擎 */
object TtsEngine {
    const val SYSTEM = "system" // 系统自带 TTS（依赖手机装了哪个语音引擎）
    const val LOCAL = "local"   // 内置离线模型（sherpa-onnx，可在设置里下载）
}

data class AppSettings(
    val llmUrl: String,
    val llmKey: String,
    val llmModel: String,
    val transcribeMode: String,
    val allowExpand: Boolean,
    val ttsRate: Float,
    /** 模型上下文窗口（token），用于预算每次请求的输入+输出 */
    val llmContextWindow: Int = LlmPolicy.DEFAULT_CONTEXT_WINDOW,
    /** 两次接口请求之间的最小间隔，避免自己把渠道 QPS 打满 */
    val llmMinIntervalMs: Long = LlmPolicy.DEFAULT_MIN_INTERVAL_MS,
    /** 保守模式：更少分段、更大间隔、更小窗口，给限流严重的渠道 */
    val llmConservative: Boolean = false,
    /** 思路精修（三段式：起草→自查→按思路出图）。更慢但图文更一致；默认关=一次成稿，快约 3 倍 */
    val llmStagedThinking: Boolean = false,
    /** 朗读引擎：系统 TTS / 本地离线模型 */
    val ttsEngine: String = TtsEngine.SYSTEM,
    /** 最近一次成功生成导图用的模型名；换模型后失败时用来提示用户切回去 */
    val lastGoodModel: String? = null,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val llmUrl = stringPreferencesKey("llm_url")
        val llmKey = stringPreferencesKey("llm_key")
        val llmModel = stringPreferencesKey("llm_model")
        val transcribeMode = stringPreferencesKey("transcribe_mode")
        val allowExpand = booleanPreferencesKey("allow_expand")
        val ttsRate = floatPreferencesKey("tts_rate")
        val llmContextWindow = intPreferencesKey("llm_context_window")
        val llmMinIntervalMs = longPreferencesKey("llm_min_interval_ms")
        val llmConservative = booleanPreferencesKey("llm_conservative")
        val llmStagedThinking = booleanPreferencesKey("llm_staged_thinking")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val lastGoodModel = stringPreferencesKey("last_good_model")

        /** 一次性迁移标记：旧版默认窗口 16k 对大模型/推理模型太小，升级后统一抬到新默认 */
        val windowMigrated = booleanPreferencesKey("llm_window_migrated_1_3")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            llmUrl = p[K.llmUrl] ?: "",
            llmKey = p[K.llmKey] ?: "",
            llmModel = p[K.llmModel] ?: "",
            transcribeMode = p[K.transcribeMode] ?: TranscribeMode.OFFLINE,
            allowExpand = p[K.allowExpand] ?: true,
            ttsRate = p[K.ttsRate] ?: 1.0f,
            llmContextWindow = p[K.llmContextWindow] ?: LlmPolicy.DEFAULT_CONTEXT_WINDOW,
            llmMinIntervalMs = p[K.llmMinIntervalMs] ?: LlmPolicy.DEFAULT_MIN_INTERVAL_MS,
            llmConservative = p[K.llmConservative] ?: false,
            llmStagedThinking = p[K.llmStagedThinking] ?: false,
            ttsEngine = p[K.ttsEngine] ?: TtsEngine.SYSTEM,
            lastGoodModel = p[K.lastGoodModel],
        )
    }

    /**
     * 所有跑管线的读取都走这里（而不是 settings Flow），因此迁移只在这一处生效：
     * 旧版装上后没动过窗口（仍是 16k 老默认）的，一次性抬到新默认并落迁移标记，之后用户改回 16k 会如实保留。
     */
    suspend fun current(): AppSettings {
        val p = context.dataStore.data.first()
        if (p[K.windowMigrated] != true && p[K.llmContextWindow] == LlmPolicy.LEGACY_DEFAULT_CONTEXT_WINDOW) {
            context.dataStore.edit {
                it[K.windowMigrated] = true
                it[K.llmContextWindow] = LlmPolicy.DEFAULT_CONTEXT_WINDOW
            }
            return settings.first()
        }
        return AppSettings(
            llmUrl = p[K.llmUrl] ?: "",
            llmKey = p[K.llmKey] ?: "",
            llmModel = p[K.llmModel] ?: "",
            transcribeMode = p[K.transcribeMode] ?: TranscribeMode.OFFLINE,
            allowExpand = p[K.allowExpand] ?: true,
            ttsRate = p[K.ttsRate] ?: 1.0f,
            llmContextWindow = p[K.llmContextWindow] ?: LlmPolicy.DEFAULT_CONTEXT_WINDOW,
            llmMinIntervalMs = p[K.llmMinIntervalMs] ?: LlmPolicy.DEFAULT_MIN_INTERVAL_MS,
            llmConservative = p[K.llmConservative] ?: false,
            llmStagedThinking = p[K.llmStagedThinking] ?: false,
            ttsEngine = p[K.ttsEngine] ?: TtsEngine.SYSTEM,
            lastGoodModel = p[K.lastGoodModel],
        )
    }

    suspend fun setLlm(url: String, key: String, model: String) {
        context.dataStore.edit { p ->
            p[K.llmUrl] = url.trim()
            p[K.llmKey] = key.trim()
            p[K.llmModel] = model.trim()
        }
    }

    suspend fun setLlmLimits(contextWindow: Int, minIntervalMs: Long, conservative: Boolean) {
        context.dataStore.edit { p ->
            p[K.llmContextWindow] = contextWindow.coerceIn(MinContextWindow, MaxContextWindow)
            p[K.llmMinIntervalMs] = minIntervalMs.coerceIn(0L, MaxMinIntervalMs)
            p[K.llmConservative] = conservative
        }
    }

    suspend fun setTranscribeMode(mode: String) {
        context.dataStore.edit { it[K.transcribeMode] = mode }
    }

    suspend fun setAllowExpand(flag: Boolean) {
        context.dataStore.edit { it[K.allowExpand] = flag }
    }

    suspend fun setLlmStagedThinking(flag: Boolean) {
        context.dataStore.edit { it[K.llmStagedThinking] = flag }
    }

    suspend fun setTtsRate(rate: Float) {
        context.dataStore.edit { it[K.ttsRate] = rate }
    }

    suspend fun setTtsEngine(engine: String) {
        context.dataStore.edit { it[K.ttsEngine] = engine }
    }

    suspend fun setLastGoodModel(model: String) {
        context.dataStore.edit { it[K.lastGoodModel] = model }
    }

    companion object {
        const val MinContextWindow = 2048
        const val MaxContextWindow = 1_000_000
        const val MaxMinIntervalMs = 60_000L
    }
}
