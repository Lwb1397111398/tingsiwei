package com.tingsiwei.app.mindmap

import com.tingsiwei.app.data.db.NoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分类总导图拼接：把一个分类下所有已完成笔记的导图，按时间正序各包一层「日期 · 标题」分支，
 * 挂到以分类名为根的总导图下。纯结构拼接，零 AI 调用、内容零丢失，新课录完自动包含。
 */
object CategoryMerger {

    /** 分支名里的笔记标题上限（超长截断，避免分支名撑爆画布） */
    private const val MaxTitleLen = 30

    data class Result(val mapJson: String, val virtualRoot: Boolean, val noteCount: Int)

    /**
     * @param notes 该分类下 status=READY 且有 mapJson 的笔记（内部按 createdAt 正序重排，防御乱序输入）
     * @return 没有任何可拼内容（空分类/都还没生成导图）时返回 null
     */
    fun merge(notes: List<NoteEntity>, categoryName: String): Result? {
        val branches = notes.sortedBy { it.createdAt }.mapNotNull { note ->
            val mapJson = note.mapJson?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val forest = TreeText.parse(TreeText.fromMapData(mapJson, note.virtualRoot))
            if (forest.isEmpty()) return@mapNotNull null
            TreeNote(branchTopic(note), forest.toMutableList())
        }
        if (branches.isEmpty()) return null
        // 根恒为分类名（单篇分类也包一层，结构与多篇一致）：以单根 forest 走 toMapData
        val rootTitle = categoryName.ifBlank { "总导图" }
        val (mapJson, _) = TreeText.toMapData(listOf(TreeNote(rootTitle, branches.toMutableList())), rootTitle)
        return Result(mapJson, virtualRoot = false, noteCount = branches.size)
    }

    /** 分支名：M月d日 · 标题。换行压成空格（节点文字不能带换行），标题超长截断 */
    fun branchTopic(note: NoteEntity): String {
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(note.createdAt))
        val title = note.title.replace('\n', ' ').replace('\r', ' ').trim().take(MaxTitleLen)
        return if (title.isEmpty()) date else "$date · $title"
    }
}
