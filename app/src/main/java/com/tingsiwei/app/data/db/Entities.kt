package com.tingsiwei.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 笔记状态 */
object NoteStatus {
    const val DRAFT = "DRAFT"               // 已录入内容，还没生成
    const val TRANSCRIBING = "TRANSCRIBING" // 语音转文字中
    const val GENERATING = "GENERATING"     // LLM 生成导图中
    const val READY = "READY"               // 完成
    const val ERROR = "ERROR"               // 出错，见 errorMsg
}

/** 内容来源 */
object NoteSource {
    const val RECORD = "RECORD" // 现场录音
    const val IMPORT = "IMPORT" // 导入音频文件
    const val TEXT = "TEXT"     // 手动输入文字
    const val MERGED = "MERGED" // 分类总导图（AI 优化整合的固化产出）
}

/** 用户自建分类（课程/当事人/项目……名字由用户定），笔记通过 notes.categoryId 归入 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 首页分类条显示顺序，上移/下移交换此值 */
    val sortOrder: Int = 0,
    val createdAt: Long,
)

/** 分类条投影：分类 + 该分类下笔记数（未包含 source=MERGED 的总导图笔记） */
data class CategoryWithCount(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val noteCount: Int,
)

@Entity(tableName = "notes", indices = [Index("categoryId")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val source: String,
    val audioPath: String? = null,
    val durationMs: Long = 0,
    /** 转写或手动输入的原文 */
    val content: String? = null,
    /**
     * 播放跟随时间轴 JSON（Timeline 编码：[{"s":起,"e":止,"t":"句子"}, ...]）。
     * 录音/导入笔记转写时生成；文字录入与升级前的旧笔记为 null——详情页无高亮跟随。
     */
    val timings: String? = null,
    /** mind-elixir 完整数据 JSON */
    val mapJson: String? = null,
    /** 记忆用的思路段落 */
    val thinking: String? = null,
    /** 多个顶层主题时是否包了虚拟根节点 */
    val virtualRoot: Boolean = false,
    /** 归属分类，null = 未分类；无外键约束，删分类由 CategoryDao 事务善后 */
    val categoryId: Long? = null,
    val errorMsg: String? = null,
)

/**
 * 首页卡片的轻量投影：不把整篇原文和整份导图 JSON 拉进内存，只取卡片要展示的列。
 * preview = 思路或原文的前 120 字（SQL 里截好），由 NoteDao.observeList/searchList 填充。
 */
data class NoteListItem(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val status: String,
    val source: String,
    val durationMs: Long = 0,
    val audioPath: String? = null,
    val preview: String? = null,
    val categoryId: Long? = null,
)

@Entity(
    tableName = "versions",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId")],
)
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val createdAt: Long,
    val mapJson: String,
    val thinking: String? = null,
    val virtualRoot: Boolean = false,
)
