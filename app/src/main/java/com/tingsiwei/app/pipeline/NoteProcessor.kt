package com.tingsiwei.app.pipeline

import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.llm.Generator
import com.tingsiwei.app.transcribe.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 笔记处理宿主：转写/生成/改写跑在 App 级协程里，离开笔记页、退到后台都不会中断。
 * 同一笔记同时只跑一个任务（幂等启动）；阶段进度集中在 [stages]，详情页状态条与列表角标共用。
 * 进程被杀后由 [resumeStuck] 依据 Room 状态续跑：生成有逐段/归并断点可续，转写从头再来。
 */
object NoteProcessor {

    enum class Kind { TRANSCRIBE, GENERATE, REVISE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _stages = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 各笔记当前阶段文案（noteId → 文案） */
    val stages: StateFlow<Map<Long, String>> = _stages.asStateFlow()

    fun stageFlow(noteId: Long) = _stages.map { it[noteId] }

    fun isBusy(noteId: Long): Boolean = jobs[noteId]?.isActive == true

    /**
     * 幂等启动：同笔记已有任务在跑就忽略并返回 false。
     * 之所以收在这里而不是 ViewModel：任务的生命周期必须长于页面。
     */
    fun start(noteId: Long, kind: Kind, arg: String? = null): Boolean {
        if (noteId <= 0L) return false
        synchronized(jobs) {
            if (jobs[noteId]?.isActive == true) return false
            val job = scope.launch {
                try {
                    when (kind) {
                        Kind.TRANSCRIBE -> runTranscribe(noteId)
                        Kind.GENERATE -> runGenerate(noteId)
                        Kind.REVISE -> runRevise(noteId, arg.orEmpty())
                    }
                } finally {
                    jobs.remove(noteId)
                    setStage(noteId, null)
                }
            }
            jobs[noteId] = job
            return true
        }
    }

    fun cancel(noteId: Long) {
        jobs[noteId]?.cancel()
    }

    /** 笔记页打开时调用：状态停在半路的任务自动续跑 */
    suspend fun maybeRun(noteId: Long) {
        if (noteId <= 0L || isBusy(noteId)) return
        val dao = App.get().database.noteDao()
        val n = dao.byId(noteId) ?: return
        when {
            n.status == NoteStatus.TRANSCRIBING && n.content.isNullOrBlank() && n.audioPath != null ->
                start(noteId, Kind.TRANSCRIBE)
            n.status == NoteStatus.DRAFT ->
                start(noteId, Kind.GENERATE)
            // 生成中途被打断且没有结果：重新生成
            n.status == NoteStatus.GENERATING && n.mapJson.isNullOrBlank() ->
                start(noteId, Kind.GENERATE)
            // 修改中途被打断但旧导图完好：旧导图没有丢，直接恢复可用
            n.status == NoteStatus.GENERATING ->
                dao.update(n.copy(status = NoteStatus.READY, errorMsg = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /** App 启动时调用：把上次没跑完的笔记全部拉起来，用户不用挨个点开 */
    fun resumeStuck() {
        scope.launch {
            val dao = App.get().database.noteDao()
            for (n in dao.byStatuses(listOf(NoteStatus.TRANSCRIBING, NoteStatus.GENERATING))) {
                if (isBusy(n.id)) continue
                when {
                    n.status == NoteStatus.TRANSCRIBING && n.audioPath != null -> start(n.id, Kind.TRANSCRIBE)
                    n.status == NoteStatus.GENERATING && n.mapJson.isNullOrBlank() -> start(n.id, Kind.GENERATE)
                    n.status == NoteStatus.GENERATING ->
                        dao.update(
                            n.copy(status = NoteStatus.READY, errorMsg = null, updatedAt = System.currentTimeMillis())
                        )
                }
            }
        }
    }

    private suspend fun runTranscribe(noteId: Long) {
        val dao = App.get().database.noteDao()
        val n = dao.byId(noteId) ?: return
        val audio = n.audioPath ?: return
        // 断点续跑时原文可能已经转好，只差生成
        if (!n.content.isNullOrBlank()) {
            runGenerate(noteId)
            return
        }
        dao.update(n.copy(status = NoteStatus.TRANSCRIBING, errorMsg = null, updatedAt = System.currentTimeMillis()))
        setStage(noteId, "正在解码音频…")
        try {
            val text = Transcriber.transcribeOffline(App.get(), audio) { _, msg ->
                setStage(noteId, msg)
            }
            if (text.isBlank()) {
                throw IllegalStateException("没有识别到内容，请检查录音质量；也可到设置里改用「手机自带识别」重新录一次")
            }
            dao.update(n.copy(content = text, status = NoteStatus.DRAFT, updatedAt = System.currentTimeMillis()))
            runGenerate(noteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.update(
                n.copy(
                    status = NoteStatus.ERROR,
                    errorMsg = e.message ?: "转写失败",
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun runGenerate(noteId: Long) {
        Generator(App.get().database, App.get().settings).generate(noteId) { msg -> setStage(noteId, msg) }
    }

    private suspend fun runRevise(noteId: Long, suggestion: String) {
        Generator(App.get().database, App.get().settings).revise(noteId, suggestion) { msg -> setStage(noteId, msg) }
    }

    private fun setStage(noteId: Long, stage: String?) {
        _stages.update { if (stage == null) it - noteId else it + (noteId to stage) }
    }
}
