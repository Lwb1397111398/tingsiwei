package com.tingsiwei.app

import com.tingsiwei.app.llm.MapEdit
import com.tingsiwei.app.llm.ReviseOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最小编辑指令的行解析（改/删/加）与模型回包三态判定（Diff/Full/Invalid）。
 * 关键防线：裸指令行绝不能被宽松兜底误读成整棵导图。
 */
class MapEditTest {

    @Test fun parsesAllThreeOps() {
        val (ops, ignored) = MapEdit.parseAll(
            """
            改 1.2 = 新文字
            删 3
            加 2 = 新子节点
            """.trimIndent(),
        )
        assertEquals(3, ops.size)
        assertEquals(0, ignored)
        assertEquals("1.2", (ops[0] as MapEdit.Rename).path)
        assertEquals("新文字", (ops[0] as MapEdit.Rename).topic)
        assertEquals("3", (ops[1] as MapEdit.Delete).path)
        assertEquals("2", (ops[2] as MapEdit.AddChild).path)
    }

    @Test fun firstEqualsSplitsTopicMayContainMore() {
        val (ops, _) = MapEdit.parseAll("改 1 = a=b=c")
        assertEquals("a=b=c", (ops[0] as MapEdit.Rename).topic)
    }

    @Test fun junkLinesCountedIgnored() {
        val (ops, ignored) = MapEdit.parseAll("改 1.2 = ok\n乱码一行\n改 =无路径\n改 1 x=无等号紧贴")
        assertEquals(1, ops.size)
        assertEquals(3, ignored)
    }

    @Test fun bulletPrefixTolerated() {
        val (ops, _) = MapEdit.parseAll("- 删 2.1")
        assertEquals(1, ops.size)
    }

    @Test fun deleteWithoutEqualsStillOkButTextRequiredForRename() {
        val (ops, ignored) = MapEdit.parseAll("删 2.1\n改 1 =")
        assertEquals(1, ops.size) // 只有删成立
        assertEquals(1, ignored) // 空文字的改算无效行
    }

    @Test fun classifyDiffBlockWithThinking() {
        val d = ReviseOutput.classify(
            "<修改>\n改 1 = 甲\n</修改>\n<思路>\n新的一段思路\n</思路>",
        )
        assertEquals(ReviseOutput.Mode.Diff, d.mode)
        assertEquals("改 1 = 甲", d.editBlock.trim())
        assertEquals("新的一段思路", d.parsed.thinking)
    }

    @Test fun classifyUnclosedDiffSalvagesBlock() {
        val d = ReviseOutput.classify("<修改>\n改 1 = 甲\n删 2\n")
        assertEquals(ReviseOutput.Mode.Diff, d.mode)
    }

    @Test fun classifyFullOnlyWhenExplicitMapStructure() {
        val d = ReviseOutput.classify("<导图>\n主题\n\t子\n</导图>\n<思路>\n文字\n</思路>")
        assertEquals(ReviseOutput.Mode.Full, d.mode)
        assertTrue(d.parsed.mapText.contains("主题"))
    }

    @Test fun classifyPlainEditLinesNotReadAsWholeMap() {
        val d = ReviseOutput.classify("改 1 = 甲\n加 2 = 乙")
        assertEquals(ReviseOutput.Mode.Invalid, d.mode)
    }

    @Test fun classifyProseOnlyInvalid() {
        assertEquals(ReviseOutput.Mode.Invalid, ReviseOutput.classify("抱歉，我无法满足这个请求。").mode)
    }

    @Test fun classifyMarkdownHeadingTreatedAsFull() {
        val d = ReviseOutput.classify("## 思维导图\n主题\n\t子")
        assertEquals(ReviseOutput.Mode.Full, d.mode)
    }
}
