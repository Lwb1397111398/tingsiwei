package com.tingsiwei.app.llm

import com.tingsiwei.app.App
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 生成流水线：
 * 原文 →（短：一次生成 / 长：语义分段 → 逐段提炼 → 分层归并 → 一次生成）→ <导图>+<思路> → 存库
 * 修改：当前导图+思路+用户建议 → 新导图+思路（改前自动存版本快照）
 * 限流、退避、预算与截断降级都在 [LlmSession] 里；分段成果由 [SegmentStore] 落盘，失败可续跑。
 */
class Generator(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {

    private val client = LlmClient(settings)

    private class Outcome(val mapText: String, val thinking: String, val notice: String?)

    suspend fun generate(noteId: Long, onStage: suspend (String) -> Unit = {}) {
        val dao = db.noteDao()
        val note = dao.byId(noteId) ?: return
        val content = note.content
        if (content.isNullOrBlank()) {
            dao.update(note.copy(status = NoteStatus.ERROR, errorMsg = "没有可用的内容", updatedAt = now()))
            return
        }
        dao.update(note.copy(status = NoteStatus.GENERATING, errorMsg = null, updatedAt = now()))
        try {
            val cfg = settings.current()
            val system = Prompts.generateSystem(cfg.allowExpand)
            val pipeline = LongTextPipeline(
                complete = { s, u, temperature, out ->
                    client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, s, u, temperature, out)
                },
                store = SegmentStore(File(App.get().filesDir, "pipeline")),
                contextWindow = cfg.llmContextWindow,
                conservative = cfg.llmConservative,
            )
            val outcome = if (pipeline.useDirectRoute(content, system, FinalOutTokens)) {
                onStage("正在生成导图…")
                directGenerate(cfg, content)
            } else {
                val result = pipeline.run(noteId, content, note.title, cfg.allowExpand, onStage)
                Outcome(result.mapText, result.thinking, result.notice())
            }
            val forest = TreeText.parse(outcome.mapText)
            if (forest.isEmpty()) throw LlmException("AI 返回的导图是空的，请重试或在设置里换个模型")
            val (mapJson, virtualRoot) = TreeText.toMapData(forest, note.title)
            dao.update(
                note.copy(
                    mapJson = mapJson,
                    thinking = outcome.thinking.ifBlank { null },
                    virtualRoot = virtualRoot,
                    status = NoteStatus.READY,
                    errorMsg = outcome.notice,
                    updatedAt = now(),
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.update(note.copy(status = NoteStatus.ERROR, errorMsg = friendly(e), updatedAt = now()))
        }
    }

    suspend fun revise(noteId: Long, suggestion: String, onStage: suspend (String) -> Unit = {}) {
        val dao = db.noteDao()
        val note = dao.byId(noteId) ?: return
        val oldMap = note.mapJson
        if (oldMap.isNullOrBlank()) {
            dao.update(note.copy(status = NoteStatus.ERROR, errorMsg = "还没有导图，先生成一次", updatedAt = now()))
            return
        }
        // 修改前存版本快照，可回退
        db.versionDao().insert(
            VersionEntity(
                noteId = note.id,
                createdAt = now(),
                mapJson = oldMap,
                thinking = note.thinking,
                virtualRoot = note.virtualRoot,
            )
        )
        dao.update(note.copy(status = NoteStatus.GENERATING, errorMsg = null, updatedAt = now()))
        try {
            onStage("正在按你的要求修改…")
            val cfg = settings.current()
            val system = Prompts.generateSystem(cfg.allowExpand)
            val user = Prompts.reviseUser(
                TreeText.fromMapData(oldMap, note.virtualRoot),
                note.thinking.orEmpty(),
                note.content.orEmpty(),
                suggestion,
            )
            var parsed = LlmOutputParser.parse(ask(cfg, system, user))
            if (TreeText.parse(parsed.mapText).isEmpty()) {
                parsed = LlmOutputParser.parse(ask(cfg, system, user + FormatReminder))
            }
            val forest = TreeText.parse(parsed.mapText)
            if (forest.isEmpty()) throw LlmException("AI 返回的导图是空的，请重试或换个说法")
            val (mapJson, virtualRoot) = TreeText.toMapData(forest, note.title)
            dao.update(
                note.copy(
                    mapJson = mapJson,
                    thinking = parsed.thinking.ifBlank { note.thinking },
                    virtualRoot = virtualRoot,
                    status = NoteStatus.READY,
                    errorMsg = null,
                    updatedAt = now(),
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.update(note.copy(status = NoteStatus.ERROR, errorMsg = friendly(e), updatedAt = now()))
        }
    }

    suspend fun restoreVersion(noteId: Long, versionId: Long): Boolean {
        val dao = db.noteDao()
        val note = dao.byId(noteId) ?: return false
        val version = db.versionDao().byId(versionId) ?: return false
        // 回退前把当前状态也存一份快照，方便再切回来
        note.mapJson?.let {
            db.versionDao().insert(
                VersionEntity(
                    noteId = note.id,
                    createdAt = now(),
                    mapJson = it,
                    thinking = note.thinking,
                    virtualRoot = note.virtualRoot,
                )
            )
        }
        dao.update(
            note.copy(
                mapJson = version.mapJson,
                thinking = version.thinking,
                virtualRoot = version.virtualRoot,
                status = NoteStatus.READY,
                errorMsg = null,
                updatedAt = now(),
            )
        )
        return true
    }

    // ---------- 内部 ----------

    private suspend fun directGenerate(cfg: com.tingsiwei.app.data.AppSettings, content: String): Outcome {
        val system = Prompts.generateSystem(cfg.allowExpand)
        val user = Prompts.generateUser(content)
        var parsed = LlmOutputParser.parse(ask(cfg, system, user))
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            parsed = LlmOutputParser.parse(ask(cfg, system, user + FormatReminder))
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图。可在设置里换一个模型再试。")
        }
        return Outcome(parsed.mapText, parsed.thinking, null)
    }

    private suspend fun ask(
        cfg: com.tingsiwei.app.data.AppSettings,
        system: String,
        user: String,
        temperature: Double = 0.4,
    ): String = client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user, temperature, null)

    private fun friendly(e: Exception): String = when (e) {
        is LlmException -> e.message ?: "接口错误"
        is UnknownHostException -> "无法连接服务器，请检查网络和接口地址"
        is SocketTimeoutException -> "请求超时，请重试或换更快的模型"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val FinalOutTokens = 2048
        const val FormatReminder =
            "\n\n（注意：上一次输出格式不对。请严格输出 <导图>…</导图> 与 <思路>…</思路> 两部分，导图用 TAB 缩进。）"
    }
}
