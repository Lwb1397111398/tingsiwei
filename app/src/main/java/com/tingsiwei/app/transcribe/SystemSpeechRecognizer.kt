package com.tingsiwei.app.transcribe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 手机自带语音识别（边录边转）。
 * 长录音时识别服务可能会话中断，这里自动重启续接，把最终文本持续回调出去。
 * 注意：部分手机需要安装相应的语音服务才能用；不可用时改用离线模型。
 */
class SystemSpeechRecognizer(private val context: Context) {

    interface Callback {
        /** 一段话的最终结果 */
        fun onSegment(text: String)
        /** 正在说的部分（实时上屏用） */
        fun onPartial(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var running = false
    private var errorCount = 0

    val isRunning: Boolean get() = running

    fun start(cb: Callback) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cb.onError("这台手机没有可用的语音识别服务，请到设置里改用离线识别")
            return
        }
        callback = cb
        running = true
        errorCount = 0
        startOnce()
    }

    private fun startOnce() {
        val cb = callback ?: return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!running) return
                // 常见错误：无语音输入(6/7)、超时(1)等都直接重启续录
                errorCount++
                if (errorCount > 30) {
                    running = false
                    cb.onError("语音识别多次失败，请改用离线识别")
                    return
                }
                r.destroy()
                if (running) startOnce()
            }

            override fun onResults(results: Bundle?) {
                if (!running) return
                errorCount = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) cb.onSegment(text)
                r.destroy()
                if (running) startOnce()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!running) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) cb.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent)
    }

    fun stop() {
        running = false
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
