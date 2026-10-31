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
    const val CLOUD = "cloud"   // 在线大模型朗读（默认硅基流动 CosyVoice2，需联网并在设置里填 Key）
}

/** 在线朗读的默认服务参数；音色完整清单在 tts/CloudTts.VOICES */
object TtsCloudDefaults {
    const val BASE_URL = "https://api.siliconflow.cn/v1"
    const val MODEL = "FunAudioLLM/CosyVoice2-0.5B"
    const val VOICE = "$MODEL:anna"
}

/** 在线朗读的两档服务：官方 ElevenLabs（地址/协议内置）与自定义 OpenAI 兼容渠道 */
object TtsCloudProvider {
    const val ELEVEN = "eleven"
    const val CUSTOM = "custom"
}

/** 官方 ElevenLabs 档的内置参数：地址与默认模型写死，用户只填 Key 和挑音色 */
object TtsElevenDefaults {
    const val BASE_URL = "https://api.elevenlabs.io/v1"
    const val MODEL = "eleven_multilingual_v2"

    /** 预置音色 Roger（官方 premade 男声），实测中文可读；完整列表可在线拉取点选 */
    const val VOICE = "CwhRBWXzGAHq8TQ4Fs17"
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
    /** 朗读引擎：系统 TTS / 本地离线模型 / 在线大模型 */
    val ttsEngine: String = TtsEngine.SYSTEM,
    /** 在线朗读四件套：接口地址、API Key、音色、模型（模型可手填或「检索模型」点选，不局限于某一家） */
    val ttsCloudUrl: String = TtsCloudDefaults.BASE_URL,
    val ttsCloudKey: String = "",
    val ttsCloudVoice: String = TtsCloudDefaults.VOICE,
    val ttsCloudModel: String = TtsCloudDefaults.MODEL,
    /** 在线朗读服务档位：官方 ElevenLabs（默认）/ 自定义 OpenAI 兼容 */
    val ttsCloudProvider: String = TtsCloudProvider.ELEVEN,
    /** 官方 ElevenLabs 档三件套：API Key、音色 ID（可在线拉取点选）、模型 */
    val ttsElevenKey: String = "",
    val ttsElevenVoice: String = TtsElevenDefaults.VOICE,
    val ttsElevenModel: String = TtsElevenDefaults.MODEL,
    /** 最近一次成功生成导图用的模型名；换模型后失败时用来提示用户切回去 */
    val lastGoodModel: String? = null,
    /** 是否已保存 GitHub 只读令牌（应用自更新用；令牌明文不放进本对象，单独按需读取） */
    val hasGithubToken: Boolean = false,
    /** 启动时自动检查更新（24 小时节流） */
    val updateAutoCheck: Boolean = true,
    /** 隐藏配置：检查更新的 API 地址；留空走 GitHub 官方，测试时可指向假服务器 */
    val updateApiBase: String = "",
)

class SettingsRepository(private val context: Context) {

    private object K {
        val llmUrl = stringPreferencesKey("llm_url")
        val llmKey = stringPreferencesKey("llm_key")
        val llmModel = stringPreferencesKey("llm_model")
        val llmProfiles = stringPreferencesKey("llm_profiles")
        val llmActiveProfile = stringPreferencesKey("llm_active_profile")
        val transcribeMode = stringPreferencesKey("transcribe_mode")
        val allowExpand = booleanPreferencesKey("allow_expand")
        val ttsRate = floatPreferencesKey("tts_rate")
        val llmContextWindow = intPreferencesKey("llm_context_window")
        val llmMinIntervalMs = longPreferencesKey("llm_min_interval_ms")
        val llmConservative = booleanPreferencesKey("llm_conservative")
        val llmStagedThinking = booleanPreferencesKey("llm_staged_thinking")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsCloudUrl = stringPreferencesKey("tts_cloud_url")
        val ttsCloudKey = stringPreferencesKey("tts_cloud_key")
        val ttsCloudVoice = stringPreferencesKey("tts_cloud_voice")
        val ttsCloudModel = stringPreferencesKey("tts_cloud_model")
        val ttsCloudProvider = stringPreferencesKey("tts_cloud_provider")
        val ttsElevenKey = stringPreferencesKey("tts_eleven_key")
        val ttsElevenVoice = stringPreferencesKey("tts_eleven_voice")
        val ttsElevenModel = stringPreferencesKey("tts_eleven_model")
        val lastGoodModel = stringPreferencesKey("last_good_model")

