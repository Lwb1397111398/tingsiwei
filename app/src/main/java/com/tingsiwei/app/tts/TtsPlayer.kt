package com.tingsiwei.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.tingsiwei.app.data.TtsEngine
import com.tingsiwei.app.transcribe.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 朗读门面：按设置在「系统语音」和「离线模型」之间分发。
 * 离线模型未下载时回落系统语音并明说；两边都不可用时也明说——绝不静默失败。
 * 朗读中可随时 [updateRate] 调速：本地引擎下一句生效，系统引擎重投当前句立即生效。
 * onMessage 的回调可能来自子线程，调用方负责切到主线程展示（如写入 StateFlow）。
 */
class TtsPlayer(
    context: Context,
    private val onMessage: ((String) -> Unit)? = null,
) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 系统语音 ----
    private var systemTts: TextToSpeech? = null
    private var systemInitDone = false
    private var systemReady = false
    private var pendingSystemText: Pair<String, Float>? = null

    /** 系统引擎按句逐条投喂：读完一块（onDone）再投下一块，调速时从当前块重投 */
    @Volatile
    private var systemChunks: List<String> = emptyList()

    @Volatile
    private var systemNext = 0

    // ---- 离线模型播放 ----
    private var localJob: Job? = null
    private var track: AudioTrack? = null

    /** 当前语速：speak/updateRate 写，本地引擎每句合成前读，朗读中改立刻生效 */
    @Volatile
    private var liveRate = 1.0f

    /** 朗读中（覆盖离线模型加载那几秒和系统引擎初始化那零点几秒） */
    private val playing = AtomicBoolean(false)

    val isSpeaking: Boolean get() = playing.get()

    var onStateChange: (() -> Unit)? = null

    /** engine 取 data.TtsEngine 的常量（"system" / "local"） */
    fun speak(text: String, rate: Float, engine: String) {
        stop()
        liveRate = rate
        when (engine) {
            TtsEngine.LOCAL -> {
                if (ModelManager.isTtsReady(appCtx)) {
                    speakLocal(text)
                } else if (speakSystemOrQueue(text, rate)) {
                    onMessage?.invoke("离线朗读模型还没下载，这次先用系统语音；可在设置里下载（约 215MB）")
                } else {
                    onMessage?.invoke("本机没有可用的朗读引擎；可在设置里下载离线朗读模型（约 215MB）")
                }
            }
            else -> {
                if (!speakSystemOrQueue(text, rate)) {
                    onMessage?.invoke("本机没有可用的系统朗读引擎；可在设置里改用离线朗读模型")
                }
            }
        }
    }

    /** 朗读中调速：不用停了再点。本地引擎下一句按新语速合成；系统引擎当前句重投，立即生效 */
    fun updateRate(rate: Float) {
        liveRate = rate
        if (!playing.get()) return
        val t = systemTts ?: return
        try {
            t.setSpeechRate(rate)
            // 正在出声的 utterance 语速已经定了，停掉让 onStop 回调从当前块重投
            if (t.isSpeaking) t.stop()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        playing.set(false)
        localJob?.cancel()
        localJob = null
        try {
            systemTts?.stop()
        } catch (_: Exception) {
        }
        onStateChange?.invoke()
    }

    fun shutdown() {
        stop()
        scope.cancel()
        try {
            systemTts?.shutdown()
        } catch (_: Exception) {
        }
        systemTts = null
        systemReady = false
    }

    // ---------- 系统语音 ----------

    /** 同步预检：本机装没装 TTS 语音引擎（没有任何引擎时 TextToSpeech 的初始化回调可能永远不回来） */
    private fun hasSystemEngine(): Boolean =
        appCtx.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .addCategory(android.content.Intent.CATEGORY_DEFAULT),
            0,
        ).isNotEmpty()

    /** 返回 false 表示引擎已确认不可用 */
    private fun speakSystemOrQueue(text: String, rate: Float): Boolean {
        if (!hasSystemEngine()) return false
        val cur = systemTts
        when {
            cur == null -> {
                pendingSystemText = text to rate
                createSystem()
                return true
            }
            !systemInitDone -> {
                pendingSystemText = text to rate
                return true
            }
            systemReady -> {
                speakSystem(text, rate)
                return true
            }
            else -> {
                // 上次初始化已失败：用户手动点了朗读，重建一次引擎再试
                try {
                    cur.shutdown()
                } catch (_: Exception) {
                }
                systemTts = null
                pendingSystemText = text to rate
                createSystem()
                return true
            }
        }
    }

    private fun createSystem() {
        systemInitDone = false
        systemTts = TextToSpeech(appCtx) { status ->
            systemInitDone = true
            systemReady = status == TextToSpeech.SUCCESS
            if (systemReady) {
                systemTts?.language = Locale.CHINA
                systemTts?.setOnUtteranceProgressListener(systemProgressListener)
                pendingSystemText?.let { (t, r) -> speakSystem(t, r) }
            } else {
                failSystemInit()
            }
            pendingSystemText = null
            onStateChange?.invoke()
        }
        // 部分机型（如裸模拟器）没有任何引擎时初始化回调永远不回来，挂 6 秒就当失败处理
        scope.launch {
            delay(6000)
            if (!systemInitDone) {
                systemInitDone = true
                systemReady = false
                failSystemInit()
                pendingSystemText = null
                onStateChange?.invoke()
            }
        }
    }

    private fun failSystemInit() {
        try {
            systemTts?.shutdown()
        } catch (_: Exception) {
        }
        systemTts = null
        if (pendingSystemText != null) {
            onMessage?.invoke("本机的系统语音不可用；可在设置里下载离线朗读模型")
            if (playing.getAndSet(false)) onStateChange?.invoke()
        }
    }

    private val systemProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (!playing.get()) return
            // 这块读完了，推进到下一块继续投喂（读完最后一块时收尾）
            val idx = utteranceId?.removePrefix("tingsiwei-")?.toIntOrNull() ?: return
            systemNext = idx + 1
            val t = systemTts ?: return
            scope.launch { enqueueNextSystemChunk(t) }
        }

        override fun onError(utteranceId: String?) {
            // 单块合成失败不中断整篇：当作读完，继续后面的块
            onDone(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // stop() 有两种来路：用户停止（playing 已为 false，到此为止）/ 朗读中调速（重投当前块）
            if (!playing.get()) return
            val t = systemTts ?: return
            scope.launch { enqueueNextSystemChunk(t) }
        }
    }

    private fun speakSystem(text: String, rate: Float) {
        val t = systemTts ?: return
        systemChunks = splitForSpeech(text, maxChars = 120)
        systemNext = 0
        t.setSpeechRate(rate)
        playing.set(true)
        onStateChange?.invoke()
        enqueueNextSystemChunk(t)
    }

    private fun enqueueNextSystemChunk(t: TextToSpeech) {
        if (!playing.get()) return
        val idx = systemNext
        if (idx >= systemChunks.size) {
            if (playing.getAndSet(false)) onStateChange?.invoke()
            return
        }
        // 一次只投一条：队列里没有积压，调速重投也不会和旧块打架
        t.speak(systemChunks[idx], TextToSpeech.QUEUE_FLUSH, null, "tingsiwei-$idx")
    }

    // ---------- 离线模型 ----------

    private fun speakLocal(text: String) {
        playing.set(true)
        onStateChange?.invoke()
        localJob = scope.launch {
            var local: AudioTrack? = null
            try {
                val engine = try {
                    LocalTtsEngine.obtain(appCtx)
                } catch (e: Exception) {
                    onMessage?.invoke("离线朗读模型加载失败，可到设置里删除后重新下载")
                    return@launch
                }
                val sampleRate = engine.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf * 4, 16384))
                    .build()
                local = t
                track = t
                t.play()
                // 边合成边播：回调里阻塞写入 AudioTrack，写多快播多快；停止时回调返回 false 让合成停下。
                // 回调必须用 TtsChunkCallback（Java 实现），Kotlin lambda 的签名匹配不上 native 反查
                var totalFrames = 0
                val chunkHandler = TtsChunkCallback.Handler { samples ->
                    if (!playing.get()) return@Handler false
                    if (samples.isNotEmpty()) {
                        val pcm = ShortArray(samples.size)
                        for (i in samples.indices) {
                            pcm[i] = ((samples[i] * 32767f).toInt().coerceIn(-32768, 32767)).toShort()
                        }
                        totalFrames += samples.size
                        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    }
                    playing.get()
                }
                // 逐句合成、AudioTrack 跨句复用，听感连续；每句开合成都前取一次 liveRate，
                // 朗读中拖调速条，当前句放完下一句立刻按新语速来。
                // 句子也是引擎的天然合成单元（内部本就逐句出声），自己先拆好还能压住内存。
                for (chunk in splitForSpeech(text, maxChars = 120)) {
                    if (!playing.get()) break
                    engine.tts.generateWithCallback(chunk, SherpaTts.VOICE_ID, liveRate, TtsChunkCallback(chunkHandler))
                }
                // 合成完了，等缓冲区里最后一段播出去
                while (playing.get() && t.playbackHeadPosition < totalFrames) delay(100)
            } catch (e: Exception) {
                if (playing.getAndSet(false)) onStateChange?.invoke()
                onMessage?.invoke("离线朗读失败：${e.message ?: "未知错误"}")
                return@launch
            } finally {
                try {
                    local?.stop()
                } catch (_: Exception) {
                }
                try {
                    local?.release()
                } catch (_: Exception) {
                }
                track = null
            }
            if (playing.getAndSet(false)) onStateChange?.invoke()
        }
    }
}

