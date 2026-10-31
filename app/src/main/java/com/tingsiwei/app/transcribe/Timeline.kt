package com.tingsiwei.app.transcribe

/**
 * 播放跟随时间轴：把「原文 ↔ 音频时间」对起来，播放时高亮当前句并自动滚动对准。
 *
 * 数据以 JSON 存在 notes.timings 列（Room v2 新增，与 content 分离，生成/导出链路零改动）：
 *   [{"s":0,"e":15200,"t":"好，那我们接着往下讲啊。"}, ...]
 * s/e = 句子在音频里的起止毫秒，t = 句子文本，数组按时间升序。
 *
 * 时间来源（两级精度）：
 * - 块级精确：离线转写按静音点切 15~30s 块，每块在音频里的偏移由累计样本数算出，分毫不差；
 * - 块内按字数比例分摊到句（块内没有字级时间戳，误差几秒，到下一块边界自动校准）。
 * 系统识别模式没有块概念，用「每段最终结果到达时的录音时长」当段尾时间（误差 1~2 秒）。
 *
 * 没有音频（文字录入）或升级前的旧笔记 timings 为 null，详情页回落为普通段落显示。
 */
object Timeline {

    /** 一句带时间的话 */
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    /** 转写产物：原文全文（格式与旧版一致，供生成/导出用）+ 时间轴 JSON */
    data class TranscriptResult(val text: String, val timingsJson: String?)

    /** 句末标点：这些字符后断句（含换行，块间拼接符也天然是边界） */
    private const val SENTENCE_ENDS = "。！？；!?;\n"

    /**
     * 无句末标点时的硬切字数。实测 SenseVoice 输出没有任何标点——不硬切的话整块（15~30s）
     * 只有一个高亮单元，跟随粒度粗到没法用；30 字 ≈ 7 秒语音，切段后高亮跟手。
     * 有标点的句子也适用（超长句同样切），切点任意不影响阅读与对齐。
     */
    private const val HARD_SPLIT_CHARS = 30

    // ---------- 构建 ----------

    /**
     * 把一块的识别文本按句切开，时间在 [chunkStartMs, chunkEndMs] 内按字数比例分摊。
     * 块级边界是精确的，句级是比例估计——这是「够用的近似」，句号粒度足够高亮跟读。
     */
    fun buildSegments(chunkText: String, chunkStartMs: Long, chunkEndMs: Long): List<Segment> =
        distribute(splitSentences(chunkText), chunkStartMs, chunkEndMs)

    /**
     * 系统识别模式：lines = 最终文本按行拆开（每行一段识别结果），
     * arrivalsMs = 每行到达时的录音时长（行 i 的起点取上一行的到达时刻）。
     * 行数与到达数对不上（理论上不该发生，防御）：退化为按总时长字数均摊。
     */
    fun buildFromLineArrivals(lines: List<String>, arrivalsMs: List<Long>, totalMs: Long): List<Segment> {
        val texts = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return emptyList()
        if (arrivalsMs.size != texts.size) return distribute(texts, 0L, totalMs)
        return texts.mapIndexed { i, t ->
            Segment(
                startMs = if (i == 0) 0L else arrivalsMs[i - 1],
                endMs = arrivalsMs[i].coerceAtLeast(if (i == 0) 0L else arrivalsMs[i - 1]),
                text = t,
            )
        }
    }

    /** 句子按字数比例摊到 [startMs, endMs]：字多的句占时长多，最朴素的语速均匀假设 */
    private fun distribute(texts: List<String>, startMs: Long, endMs: Long): List<Segment> {
        if (texts.isEmpty()) return emptyList()
        val span = (endMs - startMs).coerceAtLeast(0L)
        val total = texts.sumOf { it.length }.coerceAtLeast(1)
        var acc = 0
        return texts.map { t ->
            val s = startMs + span * acc / total
            acc += t.length
            Segment(startMs = s, endMs = startMs + span * acc / total, text = t)
        }
    }