        // ---- 应用自更新 ----
        val githubToken = stringPreferencesKey("github_token")
        val updateAutoCheck = booleanPreferencesKey("update_auto_check")
        val updateLastCheckedMs = longPreferencesKey("update_last_checked_ms")
        val updateApiBase = stringPreferencesKey("update_api_base")

        /** 一次性迁移标记：旧版默认窗口 16k 对大模型/推理模型太小，升级后统一抬到新默认 */
        val windowMigrated = booleanPreferencesKey("llm_window_migrated_1_3")
    }

    /**
     * 唯一的 DataStore → AppSettings 映射，settings 流与 [current] 共用。
     * 此前是两份 25 字段的手抄映射，加新设置漏改任何一处 = 字段静默回落默认值，且编译器不报错。
     */
    private fun androidx.datastore.preferences.core.Preferences.toAppSettings(): AppSettings = AppSettings(
        llmUrl = this[K.llmUrl] ?: "",
        llmKey = this[K.llmKey] ?: "",
        llmModel = this[K.llmModel] ?: "",
        transcribeMode = this[K.transcribeMode] ?: TranscribeMode.OFFLINE,
        allowExpand = this[K.allowExpand] ?: true,
        ttsRate = this[K.ttsRate] ?: 1.0f,
        llmContextWindow = this[K.llmContextWindow] ?: LlmPolicy.DEFAULT_CONTEXT_WINDOW,
        llmMinIntervalMs = this[K.llmMinIntervalMs] ?: LlmPolicy.DEFAULT_MIN_INTERVAL_MS,
        llmConservative = this[K.llmConservative] ?: false,
        llmStagedThinking = this[K.llmStagedThinking] ?: false,
        ttsEngine = this[K.ttsEngine] ?: TtsEngine.SYSTEM,
        ttsCloudUrl = this[K.ttsCloudUrl] ?: TtsCloudDefaults.BASE_URL,
        ttsCloudKey = this[K.ttsCloudKey].orEmpty(),
        ttsCloudVoice = this[K.ttsCloudVoice] ?: TtsCloudDefaults.VOICE,
        ttsCloudModel = this[K.ttsCloudModel] ?: TtsCloudDefaults.MODEL,
        ttsCloudProvider = this[K.ttsCloudProvider] ?: TtsCloudProvider.ELEVEN,
        ttsElevenKey = this[K.ttsElevenKey].orEmpty(),
        ttsElevenVoice = this[K.ttsElevenVoice] ?: TtsElevenDefaults.VOICE,
        ttsElevenModel = this[K.ttsElevenModel] ?: TtsElevenDefaults.MODEL,
        lastGoodModel = this[K.lastGoodModel],
        hasGithubToken = this[K.githubToken] != null,
        updateAutoCheck = this[K.updateAutoCheck] != false,
        updateApiBase = this[K.updateApiBase].orEmpty(),
    )

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

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
        return p.toAppSettings()
    }

    suspend fun setLlm(url: String, key: String, model: String) {
        context.dataStore.edit { p ->
            p[K.llmUrl] = url.trim()
            p[K.llmKey] = key.trim()
            p[K.llmModel] = model.trim()
        }
    }

    // ---- LLM 配置方案（多套命名的接口配置，设置页点选即整套切换） ----

    /** 已保存的配置方案列表；存量损坏时按空处理（decode 不抛异常） */
    suspend fun llmProfiles(): List<LlmProfile> =
        context.dataStore.data.first()[K.llmProfiles]?.let(::decodeLlmProfiles).orEmpty()

    /** 最近一次点选生效的方案名；空 = 没选过，或手改过任一项已偏离所选方案 */
    suspend fun activeLlmProfile(): String =
        context.dataStore.data.first()[K.llmActiveProfile].orEmpty()

    suspend fun setActiveLlmProfile(name: String) {
        context.dataStore.edit { it[K.llmActiveProfile] = name }
    }

    /** 新增或覆盖同名方案（同名 = 更新那份配置） */
    suspend fun saveLlmProfile(profile: LlmProfile) {
        context.dataStore.edit { p ->
            val list = p[K.llmProfiles]?.let(::decodeLlmProfiles).orEmpty()
            p[K.llmProfiles] = encodeLlmProfiles(upsertLlmProfile(list, profile))
        }
    }

    suspend fun deleteLlmProfile(name: String) {
        context.dataStore.edit { p ->
            val list = p[K.llmProfiles]?.let(::decodeLlmProfiles).orEmpty()
            p[K.llmProfiles] = encodeLlmProfiles(removeLlmProfile(list, name))
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

    suspend fun setTtsCloud(url: String, key: String, voice: String, model: String) {
        context.dataStore.edit { p ->
            p[K.ttsCloudUrl] = url.trim()
            p[K.ttsCloudKey] = key.trim()
            p[K.ttsCloudVoice] = voice.trim()
            p[K.ttsCloudModel] = model.trim()
        }
    }

    /** 切换在线朗读服务档位（官方 ElevenLabs / 自定义 OpenAI 兼容），两档配置互不覆盖 */
    suspend fun setTtsCloudProvider(provider: String) {
        context.dataStore.edit { it[K.ttsCloudProvider] = provider.trim() }
    }

    /** 官方 ElevenLabs 档三项改动即存（Key / 音色 ID / 模型），与自定义档的 setTtsCloud 对应 */
    suspend fun setTtsEleven(key: String, voice: String, model: String) {
        context.dataStore.edit { p ->
            p[K.ttsElevenKey] = key.trim()
            p[K.ttsElevenVoice] = voice.trim()
            p[K.ttsElevenModel] = model.trim()
        }
    }

    suspend fun setLastGoodModel(model: String) {
        context.dataStore.edit { it[K.lastGoodModel] = model }
    }

    // ---- 应用自更新 ----

    /**
     * 保存 GitHub 只读令牌（Fine-grained PAT，Contents:Read）。
     * 仓库已公开，令牌为可选项——仅提高 API 限流额度或仓库转回私有时才需要。
     * 与 LLM key 同级明文存 DataStore，仅本机可读。
     */
    suspend fun saveGithubToken(plain: String) {
        context.dataStore.edit { it[K.githubToken] = plain.trim() }
    }

    suspend fun clearGithubToken() {
        context.dataStore.edit { it.remove(K.githubToken) }
    }

    /** 读取令牌明文；未配置返回 null。仅 UpdateViewModel 联网检查时调用 */
    suspend fun githubTokenOrNull(): String? =
        context.dataStore.data.first()[K.githubToken]?.takeIf { it.isNotBlank() }

    suspend fun setUpdateAutoCheck(enabled: Boolean) {
        context.dataStore.edit { it[K.updateAutoCheck] = enabled }
    }

    suspend fun markUpdateChecked() {
        context.dataStore.edit { it[K.updateLastCheckedMs] = System.currentTimeMillis() }
    }

    suspend fun updateLastCheckedMs(): Long =
        context.dataStore.data.first()[K.updateLastCheckedMs] ?: 0L

    companion object {
        const val MinContextWindow = 2048
        const val MaxContextWindow = 1_000_000
        const val MaxMinIntervalMs = 60_000L
    }
}
