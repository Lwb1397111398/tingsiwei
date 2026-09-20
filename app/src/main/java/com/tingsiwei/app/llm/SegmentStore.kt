package com.tingsiwei.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 分段成果的断点存储：写文件而不是动 Room schema（数据库版本保持 1，避免无法验证的迁移）。
 * 带内容指纹：笔记内容一旦被改写，旧成果整体失效，绝不会把上一版文本的大纲复用到新文本上。
 */
class SegmentStore(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(noteId: Long) = File(root, "segments-$noteId.json")

    fun load(noteId: Long, fingerprint: String): Map<Int, String> = try {
        val f = fileFor(noteId)
        if (!f.isFile) emptyMap() else parse(f.readText(), fingerprint)
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parse(text: String, fingerprint: String): Map<Int, String> {
        if (text.isBlank()) return emptyMap()
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["fingerprint"]?.jsonPrimitive?.content != fingerprint) return emptyMap()
        val segs = obj["segments"] as? JsonObject ?: return emptyMap()
        return segs.mapNotNull { (k, v) ->
            val idx = k.toIntOrNull() ?: return@mapNotNull null
            val content = v.jsonPrimitive.contentOrNullSafe() ?: return@mapNotNull null
            if (content.isBlank()) null else idx to content
        }.toMap()
    }

    fun save(noteId: Long, fingerprint: String, done: Map<Int, String>) {
        try {
            root.mkdirs()
            val payload = buildJsonObject {
                put("fingerprint", fingerprint)
                put("noteId", noteId)
                put("updatedAt", System.currentTimeMillis())
                put(
                    "segments", buildJsonObject {
                        done.forEach { (i, t) -> put(i.toString(), t) }
                    }
                )
            }
            val f = fileFor(noteId)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(payload.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            // 断点存储失败不影响本次生成，只是下次要重来
        }
    }

    fun clear(noteId: Long) {
        try {
            fileFor(noteId).delete()
        } catch (_: Exception) {
        }
    }

    private fun kotlinx.serialization.json.JsonElement.contentOrNullSafe(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    companion object {
        fun fingerprint(content: String, params: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest((content + "\u0000" + params).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