    /**
     * 按句末标点切句；切出的纯标点碎片（没有实际文字，如省略号后跟的句号）并入前一句；
     * 超过 [HARD_SPLIT_CHARS] 字的句子按固定字数硬切——ASR 无标点输出（实测 SenseVoice 如此）
     * 不硬切会让高亮粒度退化到整块 15~30s。
     */
    fun splitSentences(text: String): List<String> {
        val raw = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in SENTENCE_ENDS) {
                raw.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) raw.add(sb.toString())

        val out = ArrayList<String>()
        for (piece in raw) {
            val t = piece.trim()
            if (t.isEmpty()) continue
            // 没有实际文字的碎片（纯标点）并回前句，不单独成段
            if (out.isNotEmpty() && t.none { it.isLetterOrDigit() }) {
                out[out.size - 1] = out.last() + t
            } else {
                out.add(t)
            }
        }
        return out.flatMap { hardSplit(it) }
    }

    /** 超长句按固定字数切成段（每段 ≤ [HARD_SPLIT_CHARS] 字），短句原样 */
    private fun hardSplit(text: String): List<String> {
        if (text.length <= HARD_SPLIT_CHARS) return listOf(text)
        return text.chunked(HARD_SPLIT_CHARS)
    }

    // ---------- 查询 ----------

    /**
     * 播放位置对应的句下标：最后一个 startMs <= posMs 的句（边界时刻归后一句）；
     * pos 在第一句之前归 0，空表归 -1。二分，列表几百上千句也是 O(log n)。
     */
    fun activeIndex(segments: List<Segment>, posMs: Long): Int {
        if (segments.isEmpty()) return -1
        var lo = 0
        var hi = segments.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].startMs <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    // ---------- JSON 编解码（手写极简，同 LlmProfiles 成例：JVM 单测没有 org.json 可用） ----------

    fun encode(segments: List<Segment>): String {
        val sb = StringBuilder(segments.size * 48 + 2)
        sb.append('[')
        segments.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"s\":").append(s.startMs)
            sb.append(",\"e\":").append(s.endMs)
            sb.append(",\"t\":\"").append(escape(s.text)).append("\"}")
        }
        sb.append(']')
        return sb.toString()
    }

    /** 解析失败/字段缺失一律返回空列表（调用方回落为无时间轴显示），绝不抛异常打断页面 */
    fun decode(json: String?): List<Segment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            decodeStrict(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeStrict(json: String): List<Segment> {
        val p = JsonReader(json)
        val out = ArrayList<Segment>()
        p.ws()
        if (p.peek() != '[') return emptyList()
        p.take()
        if (p.wsPeek() == ']') return out
        while (true) {
            p.ws()
            if (p.peek() != '{') return emptyList()
            p.take()
            var s = -1L
            var e = -1L
            var t: String? = null
            while (true) {
                p.ws()
                val key = p.str() ?: return emptyList()
                p.ws()
                if (p.peek() != ':') return emptyList()
                p.take()
                p.ws()
                when (key) {
                    "s" -> s = p.num()
                    "e" -> e = p.num()
                    "t" -> t = p.str()
                    else -> p.skipValue()
                }
                p.ws()
                when (p.peek()) {
                    ',' -> p.take()
                    '}' -> {
                        p.take()
                        break
                    }
                    else -> return emptyList()
                }
            }
            if (t != null && t.isNotBlank() && s >= 0 && e >= s) out.add(Segment(s, e, t))
            p.ws()
            when (p.peek()) {
                ',' -> p.take()
                ']' -> return out
                else -> return emptyList()
            }
        }
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /** 只够解析本格式的小读取器：字符串/数字/空白，别的结构当场抛错由 decode 兜底 */
    private class JsonReader(private val s: String) {
        private var i = 0

        fun peek(): Char = s[i]

        fun wsPeek(): Char {
            ws()
            return peek()
        }

        fun take() {
            i++
        }

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        /** 读一个带引号字符串（含转义还原），不在引号开头返回 null */
        fun str(): String? {
            if (i >= s.length || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        i++
                        when (val esc = s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(esc)
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        /** 读带可选负号的整数 */
        fun num(): Long {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toLong()
        }

        /** 跳过不认识的值（字符串或数字），保持前向兼容 */
        fun skipValue() {
            if (i < s.length && s[i] == '"') {
                str()
            } else {
                while (i < s.length && s[i] !in ",}]") i++
            }
        }
    }
}
