package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 生成流水线：
 * 原文 →（长文分块提炼再合并）→ <导图>+<思路> → 解析 → 存库
 * 修改：当前导图+思路+用户建议 → 新导图+思路（改前自动存版本快照）
 */
class Generator(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {

    private val client = LlmClient(settings)

    /** 直接生成的长度上限，超过则走分块 map-reduce */
    private val maxDirectChars = 5000
    private val chunkSize = 3500

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
            val (mapText, thinking) = produceMapAndThinking(cfg, content, note.title, onStage)
            val forest = TreeText.parse(mapText)
            if (forest.isEmpty()) throw LlmException("AI 返回的导图是空的，请重试")
            val (mapJson, virtualRoot) = TreeText.toMapData(forest, note.title)
            dao.update(
                note.copy(
                    mapJson = mapJson,
                    thinking = thinking.ifBlank { null },
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
            val currentMapText = TreeText.fromMapData(oldMap, note.virtualRoot)
            val user = Prompts.reviseUser(currentMapText, note.thinking.orEmpty(), note.content.orEmpty(), suggestion)
            var parsed = LlmOutputParser.parse(callWithRetry(cfg, Prompts.generateSystem(cfg.allowExpand), user))
            if (TreeText.parse(parsed.mapText).isEmpty()) {
                parsed = LlmOutputParser.parse(
                    callWithRetry(
                        cfg,
                        Prompts.generateSystem(cfg.allowExpand),
                        user + "\n\n（注意：上一次输出格式不对。请严格输出 <导图>…</导图> 与 <思路>…</思路> 两部分。）",
                    )
                )
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

    private suspend fun produceMapAndThinking(
        cfg: com.tingsiwei.app.data.AppSettings,
        content: String,
        title: String,
        onStage: suspend (String) -> Unit,
    ): Pair<String, String> {
        return if (content.length <= maxDirectChars) {
            onStage("正在生成导图…")
            val out = callWithRetry(cfg, Prompts.generateSystem(cfg.allowExpand), Prompts.generateUser(content))
            parseStrict(out)
        } else {
            val chunks = splitChunks(content, chunkSize)
            val outlines = StringBuilder()
            chunks.forEachIndexed { i, chunk ->
                onStage("正在整理第 ${i + 1}/${chunks.size} 段…")
                val outline = callWithRetry(cfg, Prompts.chunkSystem(), Prompts.chunkUser(chunk), temperature = 0.2)
                outlines.append("——第 ${i + 1} 段——\n").append(outline.trim()).append("\n\n")
            }
            onStage("正在汇总生成导图…")
            val merged = callWithRetry(cfg, Prompts.mergeSystem(cfg.allowExpand), Prompts.mergeUser(outlines.toString(), title))
            parseStrict(merged)
        }
    }

    private fun parseStrict(out: String): Pair<String, String> {
        val parsed = LlmOutputParser.parse(out)
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图。可在设置里换一个模型再试。")
        }
        return parsed.mapText to parsed.thinking
    }

    /** 长文按段落边界切块，单段过长时硬切 */
    private fun splitChunks(text: String, size: Int): List<String> {
        val chunks = mutableListOf<String>()
        val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            // 没有换行，硬切
            var i = 0
            while (i < text.length) {
                chunks.add(text.substring(i, minOf(i + size, text.length)))
                i += size
            }
            return chunks
        }
        val sb = StringBuilder()
        for (p in paragraphs) {
            var para = p
            while (sb.length + para.length > size && sb.isNotEmpty()) {
                chunks.add(sb.toString())
                sb.clear()
            }
            while (para.length > size) {
                if (sb.isNotEmpty()) {
                    chunks.add(sb.toString())
                    sb.clear()
                }
                chunks.add(para.take(size))
                para = para.drop(size)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(para)
            if (sb.length > size) {
                chunks.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    private suspend fun callWithRetry(
        cfg: com.tingsiwei.app.data.AppSettings,
        system: String,
        user: String,
        temperature: Double = 0.4,
    ): String {
        return try {
            client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user, temperature = temperature)
        } catch (e: LlmException) {
            // 一次性格式问题不重试；服务端偶发错误给一次机会
            if (e.message?.contains("HTTP 5") == true) {
                client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user, temperature = temperature)
            } else throw e
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is LlmException -> e.message ?: "接口错误"
        is UnknownHostException -> "无法连接服务器，请检查网络和接口地址"
        is SocketTimeoutException -> "请求超时，请重试或换更快的模型"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun now(): Long = System.currentTimeMillis()
}
