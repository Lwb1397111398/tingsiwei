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
                complete = { s, u, out ->
                    client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, s, u, out)
                },
                store = SegmentStore(File(App.get().filesDir, "pipeline")),
                contextWindow = cfg.llmContextWindow,
                conservative = cfg.llmConservative,
            )
            val outcome = if (pipeline.useDirectRoute(content, system, FinalOutTokens)) {
                stagedOrDirect(cfg, content, note.title, onStage)
            } else {
                val result = pipeline.run(noteId, content, note.title, cfg.allowExpand, onStage)
                Outcome(result.mapText, result.thinking, result.notice())
            }
            val forest = TreeText.parse(outcome.mapText)
            if (forest.isEmpty()) throw LlmException("AI 返回的导图是空的，请重试或在设置里换个模型")
            // 覆盖前存一份快照：重新生成绝不能把用户手动整理过的导图弄丢
            note.mapJson?.let { old ->
                db.versionDao().insert(
                    VersionEntity(
                        noteId = note.id,
                        createdAt = now(),
                        mapJson = old,
                        thinking = note.thinking,
                        virtualRoot = note.virtualRoot,
                    )
                )
            }
            val (mapJson, virtualRoot) = TreeText.toMapData(forest, note.title)
            settings.setLastGoodModel(cfg.llmModel)
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
            dao.update(note.copy(status = NoteStatus.ERROR, errorMsg = withLastGoodHint(friendly(e)), updatedAt = now()))
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
            val forest = TreeText.parse(TreeText.fromMapData(oldMap, note.virtualRoot))
            if (forest.isEmpty()) throw LlmException("当前导图数据异常，请先生成一次")
            val result = ReviseFlow.run(
                ReviseFlow.Context(
                    forest = forest,
                    thinking = note.thinking.orEmpty(),
                    originalContent = note.content.orEmpty(),
                    suggestion = suggestion,
                    fullSystem = Prompts.generateSystem(cfg.allowExpand) + "\n" + Prompts.majorKeepRule(),
                ),
                object : ReviseFlow.Ask {
                    override suspend fun diff(system: String, user: String) =
                        client.chat(
                            cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user,
                            FinalOutTokens, degradeRule = Prompts.diffTrimRule(),
                        )

                    override suspend fun full(system: String, user: String) =
                        client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user)
                },
            )
            when (result) {
                is ReviseResult.Success -> {
                    val (mapJson, virtualRoot) = TreeText.toMapData(result.forest, note.title)
                    dao.update(
                        note.copy(
                            mapJson = mapJson,
                            thinking = result.thinking ?: note.thinking,
                            virtualRoot = virtualRoot,
                            status = NoteStatus.READY,
                            errorMsg = result.notice,
                            updatedAt = now(),
                        )
                    )
                }
                is ReviseResult.Fail -> dao.update(
                    note.copy(
                        status = NoteStatus.ERROR,
                        errorMsg = "修改失败：${result.reason}（原导图未改动，可重新提一次要求）",
                        updatedAt = now(),
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LlmError.Truncated) {
            // 防御分支：ReviseFlow 已把截断归入 Fail，走到这里说明接入被绕过——绝不用半截导图覆盖完好的旧图
            dao.update(
                note.copy(
                    status = NoteStatus.ERROR,
                    errorMsg = "修改失败：本次改动输出太长被截断（原导图未改动）。可以把要求拆小一点再试",
                    updatedAt = now(),
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 修改失败绝不能破坏现有导图：带"修改失败"前缀，界面上的「重试」只会清掉提示，不会重跑整篇生成
            dao.update(
                note.copy(
                    status = NoteStatus.ERROR,
                    errorMsg = "修改失败：${friendly(e)}（原导图未改动，可重新提一次要求）",
                    updatedAt = now(),
                )
            )
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
        var ask = parseOrSalvage(cfg, system, user)
        if (TreeText.parse(ask.parsed.mapText).isEmpty()) {
            ask = parseOrSalvage(cfg, system, user + Prompts.formatReminder())
        }
        if (TreeText.parse(ask.parsed.mapText).isEmpty()) {
            throw LlmException("AI 没有按格式返回导图。可在设置里换一个模型再试。")
        }
        return Outcome(
            ask.parsed.mapText,
            ask.parsed.thinking,
            if (ask.salvaged) "输出超出长度上限，已尽量保留生成的导图，可在导图页检查并让 AI 补全" else null,
        )
    }

    /**
     * 直连路由的正常路径是三段式（对应「先成思路、再优化拓展、导图从思路来」）：
     * 起草思路（只读懂原文）→ 自查优化并按需拓展（拓展句标「（拓展）」）→ 只按定稿思路出导图。
     * 思路定稿后导图不再重写思路，图文天然一致。一次成稿退居兜底：
     * 窗口装不下三段、或起草就失败，都退回老路径；优化或出图失败则带着已有成果继续退，不推倒重来。
     * 三段式至少 3 次串行调用，受「思路精修」开关控制（默认关=一次成稿，1 次调用，快约 3 倍）。
     */
    private suspend fun stagedOrDirect(
        cfg: com.tingsiwei.app.data.AppSettings,
        content: String,
        title: String,
        onStage: suspend (String) -> Unit,
    ): Outcome {
        if (!cfg.llmStagedThinking) return directGenerate(cfg, content)
        val window = LlmPolicy.windowFor(cfg.llmContextWindow, cfg.llmConservative).coerceAtLeast(1024)
        if (!StagedFlow.fits(window, Tokens.estimate(content))) return directGenerate(cfg, content)
        return try {
            stagedGenerate(cfg, content, title, onStage)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            directGenerate(cfg, content)
        }
    }

    private suspend fun stagedGenerate(
        cfg: com.tingsiwei.app.data.AppSettings,
        content: String,
        title: String,
        onStage: suspend (String) -> Unit,
    ): Outcome {
        onStage("正在通读内容，起草思路…")
        val draft = try {
            StagedFlow.thinkingText(
                ask(cfg, Prompts.draftThinkingSystem(), Prompts.draftThinkingUser(content), StagedFlow.DraftOutTokens)
            )
        } catch (e: LlmError.Truncated) {
            StagedFlow.thinkingText(e.partialContent)
        }
        if (draft.isBlank()) return directGenerate(cfg, content)

        onStage(if (cfg.allowExpand) "正在自查优化并拓展思路…" else "正在自查优化思路…")
        val refined = try {
            StagedFlow.thinkingText(
                ask(
                    cfg,
                    Prompts.refineThinkingSystem(cfg.allowExpand),
                    Prompts.refineThinkingUser(draft, content),
                    StagedFlow.RefineOutTokens,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "" // 优化失败不致命：拿草稿继续出图
        }
        val thinking = refined.ifBlank { draft }

        onStage("正在根据思路生成导图…")
        return mapFromThinking(cfg, thinking, title) ?: directGenerate(cfg, content)
    }

    /** 第三步：思路 → 导图。思路就是定稿，直接沿用；出不来结构返回 null 让调用方兜底 */
    private suspend fun mapFromThinking(
        cfg: com.tingsiwei.app.data.AppSettings,
        thinking: String,
        title: String,
    ): Outcome? {
        val system = Prompts.mapFromThinkingSystem()
        val user = Prompts.mapFromThinkingUser(thinking, title)
        var parsed = parseOrSalvage(cfg, system, user).parsed
        if (TreeText.parse(parsed.mapText).isEmpty()) {
            parsed = parseOrSalvage(cfg, system, user + Prompts.mapOnlyReminder()).parsed
        }
        if (TreeText.parse(parsed.mapText).isEmpty()) return null
        return Outcome(parsed.mapText, thinking, null)
    }

    private class AskResult(val parsed: LlmOutputParser.Parsed, val salvaged: Boolean)

    /**
     * ask 的截断抢救版：截断异常带回的部分内容能解析出导图结构就接着用；
     * 抢救不出结构才把异常往上抛。解析不出格式时由调用方走 formatReminder 重问。
     */
    private suspend fun parseOrSalvage(
        cfg: com.tingsiwei.app.data.AppSettings,
        system: String,
        user: String,
    ): AskResult {
        val raw = try {
            ask(cfg, system, user)
        } catch (e: LlmError.Truncated) {
            val salvaged = LlmOutputParser.parsePartial(e.partialContent) ?: throw e
            return AskResult(LlmOutputParser.parse(LlmOutputParser.render(salvaged)), true)
        }
        return AskResult(LlmOutputParser.parse(raw), false)
    }

    private suspend fun ask(
        cfg: com.tingsiwei.app.data.AppSettings,
        system: String,
        user: String,
        outTokens: Int = FinalOutTokens,
    ): String = client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user, outTokens)

    private fun friendly(e: Exception): String = when (e) {
        is LlmException -> e.message ?: "接口错误"
        is UnknownHostException -> "无法连接服务器，请检查网络和接口地址"
        is SocketTimeoutException -> "请求超时，请重试或换更快的模型"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 换模型后总失败时，把上次成功的模型名递到用户眼前，省得他自己回忆 */
    private suspend fun withLastGoodHint(msg: String): String {
        val cfg = settings.current()
        val last = cfg.lastGoodModel
        if (last.isNullOrBlank() || last == cfg.llmModel) return msg
        return "$msg（上次成功用的是 $last，可到设置里切换试试）"
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val FinalOutTokens = LongTextPipeline.FinalOutTokens
    }
}
