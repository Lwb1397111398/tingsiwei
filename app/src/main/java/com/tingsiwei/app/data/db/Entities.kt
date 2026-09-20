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
}

@Entity(tableName = "notes")
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
    /** mind-elixir 完整数据 JSON */
    val mapJson: String? = null,
    /** 记忆用的思路段落 */
    val thinking: String? = null,
    /** 多个顶层主题时是否包了虚拟根节点 */
    val virtualRoot: Boolean = false,
    val errorMsg: String? = null,
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
