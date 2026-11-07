package com.tingsiwei.app.llm

import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.mindmap.CategoryMerger
import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.TreeText

/**
 * 分类总导图 AI 优化整合：把分类下各篇导图大纲按时间正序拼给模型，融合去重成一份精炼导图，
 * 固化为一篇 source=MERGED 的笔记（每分类至多一篇，重复整合覆盖旧的，覆盖前存版本快照）。
 * 只在用户点「AI 优化整合」时触发，不参与任何自动流程。
 */
class CategoryMergeFlow(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val client = LlmClient(settings)

    /** @return 产出/覆盖的笔记 id；分类里没有可整合的导图时抛 [LlmException]（message 可直接展示） */
    suspend fun run(categoryId: Long, onStage: suspend (String) -> Unit = {}): Long {
        val category = db.categoryDao().byId(categoryId) ?: throw LlmException("分类不存在")
        val notes = db.noteDao().readyWithMapInCategory(categoryId)
            .filter { !it.mapJson.isNullOrBlank() }
        if (notes.isEmpty()) throw LlmException("这个分类还没有已生成的导图，先把里面的笔记生成一遍")

        val cfg = settings.current()
        onStage("正在汇总 ${notes.size} 篇导图…")
        val input = buildInput(notes, LlmPolicy.windowFor(cfg.llmContextWindow, cfg.llmConservative))
        onStage("AI 正在整合 ${notes.size} 篇内容，稍等…")
        val raw = client.chat(
            cfg.llmUrl, cfg.llmKey, cfg.llmModel,
            Prompts.mergeCategorySystem(),
            Prompts.mergeCategoryUser(notes.size, input),
        )
        val parsed = LlmOutputParser.parse(raw)
        val forest = TreeText.parse(parsed.mapText)
        if (forest.isEmpty()) throw LlmException("AI 没有按格式返回导图，请重试一次")

        val title = mergeTitle(category.name)
        val (mapJson, virtualRoot) = TreeText.toMapData(forest, title)
        val thinking = parsed.thinking.ifBlank {
            "本导图由 AI 将 ${notes.size} 篇笔记的导图整合而成。"
        }
        val dao = db.noteDao()
        val now = System.currentTimeMillis()
        val existing = dao.mergedNoteInCategory(categoryId)
        return if (existing != null) {
            // 覆盖前存版本快照，与重新生成同规矩：绝不让用户丢掉旧版
            existing.mapJson?.let { old ->
                db.versionDao().insert(
                    VersionEntity(
                        noteId = existing.id,
                        createdAt = now,
                        mapJson = old,
                        thinking = existing.thinking,
                        virtualRoot = existing.virtualRoot,
                    )
                )
                db.versionDao().pruneTo(existing.id, SnapshotKeep)
            }
            dao.update(
                existing.copy(
                    mapJson = mapJson,
                    thinking = thinking,
                    virtualRoot = virtualRoot,
                    status = NoteStatus.READY,
                    errorMsg = null,
                    updatedAt = now,
                )
            )
            existing.id
        } else {
            dao.insert(
                NoteEntity(
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    status = NoteStatus.READY,
                    source = NoteSource.MERGED,
                    categoryId = categoryId,
                    mapJson = mapJson,
                    thinking = thinking,
                    virtualRoot = virtualRoot,
                )
            )
        }
    }

    companion object {

        /**
         * 拼各篇大纲为「### 日期 标题 + TAB 缩进大纲」。整体超预算（窗口的 55%）时逐篇砍深度
         * （先保 3 层、再 2 层、再 1 层），最后一道兜底交给 LlmSession 的输入截断。
         */
        fun buildInput(notes: List<NoteEntity>, contextWindow: Int): String {
            val pieces = notes.mapNotNull { n ->
                val mapJson = n.mapJson?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Piece(CategoryMerger.branchTopic(n), TreeText.fromMapData(mapJson, n.virtualRoot))
            }
            val budget = (contextWindow * 0.55).toInt().coerceAtLeast(2000)
            var maxDepth = 3
            fun render(depth: Int) = pieces.joinToString("\n\n") { p ->
                "### ${p.branch}\n${pruneDepth(p.outline, depth)}"
            }
            var input = render(maxDepth)
            while (Tokens.estimate(input) > budget && maxDepth > 1) {
                maxDepth -= 1
                input = render(maxDepth)
            }
            return input
        }

        /** 缩进树文本按深度裁剪：保留前 maxDepth 层（深度从 0 起） */
        fun pruneDepth(text: String, maxDepth: Int): String =
            text.lines()
                .filter { line -> line.isBlank() || line.takeWhile { it == '\t' }.length < maxDepth }
                .joinToString("\n")
                .trim()

        /** 固化笔记标题：{分类名}·总导图（分类名过长截断） */
        fun mergeTitle(categoryName: String): String =
            "${categoryName.replace('\n', ' ').replace('\r', ' ').trim().take(20)}·总导图"

        private class Piece(val branch: String, val outline: String)

        /** 每篇笔记保留的版本快照上限，与 Generator.VERSION_KEEP 一致 */
        private const val SnapshotKeep = 20
    }
}
