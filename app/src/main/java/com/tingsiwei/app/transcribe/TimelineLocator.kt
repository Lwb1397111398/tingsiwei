package com.tingsiwei.app.transcribe

/**
 * 思路 → 录音定位：在句级时间轴里找出「思路这段话在原文讲到哪一句」的起点。
 *
 * 思路是 LLM 的提炼，与转写原文没有现成的对应关系——措辞改写过，但术语/实体词
 * 大量原样保留（「绿色原则」「物权变动」这类），所以用不依赖词典的字 2-gram 重叠定位：
 * 中文没有现成切词方案，2-gram 够用且零依赖；术语 gram 在原文里集中命中，
 * 「的是」「了一个」这类到处出现的虚词组合靠「出现句占比」过滤掉，不参与打分。
 *
 * 定位分两步：先取命中最密集的核心句（同分取最早），再向前回溯至多 2 句——
 * 思路节的开头几句通常也在讲同一知识点，跳到展开处的开头比跳到密度峰更完整。
 * 纯函数、毫秒级，点击思路段落时同步调用；置信不足返回 null 由调用方提示——
 * 宁可不跳也不能乱跳，跳错位置比没有功能更伤信任。
 */
object TimelineLocator {

    /** 有效片段少于这个数就不定位：太短的查询一两个巧合命中就会乱跳 */
    private const val MIN_KEYS = 4

    /** 核心句至少要命中的片段数：低于它视为「只是措辞巧合」 */
    private const val MIN_SCORE = 3

    /** 文档频率过滤的两个口径：一个片段出现在超过 max(这两者) 句 = 无分辨力，剔除 */
    private const val COMMON_MIN = 3
    private const val COMMON_RATIO = 0.3

    /** 核心句向前回溯的句数上限：再往前多半是上一个知识点的收尾 */
    private const val BACKTRACK = 2

    /** 思路展示切段的上限：超过它再按句细切，保证每段都有可点击的落点 */
    private const val MAX_PARAGRAPH = 160

    /**
     * 在时间轴里定位 [query]（思路的一节/一段）对应的录音起点句。
     * 返回句子索引；找不到或置信不足返回 null。
     */
    fun locate(segments: List<Timeline.Segment>, query: String): Int? {
        if (segments.isEmpty()) return null
        val keys = grams(query)
        if (keys.size < MIN_KEYS) return null

        // 文档频率过滤：先数每个片段出现在多少句，出现太普遍的（虚词组合）剔除。
        // 下限取 COMMON_MIN：小语料里核心概念讲上三四遍很正常，按比例滤会误杀术语
        val df = HashMap<String, Int>()
        for (seg in segments) {
            val seen = HashSet<String>()
            for (k in keys) if (k in seg.text) seen.add(k) // 一句内重复出现只计一次
            for (k in seen) df[k] = (df[k] ?: 0) + 1
        }
        val commonLimit = (segments.size * COMMON_RATIO).toInt().coerceAtLeast(COMMON_MIN)
        val discriminating = keys.filter { (df[it] ?: 0) <= commonLimit }
        if (discriminating.size < MIN_KEYS) return null

        // 每句得分 = 命中多少个有分辨力的片段
        val scores = IntArray(segments.size)
        for (i in segments.indices) {
            var hit = 0
            for (k in discriminating) if (k in segments[i].text) hit++
            scores[i] = hit
        }

        // 单句 argmax，同分取最早——知识点第一次讲到的地方
        var best = -1
        var bestScore = 0
        for (i in segments.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                best = i
            }
        }
        if (best < 0 || bestScore < MIN_SCORE) return null

        // 向前回溯到同一密集命中段的开头：知识点的展开常从核心句的前一两句就开始。
        // 只穿过「同样密集命中」的句子（≥ 核心分一半）——擦边 1 分的句子是上一个
        // 知识点的收尾或过渡句，穿过去会把起点拉到话题之前
        var start = best
        var back = 0
        while (start > 0 && back < BACKTRACK && scores[start - 1] * 2 >= bestScore) {
            start--
            back++
        }
        return start
    }

    /**
     * 把思路文本切成可点击的段落：按行切（LLM 思路常按要点分段），
     * 超过 [MAX_PARAGRAPH] 的长段按句末标点再切、贪心并回上限内，避免一大段没有落点。
     * 不用 Timeline.splitSentences——它带 30 字硬切，会把长句打成碎片。
     */
    fun splitForDisplay(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.length <= MAX_PARAGRAPH) {
                out.add(t)
                continue
            }
            var acc = StringBuilder()
            for (s in splitBySentenceEnd(t)) {
                if (acc.isNotEmpty() && acc.length + s.length > MAX_PARAGRAPH) {
                    out.add(acc.toString())
                    acc = StringBuilder()
                }
                acc.append(s)
            }
            if (acc.isNotEmpty()) out.add(acc.toString())
        }
        return out
    }

    /** 按句末标点切（保留标点在句尾），无句末标点的尾巴单独成段 */
    private fun splitBySentenceEnd(text: String): List<String> {
        val ends = "。！？；!?;"
        val out = ArrayList<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in ends) {
                out.add(text.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /**
     * 字片段提取：字母数字连续段按词内 2-gram（英文单词不跨词），连续中文按字 2-gram；
     * 标点与空白一律丢弃——思路与原文的标点习惯不同（LLM 有标点，ASR 常没有）。
     */
    private fun grams(text: String): Set<String> {
        val out = HashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 2) {
                for (i in 0 until sb.length - 1) out.add(sb.substring(i, i + 2))
            }
            sb.clear()
        }
        for (c in text) {
            if (c.isLetterOrDigit()) sb.append(c) else flush()
        }
        flush()
        return out
    }
}
