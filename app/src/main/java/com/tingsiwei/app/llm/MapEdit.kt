package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser

/**
 * 最小编辑指令：一行一条（改/删/加），路径指向改写前的原树。
 * 行原子 = 截断输出可以逐行抢救；被砍半的指令绝不能被应用。
 */
sealed class MapEdit {
    class Rename(val path: String, val topic: String) : MapEdit()
    class Delete(val path: String) : MapEdit()
    class AddChild(val path: String, val topic: String) : MapEdit()

    companion object {
        private val Path = Regex("\\d+(\\.\\d+)*")

        /** `<修改>` 块文本 → 指令列表；无法解析的行跳过并计数 */
        fun parseAll(block: String): Pair<List<MapEdit>, Int> {
            val ops = ArrayList<MapEdit>()
            var ignored = 0
            for (raw in block.lines()) {
                val line = raw.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim()
                if (line.isEmpty()) continue
                val op = parseLine(line)
                if (op == null) ignored++ else ops.add(op)
            }
            return ops to ignored
        }

        private fun parseLine(line: String): MapEdit? {
            if (line.startsWith("删")) {
                val path = line.removePrefix("删").trim()
                return if (Path.matchEntire(path) != null) Delete(path) else null
            }
            val head = if (line.startsWith("改")) "改" else if (line.startsWith("加")) "加" else return null
            val sep = line.indexOfFirst { it == '=' || it == '＝' }
            if (sep <= head.length) return null // 等号必须存在且位于关键字之后
            val path = line.substring(head.length, sep).trim()
            val text = line.substring(sep + 1).trim()
            if (Path.matchEntire(path) == null || text.isEmpty()) return null
            return if (head == "改") Rename(path, text) else AddChild(path, text)
        }
    }
}

/**
 * 模型回包三态判定。严格识别：必须见到显式标签或 markdown 分节才算"全量导图"，
 * 防止裸指令行被 [LlmOutputParser.parse] 的宽松兜底误读成一棵导图。
 */
object ReviseOutput {
    enum class Mode { Diff, Full, Invalid }
    class Decision(val mode: Mode, val editBlock: String, val parsed: LlmOutputParser.Parsed)

    private val EditTag = Regex("<\\s*修改\\s*>([\\s\\S]*?)(?:<\\s*/\\s*修改\\s*>|$)")
    private val ThinkingTag = Regex("<\\s*思路\\s*>([\\s\\S]*?)<\\s*/\\s*思路\\s*>")
    private val MapTag = Regex("<\\s*/?\\s*导图\\s*>")
    private val HeadingMap = Regex("(?:#+\\s*|\\*\\*)\\s*(?:思维导图|导图)")

    fun classify(raw: String): Decision {
        val text = raw.trim()
        EditTag.find(text)?.let { m ->
            val block = m.groupValues[1].trim()
            if (block.isNotEmpty()) {
                val thinking = ThinkingTag.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                return Decision(Mode.Diff, block, LlmOutputParser.Parsed("", thinking))
            }
        }
        // 只认「导图标签或 markdown 分节」为全量信号；光秃秃的 <思路> 判无效走重问，
        // 否则 parse 的"整段当导图"兜底会把思路标签当成一棵垃圾树
        if (MapTag.containsMatchIn(text) || HeadingMap.containsMatchIn(text)) {
            return Decision(Mode.Full, "", LlmOutputParser.parse(text))
        }
        return Decision(Mode.Invalid, "", LlmOutputParser.Parsed("", ""))
    }
}
