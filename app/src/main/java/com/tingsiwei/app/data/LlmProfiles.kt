package com.tingsiwei.app.data

/**
 * 一套命名的 LLM 接口配置（地址 + API Key + 模型）。
 *
 * 用户可保存多套、在设置页点选随时切换。切换的实现是「整套写回」：
 * 当前生效的那一套始终存在 DataStore 的 llm_url/llm_key/llm_model 三键，
 * 点选某套 = 把它的三项写回这三键——生成链路（LlmClient 等）因此完全不用感知"配置方案"的存在。
 */
data class LlmProfile(
    val name: String,
    val url: String,
    val key: String,
    val model: String,
)

// ---- 存储格式 ----
// DataStore 单键存 JSON 数组：[{"name":"…","url":"…","key":"…","model":"…"}]
// 手写极简编解码而不引依赖：字段只有四个字符串，编解码各 ~30 行，且是纯函数，
// 可以在 JVM 单测里覆盖转义与损坏输入（org.json 在 JVM 单测里是 stub，不可用）。

/** 转义成合法 JSON 字符串字面量（含首尾引号）。小于空格的控制字符统一 \uXXXX，任何用户输入都能无损落盘。 */
internal fun jsonQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun encodeLlmProfiles(list: List<LlmProfile>): String = list.joinToString(separator = ",", prefix = "[", postfix = "]") { p ->
    """{"name":${jsonQuote(p.name)},"url":${jsonQuote(p.url)},"key":${jsonQuote(p.key)},"model":${jsonQuote(p.model)}}"""
}

private class JsonScanner(private val s: String) {
    private var i = 0

    fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    /** 当前字符（不消费）；越界抛 IllegalArgumentException，由 decode 统一吞成空列表 */
    fun peek(): Char {
        check(i < s.length) { "unexpected end" }
        return s[i]
    }

    fun next(): Char = peek().also { i++ }

    fun expect(c: Char) {
        check(next() == c) { "expected '$c'" }
    }

    /** 消费完剩余空白后是否已到结尾（之后还有内容 = 尾部有垃圾，算损坏） */
    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
    }

    /** 读一个 JSON 字符串字面量（含转义）；裸控制字符不合法 */
    fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(12.toChar()) // JSON \f（Kotlin 没有字面转义，用码点写，避免源码里藏不可见字符）
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        check(i + 4 <= s.length) { "bad \\u escape" }
                        val hex = s.substring(i, i + 4)
                        check(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) { "bad \\u escape" }
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("bad escape \\$e")
                }
                else -> {
                    check(c >= ' ') { "raw control char" }
                    sb.append(c)
                }
            }
        }
    }
}

private fun readProfile(p: JsonScanner): LlmProfile {
    p.skipWs()
    p.expect('{')
    var name = ""
    var url = ""
    var key = ""
    var model = ""
    p.skipWs()
    if (p.peek() != '}') {
        while (true) {
            p.skipWs()
            val field = p.readString()
            p.skipWs()
            p.expect(':')
            p.skipWs()
            val value = p.readString()
            when (field) {
                "name" -> name = value
                "url" -> url = value
                "key" -> key = value
                "model" -> model = value
            }
            p.skipWs()
            val c = p.next()
            if (c == '}') break
            check(c == ',') { "expected ',' or '}'" }
        }
    } else {
        p.next()
    }
    return LlmProfile(name, url, key, model)
}

/**
 * 解析 [encodeLlmProfiles] 的产物；只认「字符串数组里每项含四个字符串字段」这一种形状。
 * 任何解析失败（损坏、半截、尾部有垃圾）都返回空列表而不抛异常——存量读不出来就当作没有，
 * 不打断设置页，用户重新存一遍即可恢复。没有名字的项（无意义，无法点选）会被跳过。
 */
fun decodeLlmProfiles(json: String): List<LlmProfile> {
    if (json.isBlank()) return emptyList()
    return try {
        val p = JsonScanner(json)
        p.skipWs()
        p.expect('[')
        val out = mutableListOf<LlmProfile>()
        p.skipWs()
        if (p.peek() == ']') {
            p.next()
        } else {
            while (true) {
                val item = readProfile(p)
                if (item.name.isNotBlank()) out += item
                p.skipWs()
                val c = p.next()
                if (c == ']') break
                check(c == ',') { "expected ',' or ']'" }
            }
        }
        check(p.atEnd()) { "trailing garbage" }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** 同名（精确匹配）覆盖、否则追加；返回新列表，不改入参 */
fun upsertLlmProfile(list: List<LlmProfile>, profile: LlmProfile): List<LlmProfile> {
    val idx = list.indexOfFirst { it.name == profile.name }
    return if (idx >= 0) {
        list.toMutableList().also { it[idx] = profile }
    } else {
        list + profile
    }
}

/** 按名字删除；名字不存在时原样返回 */
fun removeLlmProfile(list: List<LlmProfile>, name: String): List<LlmProfile> =
    list.filterNot { it.name == name }
