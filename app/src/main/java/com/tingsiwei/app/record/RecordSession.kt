package com.tingsiwei.app.record

import com.tingsiwei.app.App
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.transcribe.SystemSpeechRecognizer
import com.tingsiwei.app.transcribe.Timeline
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

    /**
     * 系统识别模式：每段最终结果到达时的录音时长（毫秒）。
     * 与 finalText 的行一一对应（一段结果可能多行，每行记同一时刻），存进笔记当播放时间轴。
     * 回调在主线程、读在 IO 协程，锁保护。
     */
    private val segArrivals = mutableListOf<Long>()
    private val segLock = Any()

    private val recognizerCallback = object : SystemSpeechRecognizer.Callback {
        override fun onSegment(text: String) {
            finalText.value = (finalText.value + text + "\n").trim()
            val at = durationMs.value
            synchronized(segLock) {
                repeat(text.lines().count { it.isNotBlank() }) { segArrivals.add(at) }
            }
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
                synchronized(segLock) { segArrivals.clear() }
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

    /** @param categoryId 归入的分类（首页筛选继承 + 录音页可改），null = 未分类 */
    fun stopAndSave(categoryId: Long? = null) {
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
        val arrivals = synchronized(segLock) { segArrivals.toList() }
        scope.launch(Dispatchers.IO) {
            try {
                val content = if (mode == TranscribeMode.SYSTEM && systemText.isNotBlank()) systemText else null
                // 系统识别当场就有全文：时间轴一并入库，播放时才能高亮跟随；
                // 空全文走离线转写，时间轴由 Transcriber 生成
                val timingsJson = if (content != null) {
                    Timeline.encode(Timeline.buildFromLineArrivals(systemText.lines(), arrivals, duration))
                } else null
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
                        timings = timingsJson,
                        categoryId = categoryId,
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
