package com.tingsiwei.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 导出 Markdown / 导图图片，并调起系统分享 */
object Exporter {

    /** 缩进树 → markdown 无序列表 */
    fun forestToMarkdown(forest: List<TreeNote>): String = buildString {
        fun walk(node: TreeNote, depth: Int) {
            repeat(depth) { append("  ") }
            append("- ").append(node.topic)
            append('\n')
            node.children.forEach { walk(it, depth + 1) }
        }
        forest.forEach { walk(it, 0) }
    }.trimEnd()

    fun buildMarkdown(note: NoteEntity): String {
        val mapText = note.mapJson?.let { TreeText.fromMapData(it, note.virtualRoot) }.orEmpty()
        val forest = TreeText.parse(mapText)
        return buildString {
            appendLine("# ${note.title}")
            appendLine()
            if (forest.isNotEmpty()) {
                appendLine("## 思维导图")
                appendLine()
                appendLine(forestToMarkdown(forest))
                appendLine()
            }
            if (!note.thinking.isNullOrBlank()) {
                appendLine("## 思路")
                appendLine()
                appendLine(note.thinking)
                appendLine()
            }
            if (!note.content.isNullOrBlank()) {
                appendLine("## 原文")
                appendLine()
                appendLine(note.content)
            }
        }.trimEnd() + "\n"
    }

    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(40).ifBlank { "导出" }

    suspend fun exportMarkdown(context: Context, note: NoteEntity): File = withContext(Dispatchers.IO) {
        val f = File(exportsDir(context), "${safeName(note.title)}.md")
        f.writeText(buildMarkdown(note), Charsets.UTF_8)
        f
    }

    suspend fun savePng(context: Context, bytes: ByteArray, note: NoteEntity): File = withContext(Dispatchers.IO) {
        val f = File(exportsDir(context), "${safeName(note.title)}_${System.currentTimeMillis()}.png")
        f.writeBytes(bytes)
        f
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
