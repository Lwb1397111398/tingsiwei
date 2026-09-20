package com.tingsiwei.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 转写方式 */
object TranscribeMode {
    const val OFFLINE = "offline" // 内置离线模型（sherpa-onnx）
    const val SYSTEM = "system"   // 手机自带语音识别
}

data class AppSettings(
    val llmUrl: String,
    val llmKey: String,
    val llmModel: String,
    val transcribeMode: String,
    val allowExpand: Boolean,
    val ttsRate: Float,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val llmUrl = stringPreferencesKey("llm_url")
        val llmKey = stringPreferencesKey("llm_key")
        val llmModel = stringPreferencesKey("llm_model")
        val transcribeMode = stringPreferencesKey("transcribe_mode")
        val allowExpand = booleanPreferencesKey("allow_expand")
        val ttsRate = floatPreferencesKey("tts_rate")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            llmUrl = p[K.llmUrl] ?: "",
            llmKey = p[K.llmKey] ?: "",
            llmModel = p[K.llmModel] ?: "",
            transcribeMode = p[K.transcribeMode] ?: TranscribeMode.OFFLINE,
            allowExpand = p[K.allowExpand] ?: true,
            ttsRate = p[K.ttsRate] ?: 1.0f,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLlm(url: String, key: String, model: String) {
        context.dataStore.edit { p ->
            p[K.llmUrl] = url.trim()
            p[K.llmKey] = key.trim()
            p[K.llmModel] = model.trim()
        }
    }

    suspend fun setTranscribeMode(mode: String) {
        context.dataStore.edit { it[K.transcribeMode] = mode }
    }

    suspend fun setAllowExpand(flag: Boolean) {
        context.dataStore.edit { it[K.allowExpand] = flag }
    }

    suspend fun setTtsRate(rate: Float) {
        context.dataStore.edit { it[K.ttsRate] = rate }
    }
}
