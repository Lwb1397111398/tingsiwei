package com.tingsiwei.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 用系统 TTS 朗读「思路」，帮助记忆 */
class TtsPlayer(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    var onStateChange: (() -> Unit)? = null

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.CHINA
                pendingText?.let { speakInternal(it) }
                pendingText = null
            }
            onStateChange?.invoke()
        }
    }

    fun speak(text: String, rate: Float) {
        if (!ready) {
            pendingText = text
            this.rate = rate
            return
        }
        speakInternal(text)
    }

    private var rate = 1.0f

    private fun speakInternal(text: String) {
        val t = tts ?: return
        t.setSpeechRate(rate)
        t.stop()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tingsiwei-thought")
        onStateChange?.invoke()
    }

    fun stop() {
        tts?.stop()
        onStateChange?.invoke()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
