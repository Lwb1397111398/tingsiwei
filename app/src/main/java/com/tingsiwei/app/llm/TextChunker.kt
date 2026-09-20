package com.tingsiwei.app.llm

/**
 * 语义分段：按句末边界装填到 token 预算，相邻块带 1 句重叠（保住跨段连贯），绝不切断句子。
 * 纯 Kotlin，零 Android 依赖，便于 JVM 单测。
 */
data class Chunk(val index: Int, val text: String, val overlapChars: Int)

class ChunkPlan(val chunks: List<Chunk>, val noBoundary: Boolean, val targetTokens: Int) {
    val size: Int get() = chunks.size

    /** 去掉每块开头的重叠句后按序拼接，应与原文（trim 后）一致。 */
    fun stripped(): String = chunks.joinToString("") { it.text.drop(it.overlapChars) }
}

object TextChunker {

    const val OVERLAP_SENTENCES = 1

    private val StrongEnds = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')
    private val WeakEnds = charArrayOf('，', ',', '、', '：', ':', ' ')

    private class Piece(val text: String, val tokens: Int)

    fun hasStrongBoundary(text: String): Boolean = text.any { it in StrongEnds }

    fun plan(text: String, targetTokens: Int, maxChunks: Int): ChunkPlan {
        val src = text.trim()
        if (src.isEmpty()) return ChunkPlan(listOf(Chunk(0, "", 0)), false, targetTokens)
        val total = Tokens.estimate(src)
        if (total <= targetTokens) {
            return ChunkPlan(listOf(Chunk(0, src, 0)), !hasStrongBoundary(src), targetTokens)
        }
        var target = targetTokens.coerceAtLeast(64)
        var attempts = 0
        while (true) {
            val (groups, noBoundary) = build(src, target)
            if (groups.size <= maxChunks || attempts++ >= 5) {
                return ChunkPlan(groups.mapIndexed { i, g -> Chunk(i, g.first, g.second) }, noBoundary, target)
            }
            target = (target * (groups.size.toDouble() / maxChunks)).toInt() + 1
        }
    }

    /** 返回 (每块的 文本 to 重叠字符数, 是否发生过无边界硬切) */
    private fun build(text: String, target: Int): Pair<List<Pair<String, Int>>, Boolean> {
        val (pieces, hardCut) = atomize(text, target)
        val groups = ArrayList<Pair<String, Int>>()
        val cur = StringBuilder()
        val curPieces = ArrayList<Piece>()
        var carry = listOf<Piece>()
        var carryTokens = 0
        var carryChars = 0
        var curTokens = 0
        // 装填上限先扣掉重叠，保证整块不超预算
        var packTarget = target
        for (p in pieces) {
            if (curTokens + p.tokens > packTarget && curPieces.size > carry.size) {
                groups.add(cur.toString() to carryChars)
                // 只带一句"短"尾作重叠；尾巴本身就长时宁可不带，也不能撑爆预算
                val tail = curPieces.drop(carry.size).takeLast(OVERLAP_SENTENCES)
                carry = if (tail.sumOf { it.tokens } * 4 <= target) tail else emptyList()
                carryTokens = carry.sumOf { it.tokens }
                carryChars = carry.sumOf { it.text.length }
                packTarget = (target - carryTokens).coerceAtLeast(target / 2)
                cur.setLength(0)
                curPieces.clear()
                cur.appendAll(carry)
                curPieces.addAll(carry)
                curTokens = carryTokens
            }
            cur.append(p.text)
            curPieces.add(p)
            curTokens += p.tokens
        }
        if (curPieces.size > carry.size) groups.add(cur.toString() to carryChars)
        return groups to hardCut
    }

    private fun StringBuilder.appendAll(list: List<Piece>) {
        list.forEach { append(it.text) }
    }

    /** 强边界 → 弱边界 → 字符硬切，保证每个原子片不超过预算（否则装填会死循环）。 */
    private fun atomize(text: String, target: Int): Pair<List<Piece>, Boolean> {
        var hardCut = false
        val first = splitBy(text, StrongEnds)
        val out = ArrayList<Piece>()
        for (piece in first) {
            if (piece.tokens <= target) {
                out.add(piece)
                continue
            }
            for (sub in splitBy(piece.text, WeakEnds)) {
                if (sub.tokens <= target) {
                    out.add(sub)
                } else {
                    hardCut = true
                    out.addAll(hardSplit(sub.text, target))
                }
            }
        }
        return out to hardCut
    }

    private fun splitBy(text: String, ends: CharArray): List<Piece> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Piece>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in ends) {
                out.add(Piece(sb.toString(), Tokens.estimate(sb.toString())))
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(Piece(sb.toString(), Tokens.estimate(sb.toString())))
        return out
    }

    private fun hardSplit(text: String, target: Int): List<Piece> {
        val perChunk = maxOf(16, target)
        val step = (text.length * perChunk / maxOf(1, Tokens.estimate(text))).coerceAtLeast(8)
        val out = ArrayList<Piece>()
        var i = 0
        while (i < text.length) {
            val part = text.substring(i, minOf(i + step, text.length))
            out.add(Piece(part, Tokens.estimate(part)))
            i += step
        }
        return out
    }
}