/**
 * 离线引擎单例：模型加载要几秒，加载一次常驻复用，避免每条笔记/每次试听都重新加载。
 */
object LocalTtsEngine {
    @Volatile
    private var instance: SherpaTts? = null

    fun obtain(context: Context): SherpaTts =
        instance ?: synchronized(this) {
            instance ?: SherpaTts(ModelManager.ttsDir(context).absolutePath).also { instance = it }
        }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}

/**
 * 长文本按句子边界切成 ≤maxChars 的块：系统引擎单条 utterance 有 4000 字上限，
 * 离线引擎整篇合成也会拖长出声前的等待。窗口内没句读就硬切（转写文字基本都有标点，极少走到）。
 */
internal fun splitForSpeech(text: String, maxChars: Int = 3000): List<String> {
    val t = text.trim()
    if (t.isEmpty()) return emptyList()
    if (t.length <= maxChars) return listOf(t)
    val out = ArrayList<String>()
    val ends = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')
    var start = 0
    var lastBoundary = -1
    var i = 0
    while (i < t.length) {
        if (t[i] in ends) lastBoundary = i + 1
        if (i + 1 - start >= maxChars) {
            val cut = if (lastBoundary > start) lastBoundary else i + 1
            out.add(t.substring(start, cut).trim())
            start = cut
            lastBoundary = -1
        }
        i++
    }
    if (start < t.length) out.add(t.substring(start).trim())
    return out.filter { it.isNotEmpty() }
}
