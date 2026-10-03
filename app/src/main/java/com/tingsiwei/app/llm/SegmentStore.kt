package com.tingsiwei.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 断点存储：逐段提炼 + 归并组两类成果，写文件而不是动 Room schema（数据库版本保持 1，避免无法验证的迁移）。
 * 带内容指纹：笔记内容一旦被改写，旧成果整体失效，绝不会把上一版文本的大纲复用到新文本上。
 */
class SegmentStore(private val root: File) {

    /** 一次 load 取回的全部断点：逐段提炼（键=段序号）与归并组（键=成员内容寻址，见 fingerprintForGroup） */
    class Snapshot(val segments: Map<Int, String>, val merge: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(noteId: Long) = File(root, "segments-$noteId.json")

    /** 任何异常/损坏都退化成"没有断点"，绝不让生成流程因此失败 */
    fun load(noteId: Long, fingerprint: String): Snapshot = try {
        val f = fileFor(noteId)
        if (!f.isFile) Snapshot(emptyMap(), emptyMap()) else parse(f.readText(), fingerprint)
    } catch (e: Exception) {
        Snapshot(emptyMap(), emptyMap())
    }

    private fun parse(text: String, fingerprint: String): Snapshot {
        if (text.isBlank()) return Snapshot(emptyMap(), emptyMap())
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["fingerprint"]?.jsonPrimitive?.content != fingerprint) return Snapshot(emptyMap(), emptyMap())
        // 单个坏条目跳过即可，不能整份作废（那会让断点续跑白做）；旧格式文件没有 merge 字段，按空处理
        val segs = obj["segments"] as? JsonObject ?: JsonObject(emptyMap())
        val segments = segs.mapNotNull { (key, value) ->
            val index = key.toIntOrNull() ?: return@mapNotNull null
            val content = runCatching { value.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            index to content
        }.toMap()
        val mrg = obj["merge"] as? JsonObject ?: JsonObject(emptyMap())
        val merge = mrg.mapNotNull { (key, value) ->
            val content = runCatching { value.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            key to content
        }.toMap()
        return Snapshot(segments, merge)
    }

    /** 先写临时文件再替换；替换失败就直接写，保证不留下半截 JSON */
    fun save(
        noteId: Long,
        fingerprint: String,
        segments: Map<Int, String>,
        merge: Map<String, String> = emptyMap(),
    ) {
        val payload = buildJsonObject {
            put("fingerprint", fingerprint)
            put(
                "segments", buildJsonObject {
                    segments.forEach { (index, text) -> put(index.toString(), text) }
                }
            )
            if (merge.isNotEmpty()) {
                put(
                    "merge", buildJsonObject {
                        merge.forEach { (key, text) -> put(key, text) }
                    }
                )
            }
        }.toString()
        try {
            root.mkdirs()
            val f = fileFor(noteId)
            val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
            tmp.writeText(payload)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(payload)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 断点存储失败不影响本次生成，只是下次要从头提炼
        }
    }

    fun clear(noteId: Long) {
        try {
            fileFor(noteId).delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun fingerprint(content: String, params: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((content + "\u0000" + params).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** 归并组的缓存键：按成员内容寻址。「补全」场景下段数变化会让组构成漂移，按序号存会串组 */
        fun fingerprintForGroup(items: List<String>, level: Int): String =
            "L$level-" + fingerprint(items.joinToString("\u0000"), "merge")
    }
}
