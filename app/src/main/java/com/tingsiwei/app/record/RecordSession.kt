package com.tingsiwei.app.record

import com.tingsiwei.app.App
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.transcribe.SystemSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音会话：进程级单例，配合前台服务（RecordService）保活。
 * 录音状态不挂在页面 ViewModel 上，锁屏、切后台、离开页面都不会中断录音；
 * 界面（RecordViewModel）只是这组状态的展示层。
 */
object RecordSession {

    val isRecording = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val durationMs = MutableStateFlow(0L)
    val partialText = MutableStateFlow("")
    val finalText = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val transcribeMode = MutableStateFlow(TranscribeMode.OFFLINE)

    /** 完成保存后发出新笔记 id（一次性事件） */
    val saved = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: Recorder? = null
    private var sysRecognizer: SystemSpeechRecognizer? = null
    private var ticker: Job? = null

    private val recognizerCallback = object : SystemSpeechRecognizer.Callback {
        override fun onSegment(text: String) {
            finalText.value = (finalText.value + text + "\n").trim()
            partialText.value = ""
        }

        override fun onPartial(text: String) {
            partialText.value = text
        }

        override fun onError(message: String) {
            error.value = message
        }
    }

    fun start() {
        error.value = null
        scope.launch {
            if (isRecording.value) return@launch
            // 先占位防止连点重复启动
            isRecording.value = true
            try {
                transcribeMode.value = App.get().settings.current().transcribeMode
                val rec = Recorder(App.get())
                rec.start()
                recorder = rec
                isPaused.value = false
                durationMs.value = 0
                finalText.value = ""
                partialText.value = ""
                // 前台服务保活：锁屏/切后台也继续录
                RecordService.start(App.get())
                if (transcribeMode.value == TranscribeMode.SYSTEM) startRecognition()
                ticker?.cancel()
                ticker = scope.launch {
                    while (isRecording.value) {
                        durationMs.value = recorder?.currentDurationMs() ?: 0L
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                isRecording.value = false
                recorder?.cancel()
                recorder = null
                error.value = "录音启动失败：${e.message}"
            }
        }
    }

    fun pause() {
        val rec = recorder ?: return
        rec.pause()
        sysRecognizer?.stop()
        isPaused.value = true
    }

    fun resume() {
        val rec = recorder ?: return
        rec.resume()
        if (transcribeMode.value == TranscribeMode.SYSTEM) startRecognition()
        isPaused.value = false
    }

    fun stopAndSave() {
        if (!isRecording.value) return
        val rec = recorder ?: return
        sysRecognizer?.stop()
        sysRecognizer = null
        val (file, duration) = try {
            rec.stop()
        } catch (e: Exception) {
            error.value = "录音保存失败：${e.message}"
            finishSession()
            return
        }
        finishSession()
        // 先取快照再异步入库，避免新一轮录音把状态改掉
        val systemText = finalText.value.trim()
        val mode = transcribeMode.value
        scope.launch(Dispatchers.IO) {
            try {
                val content = if (mode == TranscribeMode.SYSTEM && systemText.isNotBlank()) systemText else null
                val id = App.get().database.noteDao().insert(
                    NoteEntity(
                        title = "录音 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())}",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        status = if (content != null) NoteStatus.DRAFT else NoteStatus.TRANSCRIBING,
                        source = NoteSource.RECORD,
                        audioPath = file.absolutePath,
                        durationMs = duration,
                        content = content,
                    )
                )
                // 离开录音页后处理也要继续：交给 App 级协程，进详情页只是看进度
                NoteProcessor.start(id, NoteProcessor.Kind.TRANSCRIBE)
                saved.emit(id)
            } catch (e: Exception) {
                error.value = "录音保存失败：${e.message}"
            }
        }
    }

    fun cancelIfAny() {
        if (!isRecording.value) return
        sysRecognizer?.stop()
        sysRecognizer = null
        recorder?.cancel()
        finishSession()
    }

    private fun startRecognition() {
        sysRecognizer?.stop()
        val sr = SystemSpeechRecognizer(App.get())
        sysRecognizer = sr
        sr.start(recognizerCallback)
    }

    private fun finishSession() {
        recorder = null
        isRecording.value = false
        isPaused.value = false
        ticker?.cancel()
        ticker = null
        RecordService.stop(App.get())
    }
}
