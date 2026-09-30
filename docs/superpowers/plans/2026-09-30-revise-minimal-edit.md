# 最小化编辑改写（revise diff 化）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 AI 改写从「整图重发」改为「最小编辑指令 + 本地应用」，非 flash 模型不再因输出体量触顶截断，未点名节点逐字保留。

**Architecture:** 新增三个纯 Kotlin 模块（`MapDiffText` 编号视图、`MapEdit`/`ReviseOutput` 指令解析与三态判定、`MapEditApplier` 树操作）+ `ReviseFlow` 纯决策层（diff 主问→提醒重问→全量降级，仿 `StagedFlow` 注入假 ask 可 JVM 单测）；`Generator.revise` 只留接线。

**Tech Stack:** Kotlin（JVM 单测，无新依赖）、OkHttp/OpenAI 兼容层现有 `LlmClient`/`chatWithDegrade`、Gradle wrapper `--no-daemon`。

**Spec:** `docs/superpowers/specs/2026-09-30-revise-minimal-edit-design.md`

**约定（全计划通用）**
- 所有命令在 `听的思维/源码工程/tingsiwei/` 目录执行；测试跑 `./gradlew.bat testDebugUnitTest --no-daemon --tests "<类全名>"`。
- 提交一律带身份且**只 add 本任务文件**（工作区存在他人未提交的在飞改动，绝不 `git add -A`）：
  `git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "<msg>" --no-verify`
- 单测放 `app/src/test/java/com/tingsiwei/app/`，包名 `com.tingsiwei.app`（与现有测试一致）；`internal` 的 `ReviseFlow` 可被同模块测试访问（`StagedFlowTest` 已验证此模式）。

---

### Task 1: `MapDiffText` 编号视图 + `TreeNote.topic` 改 var

**Files:**
- Create: `app/src/main/java/com/tingsiwei/app/mindmap/MapDiffText.kt`
- Modify: `app/src/main/java/com/tingsiwei/app/mindmap/TreeText.kt:14-17`（`TreeNote`）
- Test: `app/src/test/java/com/tingsiwei/app/MapDiffTextTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.tingsiwei.app

import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapDiffTextTest {
    private fun tree(): List<TreeNote> = listOf(
        TreeNote("根枝一", mutableListOf(TreeNote("子一"), TreeNote("子二", mutableListOf(TreeNote("孙"))))),
        TreeNote("根枝二"),
    )

    @Test fun renderNumberingDepthFirst() {
        val text = MapDiffText.render(tree())
        assertEquals(
            listOf("1 根枝一", "\t1.1 子一", "\t1.2 子二", "\t\t1.2.1 孙", "2 根枝二"),
            text.lines(),
        )
    }

    @Test fun resolveRoundTrip() {
        val t = tree()
        assertEquals("孙", MapDiffText.resolve(t, "1.2.1")?.topic)
        assertEquals("根枝二", MapDiffText.resolve(t, "2")?.topic)
        assertNull(MapDiffText.resolve(t, "3"))
        assertNull(MapDiffText.resolve(t, "1..2"))
        assertNull(MapDiffText.resolve(t, ""))
    }

    @Test fun indexCoversAllNodes() {
        val idx = MapDiffText.indexByPath(tree())
        assertEquals(setOf("1", "1.1", "1.2", "1.2.1", "2"), idx.keys)
    }

    @Test fun parentIndexUsesIdentity() {
        val t = tree()
        val parents = MapDiffText.parentIndex(t)
        val grand = MapDiffText.resolve(t, "1.2.1")!!
        assertEquals(MapDiffText.resolve(t, "1.2"), parents[grand])
        assertNull(parents[t.first()])
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapDiffTextTest"`
Expected: 编译错误 `unresolved reference: MapDiffText`

- [ ] **Step 3: 最小实现**

`TreeText.kt` 第 14-17 行改为（topic 可变，指令应用要就地改文字；`var` 不影响既有 `copy` 用法）：

```kotlin
/** 一行一个节点、TAB 缩进的通用树节点。topic 可变：AI 改写按路径就地改文字 */
data class TreeNote(
    var topic: String,
    val children: MutableList<TreeNote> = mutableListOf(),
)
```

新建 `MapDiffText.kt`：

```kotlin
package com.tingsiwei.app.mindmap

import java.util.IdentityHashMap

/**
 * 改写的编号视图：给森林每个节点算稳定路径编号（1、1.2、1.2.3…），
 * 渲染成「编号 文字」（层级用 TAB 缩进辅助阅读）。编号只进提示词、绝不落库。
 */
object MapDiffText {

    /** 深度优先渲染：根枝依次 1..n，节点编号 = 父编号.子序号（1-based） */
    fun render(forest: List<TreeNote>): String = buildString {
        fun walk(node: TreeNote, path: String, depth: Int) {
            repeat(depth) { append('\t') }
            append(path).append(' ').append(node.topic.replace('\n', ' ').replace('\r', ' ').trim())
            append('\n')
            node.children.forEachIndexed { i, c -> walk(c, "$path.${i + 1}", depth + 1) }
        }
        forest.forEachIndexed { i, r -> walk(r, "${i + 1}", 0) }
    }.trimEnd()

    /** 按点分路径定位节点；非法格式或不存在返回 null */
    fun resolve(forest: List<TreeNote>, path: String): TreeNote? {
        val parts = path.split('.')
        if (parts.isEmpty()) return null
        var nodes = forest
        var node: TreeNote? = null
        for (p in parts) {
            val i = p.toIntOrNull() ?: return null
            if (i <= 0) return null
            node = nodes.getOrNull(i - 1) ?: return null
            nodes = node.children
        }
        return node
    }

    /** 路径 → 节点（应用前对原树快照，删操作不影响其余编号的解析） */
    fun indexByPath(forest: List<TreeNote>): Map<String, TreeNote> {
        val out = LinkedHashMap<String, TreeNote>()
        fun walk(node: TreeNote, path: String) {
            out[path] = node
            node.children.forEachIndexed { i, c -> walk(c, "$path.${i + 1}") }
        }
        forest.forEachIndexed { i, r -> walk(r, "${i + 1}") }
        return out
    }

    /** 节点 → 父节点。引用同一性做键，避免同构子树在结构相等下串键 */
    fun parentIndex(forest: List<TreeNote>): Map<TreeNote, TreeNote> {
        val out = IdentityHashMap<TreeNote, TreeNote>()
        fun walk(node: TreeNote) {
            for (c in node.children) {
                out[c] = node
                walk(c)
            }
        }
        forest.forEach { walk(it) }
        return out
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapDiffTextTest"`
Expected: `BUILD SUCCESSFUL`，4 例绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/mindmap/MapDiffText.kt app/src/main/java/com/tingsiwei/app/mindmap/TreeText.kt app/src/test/java/com/tingsiwei/app/MapDiffTextTest.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): MapDiffText 路径编号视图；TreeNote.topic 改 var 支持就地编辑" --no-verify
```

---

### Task 2: `MapEdit` 指令解析 + `ReviseOutput` 三态判定

**Files:**
- Create: `app/src/main/java/com/tingsiwei/app/llm/MapEdit.kt`（含 `ReviseOutput`）
- Test: `app/src/test/java/com/tingsiwei/app/MapEditTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.tingsiwei.app

import com.tingsiwei.app.llm.MapEdit
import com.tingsiwei.app.llm.ReviseOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // 关键防线：没有标签也没有 markdown 结构的指令行必须判无效，绝不回退"整段当导图"
        val d = ReviseOutput.classify("改 1 = 甲\n加 2 = 乙")
        assertEquals(ReviseOutput.Mode.Invalid, d.mode)
    }

    @Test fun classifyProseOnlyInvalid() {
        assertEquals(ReviseOutput.Mode.Invalid, ReviseOutput.classify("抱歉，我无法满足这个请求。").mode)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapEditTest"`
Expected: 编译错误 `unresolved reference: MapEdit`

- [ ] **Step 3: 实现**

新建 `MapEdit.kt`：

```kotlin
package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser

/**
 * 最小编辑指令：一行一条（改/删/加），路径指向改写前的原树。
 * 行原子 = 截断输出可以逐行抢救，不会被砍半的指令绝不能被应用。
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
 * 防止裸指令行被 `LlmOutputParser.parse` 的宽松兜底误读成一棵导图。
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
        if (MapTag.containsMatchIn(text) || ThinkingTag.containsMatchIn(text) ||
            HeadingMap.containsMatchIn(text)
        ) {
            return Decision(Mode.Full, "", LlmOutputParser.parse(text))
        }
        return Decision(Mode.Invalid, "", LlmOutputParser.Parsed("", ""))
    }
}
```

（`LlmOutputParser.Parsed` 是公开 data class，可直接构造空实例。）

- [ ] **Step 4: 跑测试通过**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapEditTest"`
Expected: `BUILD SUCCESSFUL`，9 例绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/MapEdit.kt app/src/test/java/com/tingsiwei/app/MapEditTest.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): 改/删/加 指令解析与 diff/全量/无效三态判定" --no-verify
```

---

### Task 3: `MapEditApplier` 树操作

**Files:**
- Create: `app/src/main/java/com/tingsiwei/app/llm/MapEditApplier.kt`
- Test: `app/src/test/java/com/tingsiwei/app/MapEditApplierTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.tingsiwei.app

import com.tingsiwei.app.llm.MapEdit
import com.tingsiwei.app.llm.MapEditApplier
import com.tingsiwei.app.mindmap.TreeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapEditApplierTest {

    private fun tree(): List<TreeNote> = listOf(
        TreeNote("A", mutableListOf(TreeNote("A1"), TreeNote("A2", mutableListOf(TreeNote("A2a"))))),
        TreeNote("B", mutableListOf(TreeNote("B1"))),
    )

    private fun ops(vararg e: MapEdit) = e.toList()

    @Test fun renameKeepsSubtreeAndUntouchedNodesByteIdentical() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(MapEdit.Rename("1.2", "A2改名")))!!
        assertEquals(
            listOf("A", "\tA1", "\tA2改名", "\t\tA2a", "B", "\tB1"),
            com.tingsiwei.app.mindmap.TreeText.serialize(out.forest).lines(),
        )
        assertEquals(1, out.report.applied)
    }

    @Test fun deleteRemovesWholeSubtree() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(MapEdit.Delete("1.2")))!!
        assertEquals(listOf("A", "\tA1", "B", "\tB1"),
            com.tingsiwei.app.mindmap.TreeText.serialize(out.forest).lines())
    }

    @Test fun addAppendsInOrderAndDeleteThenAddRedrawsBranch() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(
            MapEdit.Delete("2.1"),
            MapEdit.AddChild("2", "新1"),
            MapEdit.AddChild("2", "新2"),
        ))!!
        assertEquals(listOf("A", "\tA1", "\tA2", "\t\tA2a", "B", "\t新1", "\t新2"),
            com.tingsiwei.app.mindmap.TreeText.serialize(out.forest).lines())
    }

    @Test fun pathsResolveAgainstOriginalTreeEvenAfterDelete() {
        val t = tree()
        // 删 1 之后仍可用原编号改 B——先解析后应用，删操作不使后续编号错位
        val out = MapEditApplier.apply(t, ops(MapEdit.Delete("1"), MapEdit.Rename("2", "B改名")))!!
        assertEquals("B改名", out.forest.single().topic)
    }

    @Test fun unknownPathAndConflictWithDeleteIgnored() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(
            MapEdit.Delete("1"),
            MapEdit.Rename("1.1", "随父删除无意义"),
            MapEdit.AddChild("9.9", "不存在"),
        ))!!
        assertEquals(1, out.report.applied)
        assertEquals(2, out.report.ignored)
        assertEquals(listOf("B", "\tB1"), com.tingsiwei.app.mindmap.TreeText.serialize(out.forest).lines())
    }

    @Test fun emptyResultGuardRejectsWholeApplication() {
        val t = tree()
        assertNull(MapEditApplier.apply(t, ops(MapEdit.Delete("1"), MapEdit.Delete("2"))))
    }

    @Test fun duplicateDeleteCountsOnce() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(MapEdit.Delete("1.1"), MapEdit.Delete("1.1")))!!
        assertEquals(1, out.report.applied)
        assertEquals(1, out.report.ignored)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapEditApplierTest"`
Expected: 编译错误 `unresolved reference: MapEditApplier`

- [ ] **Step 3: 实现**

新建 `MapEditApplier.kt`：

```kotlin
package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote

/**
 * 指令应用：所有路径先对原树解析成引用，再统一执行（删 → 摘链 → 改 → 加）。
 * 返回 null = 应用后导图为空，整次改动作废。未点名节点在结构上不经模型，逐字不变。
 */
object MapEditApplier {

    class Report(val applied: Int, val ignored: Int, val reasons: List<String>)
    class Outcome(val forest: List<TreeNote>, val report: Report)

    fun apply(forest: List<TreeNote>, ops: List<MapEdit>): Outcome? {
        val byPath = MapDiffText.indexByPath(forest)
        val parents = MapDiffText.parentIndex(forest)
        val deleted = HashSet<TreeNote>()
        var applied = 0
        var ignored = 0
        val reasons = ArrayList<String>()

        fun ignore(path: String, why: String) {
            ignored++
            if (reasons.size < 3) reasons.add("$path $why")
        }

        /** 节点自身或其任一祖先被删，就视为已不可达 */
        fun reachable(n: TreeNote): Boolean {
            var cur: TreeNote? = n
            while (cur != null) {
                if (cur in deleted) return false
                cur = parents[cur]
            }
            return true
        }

        for (op in ops.filterIsInstance<MapEdit.Delete>()) {
            val node = byPath[op.path] ?: run { ignore(op.path, "位置不存在"); continue }
            if (!reachable(node)) { ignore(op.path, "已随父枝删除"); continue }
            deleted.add(node)
            applied++
        }
        for (n in deleted) parents[n]?.children?.remove(n)

        for (op in ops) when (op) {
            is MapEdit.Delete -> Unit
            is MapEdit.Rename -> {
                val node = byPath[op.path] ?: run { ignore(op.path, "位置不存在"); continue }
                if (!reachable(node)) { ignore(op.path, "已被删除"); continue }
                node.topic = op.topic
                applied++
            }
            is MapEdit.AddChild -> {
                val node = byPath[op.path] ?: run { ignore(op.path, "位置不存在"); continue }
                if (!reachable(node)) { ignore(op.path, "已被删除"); continue }
                node.children.add(TreeNote(op.topic))
                applied++
            }
        }

        val result = forest.filterNot { it in deleted }
        if (result.isEmpty()) return null
        return Outcome(result, Report(applied, ignored, reasons))
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.MapEditApplierTest"`
Expected: `BUILD SUCCESSFUL`，7 例绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/MapEditApplier.kt app/src/test/java/com/tingsiwei/app/MapEditApplierTest.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): 指令应用器——原树快照解析、删改加定序、空图守卫" --no-verify
```

---

### Task 4: `Prompts` 改写提示词

**Files:**
- Modify: `app/src/main/java/com/tingsiwei/app/llm/Prompts.kt`（`reviseUser` 之前或之后追加一组函数；旧 `reviseUser` 保留给全量兜底）

- [ ] **Step 1: 追加提示词函数**

在 `object Prompts` 内（`reviseUser` 上方）加入：

```kotlin
    /** 最小化改写：模型只输出编辑指令，程序本地应用，未点名节点逐字保留 */
    fun reviseDiffSystem(): String = """
你是学习助手的「编辑模式」。给你一张带路径编号的思维导图（每行开头的 1、1.2、2.3.1 是节点地址）和当前思路，请按修改要求做**最小化编辑**：
- 只输出修改指令，一行一条，放在 <修改> 标签里：
改 <路径> = 新的节点文字
删 <路径>
加 <路径> = 新增子节点文字
- 路径必须来自给定编号，指向修改前的树；「删」会连同子树一起删除
- 重画某棵子树：先逐条「删」它的子节点，再逐条「加」新子节点
- 未被指令点名的节点会逐字保留——绝不要顺手润色、合并或重排
- 思路基本不用动；确需同步时，在 </修改> 之后另起 <思路>…</思路> 给出整段新文字
- 只有整体重构级别的改动，才放弃指令、改为输出完整 <导图>…</导图> 与 <思路>…</思路>
- 不要输出解释、不要套代码块
""".trim()

    fun reviseDiffUser(numberedMap: String, thinking: String, suggestion: String): String = buildString {
        appendLine("【当前思维导图（行首为节点路径编号）】")
        appendLine(numberedMap)
        if (thinking.isNotBlank()) {
            appendLine()
            appendLine("【当前思路】")
            appendLine(thinking)
        }
        appendLine()
        appendLine("【我的修改要求】")
        appendLine(suggestion)
        append("请按最小化编辑输出 <修改> 指令块。")
    }.trim()

    /** diff 输出没法应用时的纠正提示 */
    fun diffReminder(): String =
        "\n\n（注意：上一次的输出无法应用。请只输出 <修改>…</修改> 指令块，每行形如「改 <路径> = 新文字」「删 <路径>」「加 <路径> = 新文字」；确属整体重构才输出完整 <导图>…</导图> 与 <思路>…</思路>。）"

    /** diff 输出被截断时的降级规则：指令继续压缩，压不下去就升级全量 */
    fun diffTrimRule(): String =
        "体量要求：指令行最多 20 条；确实需要更多改动，请改为输出完整 <导图>…</导图> 与 <思路>…</思路>。"

    /** 全量兜底路径的硬规则：只改建议涉及的部分，其余逐字保留 */
    fun majorKeepRule(): String =
        "修改纪律：只按我的要求修改涉及的节点与思路，其余节点逐字保留，不得顺手润色或调整层级。"
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew.bat compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/Prompts.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): 最小编辑提示词组（diff 指令/提醒/降级/逐字保留规则）" --no-verify
```

---

### Task 5: `LlmClient.chat` 支持自定义降级规则

**Files:**
- Modify: `app/src/main/java/com/tingsiwei/app/llm/LlmClient.kt:35-52`
- Test: `app/src/test/java/com/tingsiwei/app/LlmPolicyTest.kt`（追加一例）

- [ ] **Step 1: 写失败测试（追加到 `LlmPolicyTest` 类末尾）**

先读现有 `LlmPolicyTest.kt` 的假 Transport 写法，仿其风格追加（假实现名以现有文件为准，下面示例中的 `FakeTransport` 需替换为文件中实际类名；若现有假实现不便复用，就新建局部假类）：

```kotlin
    @Test fun chatWithCustomDegradeRuleUsesItOnTruncation() = runBlocking {
        var seenSystem = ""
        val transport = object : Transport {
            var hits = 0
            override fun post(url: String, headers: Map<String, String>, body: String): RawResp {
                hits++
                return if (hits == 1) RawResp(200, sseWithLengthFinish("部分内容"), mapOf())
                else { seenSystem = Regex("\"content\":\\s*\"([^\"]*)\"").findAll(body).last().groupValues[1]
                       RawResp(200, sseDone("<修改>\n改 1 = 甲\n</修改>"), mapOf()) }
            }
            override fun get(url: String, headers: Map<String, String>) = RawResp(200, "{}", mapOf())
        }
        // 直接测 LlmSession：degradeRule 必须出现在重问请求里
        val session = LlmSession(transport, gate = RateGate(), sleeper = NoopSleeper)
        session.chatWithDegrade("u", "k", "m", "SYS", "USER", degradeRule = "自定义体量规则")
        assertTrue(seenSystem.contains("自定义体量规则"))
    }
```

辅助函数 `sseWithLengthFinish`/`sseDone` 若不存在则新建（拼最小 SSE：`data: {"choices":[{"delta":{"content":"…"}}]}` + `data: {"choices":[{"finish_reason":"length"}]}` / `data: [DONE]`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.LlmPolicyTest"`
Expected: 新用例 FAIL（`chatWithDegrade` 现在把规则拼成 `$system\n$degradeRule`——断言点先看失败原因再修正断言；若第一版就绿，说明语义已满足，直接进 Step 3）

- [ ] **Step 3: 给 `LlmClient.chat` 加参数**

```kotlin
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int? = null,
        degradeRule: String = Prompts.trimRule(),
    ): String = withContext(Dispatchers.IO) {
        session().chatWithDegrade(
            baseUrl = endpoint(baseUrl),
            apiKey = apiKey,
            model = model,
            system = system,
            user = user,
            desiredOutTokens = maxTokens ?: LlmPolicy.DEFAULT_MAX_OUTPUT_TOKENS,
            degradeRule = degradeRule,
        )
    }
```

- [ ] **Step 4: 跑测试通过（含全文件回归）**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.LlmPolicyTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/LlmClient.kt app/src/test/java/com/tingsiwei/app/LlmPolicyTest.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(llm): chat 支持自定义截断降级规则，diff 路径不再套导图体量提示" --no-verify
```

---

### Task 6: `ReviseFlow` 纯决策层

**Files:**
- Create: `app/src/main/java/com/tingsiwei/app/llm/ReviseFlow.kt`
- Test: `app/src/test/java/com/tingsiwei/app/ReviseFlowTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.tingsiwei.app

import com.tingsiwei.app.llm.LlmError
import com.tingsiwei.app.llm.ReviseFlow
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviseFlowTest {

    private fun ctx() = ReviseFlow.Context(
        forest = listOf(
            TreeNote("A", mutableListOf(TreeNote("A1"), TreeNote("A2"))),
            TreeNote("B"),
        ),
        thinking = "旧思路",
        originalContent = "原文若干",
        suggestion = "把 A2 改成 A2改",
        fullSystem = "FULL-SYS",
    )

    /** 记录调用的假 Ask：按脚本回话，Truncated 用异常抛 */
    private class FakeAsk(vararg replies: Any) : ReviseFlow.Ask {
        val diffCalls = ArrayList<Pair<String, String>>()
        val fullCalls = ArrayList<Pair<String, String>>()
        private val queue = ArrayDeque<Any>().apply { replies.forEach { add(it) } }
        override suspend fun diff(system: String, user: String): String {
            diffCalls += system to user
            return take()
        }
        override suspend fun full(system: String, user: String): String {
            fullCalls += system to user
            return take()
        }
        private fun take(): String = when (val r = if (queue.isEmpty()) "改 1 = 兜底" else queue.removeFirst()) {
            is LlmError -> throw r
            else -> r as String
        }
    }

    private fun ReviseFlow.Result.expectSuccess(): ReviseResult.Success {
        assertTrue(this is ReviseResult.Success)
        return this as ReviseResult.Success
    }

    @Test fun diffHappyPathKeepsUntouchedNodesByteIdentical() = runBlocking {
        val ask = FakeAsk("<修改>\n改 1.2 = A2改\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("A", "\tA1", "\tA2改", "B"), TreeText.serialize(out.forest).lines())
        assertNull(out.thinking) // 没给新思路 = 保留旧思路，由上层 orElse
        assertNull(out.notice)
        assertEquals(1, ask.diffCalls.size)
        assertTrue(ask.diffCalls.first().second.contains("1.2"))
    }

    @Test fun thinkingTagReplacesThinking() = runBlocking {
        val ask = FakeAsk("<修改>\n改 1.2 = A2改\n</修改>\n<思路>\n新思路一段\n</思路>")
        assertEquals("新思路一段", ReviseFlow.run(ctx(), ask).expectSuccess().thinking)
    }

    @Test fun invalidOutputRetriedWithReminderThenApplied() = runBlocking {
        val ask = FakeAsk("抱歉做不到", "<修改>\n加 2 = B1\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(2, ask.diffCalls.size)
        assertTrue(ask.diffCalls[1].second.contains("无法应用"))
        assertEquals(listOf("A", "\tA1", "\tA2", "B", "\tB1"), TreeText.serialize(out.forest).lines())
    }

    @Test fun truncatedSalvagesCompleteLines() = runBlocking {
        // partialContent 最后一行没有换行 = 被砍断，必须丢掉；完整行照常应用
        val ask = FakeAsk(LlmError.Truncated("<修改>\n改 1 = 甲改\n删 1.2"))
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("甲改", "\tA1", "B"), TreeText.serialize(out.forest).lines())
        assertTrue(out.notice!!.contains("截断"))
    }

    @Test fun bothDiffAttemptsUselessFallsBackToFullRewrite() = runBlocking {
        val ask = FakeAsk(
            "胡言乱语", "还是胡言乱语",
            "<导图>\n新根\n\t新子\n</导图>\n<思路>\n全新思路\n</思路>",
        )
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(1, ask.fullCalls.size)
        assertTrue(ask.fullCalls.first().first.contains("逐字保留"))
        assertEquals(listOf("新根", "\t新子"), TreeText.serialize(out.forest).lines())
        assertEquals("全新思路", out.thinking)
    }

    @Test fun fullRewriteAlsoTruncatedFailsWithoutTouchingOldMap() = runBlocking {
        val ask = FakeAsk(
            "无", "无",
            LlmError.Truncated("<导图>\n半截"),
        )
        val r = ReviseFlow.run(ctx(), ask)
        assertTrue(r is ReviseResult.Fail)
        assertTrue((r as ReviseResult.Fail).reason.contains("拆小"))
    }

    @Test fun wholeMapDeleteRejectedAsEmptyGuard() = runBlocking {
        val ask = FakeAsk("<修改>\n删 1\n删 2\n</修改>", "无", "无", "<导图>\n根\n</导图>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(1, ask.fullCalls.size) // 走了兜底而不是应用空图
        assertEquals("根", out.forest.single().topic)
    }

    @Test fun modelDirectlyEmitsWholeMapTreatedAsReplacement() = runBlocking {
        val ask = FakeAsk("<导图>\n主题\n\t甲\n</导图>\n<思路>\n段\n</思路>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertEquals(listOf("主题", "\t甲"), TreeText.serialize(out.forest).lines())
        assertTrue(ask.diffCalls.isEmpty().not()) // diff 问过一次，classify 出 Full 直接采纳
        assertNull(ask.let { it })
    }

    @Test fun ignoredPathsReportedInNotice() = runBlocking {
        val ask = FakeAsk("<修改>\n改 9.9 = 不存在\n改 1.2 = A2改\n</修改>")
        val out = ReviseFlow.run(ctx(), ask).expectSuccess()
        assertTrue(out.notice!!.contains("1 处"))
    }
}
```

（注：`modelDirectlyEmitsWholeMapTreatedAsReplacement` 最后一行 `assertNull(ask.let { it })` 是笔误写法，执行时替换为对 `ask.diffCalls.size == 1` 的断言。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.ReviseFlowTest"`
Expected: 编译错误 `unresolved reference: ReviseFlow`

- [ ] **Step 3: 实现**

新建 `ReviseFlow.kt`（与 `StagedFlow` 同风格的 internal 决策层）：

```kotlin
package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.LlmOutputParser
import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText

/** 改写结果：Success 交给 Generator 写库；Fail 的 reason 由 Generator 包「修改失败：…」前缀 */
sealed class ReviseResult {
    class Success(val forest: List<TreeNote>, val thinking: String?, val notice: String?) : ReviseResult()
    class Fail(val reason: String) : ReviseResult()
}

/**
 * 改写的纯决策层（仿 StagedFlow，不碰接口与库，测试注入假 [Ask]）：
 * diff 主问 → 提醒重问 → 全量降级；截断按行抢救。最多两次 diff 问 + 一次全量问。
 */
internal object ReviseFlow {

    interface Ask {
        /** diff 模式：chatWithDegrade 的降级规则用 Prompts.diffTrimRule() */
        suspend fun diff(system: String, user: String): String
        /** 全量兜底：现行单次全图重发 */
        suspend fun full(system: String, user: String): String
    }

    data class Context(
        val forest: List<TreeNote>,
        val thinking: String,
        val originalContent: String,
        val suggestion: String,
        /** 全量兜底的 system：generateSystem(allowExpand) + majorKeepRule，由 Generator 拼 */
        val fullSystem: String,
    )

    suspend fun run(ctx: Context, ask: Ask): ReviseResult {
        val system = Prompts.reviseDiffSystem()
        val user = Prompts.reviseDiffUser(MapDiffText.render(ctx.forest), ctx.thinking, ctx.suggestion)
        var salvage = false

        val decision: ReviseOutput.Decision
        try {
            decision = classifyOrSalvage(ask.diff(system, user), ::salvageFlag)
        } catch (e: LlmError.Truncated) {
            salvage = true
            decision = classifyOrSalvage(dropIncompleteLastLine(e.partialContent), ::salvageFlag)
        }
        val first = decision
        val finalDecision: ReviseOutput.Decision
        val finalSalvage: Boolean
        if (first.mode == ReviseOutput.Mode.Invalid) {
            salvage = false
            try {
                finalDecision = classifyOrSalvage(ask.diff(system, user + Prompts.diffReminder()), ::salvageFlag)
                finalSalvage = false
            } catch (e: LlmError.Truncated) {
                finalSalvage = true
                finalDecision = classifyOrSalvage(dropIncompleteLastLine(e.partialContent), ::salvageFlag)
            }
        } else {
            finalDecision = first
            finalSalvage = salvage
        }

        return when (finalDecision.mode) {
            ReviseOutput.Mode.Diff -> applyDiff(ctx, ask, finalDecision, finalSalvage)
            ReviseOutput.Mode.Full -> acceptFull(finalDecision.parsed, finalSalvage)
                ?: fallback(ctx, ask)
            ReviseOutput.Mode.Invalid -> fallback(ctx, ask)
        }
    }

    private fun salvageFlag() = Unit // 占位以统一签名——见下：分类不感知抢救，抢救标志由外层维护

    private fun classifyOrSalvage(raw: String, marker: () -> Unit): ReviseOutput.Decision =
        ReviseOutput.classify(raw)

    private suspend fun applyDiff(
        ctx: Context,
        ask: Ask,
        decision: ReviseOutput.Decision,
        salvage: Boolean,
    ): ReviseResult {
        val (ops, badLines) = MapEdit.parseAll(decision.editBlock)
        if (ops.isEmpty()) return fallback(ctx, ask)
        val applied = MapEditApplier.apply(ctx.forest, ops)
            ?: return ReviseResult.Fail("指令会把导图清空。可以把要求拆小一点再试")
        val notice = buildNotice(applied.report, badLines, salvage)
        return ReviseResult.Success(applied.forest, decision.parsed.thinking.ifBlank { null }, notice)
    }

    private fun acceptFull(parsed: LlmOutputParser.Parsed, salvage: Boolean): ReviseResult? {
        val forest = TreeText.parse(parsed.mapText)
        if (forest.isEmpty()) return null
        return ReviseResult.Success(
            forest,
            parsed.thinking.ifBlank { null },
            if (salvage) "输出被长度上限截断，已按完整部分应用，建议检查导图是否完整" else null,
        )
    }

    /** 全量兜底：现行"整图重发"，system 带逐字保留硬规则 */
    private suspend fun fallback(ctx: Context, ask: Ask): ReviseResult {
        val user = Prompts.reviseUser(
            TreeText.serialize(ctx.forest), ctx.thinking, ctx.originalContent, ctx.suggestion,
        )
        val raw = try {
            ask.full(ctx.fullSystem, user)
        } catch (e: LlmError.Truncated) {
            return ReviseResult.Fail("本次改动输出太长被截断。可以把要求拆小一点再试")
        }
        val parsed = LlmOutputParser.parse(raw)
        val forest = TreeText.parse(parsed.mapText)
        if (forest.isEmpty()) return ReviseResult.Fail("AI 返回的导图是空的，请重试或换个说法")
        return ReviseResult.Success(forest, parsed.thinking.ifBlank { null }, null)
    }

    /** 截断输出的尾行几乎总是没写完：丢掉未闭合尾行，其余完整指令行照常可用 */
    private fun dropIncompleteLastLine(partial: String): String {
        if (partial.isEmpty() || partial.endsWith("\n")) return partial
        val cut = partial.lastIndexOf('\n')
        return if (cut < 0) "" else partial.substring(0, cut + 1)
    }

    private fun buildNotice(report: MapEditApplier.Report, badLines: Int, salvage: Boolean): String? {
        val problems = ArrayList<String>()
        val skipped = report.ignored + badLines
        if (skipped > 0) problems.add("$skipped 处位置没对上或被忽略")
        if (salvage) problems.add("输出被截断，未送达的指令没有应用")
        if (problems.isEmpty()) return null
        return "本次应用 ${report.applied} 处修改，${problems.joinToString("，")}"
    }
}
```

实现时把 `salvageFlag`/`classifyOrSalvage` 的占位写法收敛为简单形式：classify 不需要标记参数，直接 `ReviseOutput.classify(raw)`；`salvage` 标志在外层变量维护（上面代码以可读性优先，落地时删掉 `salvageFlag`，`classifyOrSalvage` 改名 `ReviseOutput.classify` 直调）。

- [ ] **Step 4: 跑测试通过**

Run: `./gradlew.bat testDebugUnitTest --no-daemon --tests "com.tingsiwei.app.ReviseFlowTest"`
Expected: `BUILD SUCCESSFUL`，9 例绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/ReviseFlow.kt app/src/test/java/com/tingsiwei/app/ReviseFlowTest.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): 纯决策层 ReviseFlow——diff→提醒→全量降级失败链与截断行抢救" --no-verify
```

---

### Task 7: `Generator.revise` 接线

**Files:**
- Modify: `app/src/main/java/com/tingsiwei/app/llm/Generator.kt:87-155`
- Test: `app/src/test/java/com/tingsiwei/app/LongTextPipelineTest.kt` 不动；全量回归

- [ ] **Step 1: 替换 revise 主体**

保留「快照 + GENERATING」前置逻辑（90-105 行）与 catch 结构，把 try 体改为：

```kotlin
            onStage("正在按你的要求修改…")
            val cfg = settings.current()
            val forest = TreeText.parse(TreeText.fromMapData(oldMap, note.virtualRoot))
            if (forest.isEmpty()) throw LlmException("当前导图数据异常，请先生成一次")
            val result = ReviseFlow.run(
                ReviseFlow.Context(
                    forest = forest,
                    thinking = note.thinking.orEmpty(),
                    originalContent = note.content.orEmpty(),
                    suggestion = suggestion,
                    fullSystem = Prompts.generateSystem(cfg.allowExpand) + "\n" + Prompts.majorKeepRule(),
                ),
                ask = object : ReviseFlow.Ask {
                    override suspend fun diff(system: String, user: String) =
                        client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user,
                            FinalOutTokens, degradeRule = Prompts.diffTrimRule())
                    override suspend fun full(system: String, user: String) =
                        client.chat(cfg.llmUrl, cfg.llmKey, cfg.llmModel, system, user)
                },
            )
            when (result) {
                is ReviseResult.Success -> {
                    val (mapJson, virtualRoot) = TreeText.toMapData(result.forest, note.title)
                    dao.update(
                        note.copy(
                            mapJson = mapJson,
                            thinking = result.thinking ?: note.thinking,
                            virtualRoot = virtualRoot,
                            status = NoteStatus.READY,
                            errorMsg = result.notice,
                            updatedAt = now(),
                        )
                    )
                }
                is ReviseResult.Fail -> dao.update(
                    note.copy(
                        status = NoteStatus.ERROR,
                        errorMsg = "修改失败：${result.reason}（原导图未改动，可重新提一次要求）",
                        updatedAt = now(),
                    )
                )
            }
```

`catch (e: LlmError.Truncated)` 保留为防御分支（ReviseFlow 已内部消化截断，正常不再走到，保留原文案）。注意删除旧的 `Prompts.formatReminder()` 直连逻辑——已由 ReviseFlow 的 reminder 取代。

- [ ] **Step 2: 全量单测回归**

Run: `./gradlew.bat testDebugUnitTest --no-daemon`
Expected: 既有 98 例 + 新增 4+9+7+9+1 例全绿，`BUILD SUCCESSFUL`

- [ ] **Step 3: 组装验证**

Run: `./gradlew.bat assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tingsiwei/app/llm/Generator.kt
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "feat(revise): 改写接线到 ReviseFlow——成功应用/失败链/提示条通知全走新链路" --no-verify
```

---

### Task 8: 文档与实测清单

**Files:**
- Modify: `docs/模块总览/生成流水线模块总览.md`（revise 段改写为新链路）
- Modify: `docs/迭代计划/用户实测清单-v1.md`（追加实测项）

- [ ] **Step 1: 更新模块总览**

在「生成流水线模块总览」的修改流程段落改写为：快照 → `MapDiffText.render` 编号 → diff 主问（`ReviseFlow.run`）→ 提醒重问 → 全量降级；指令 `改/删/加`，未点名节点逐字保留；截断按行抢救；`errorMsg` 在 READY 态承载「应用 N 处/忽略 M 处」通知。

- [ ] **Step 2: 追加实测清单**

```markdown
## 最小化改写（本次迭代）
- [ ] 非 flash 模型：对 20+ 节点导图提「改某节点措辞」→ 一次成功，无「输出太长被截断」
- [ ] 未点名节点逐字未变；手动编辑过的节点文字保持不变
- [ ] 要求「重新梳理整个导图」→ 走全量替换，结果完整
- [ ] 故意提超范围大要求 → 失败提示「修改失败」，旧图完好，版本回退可用
- [ ] 失败后建议文字仍留在输入框（现有行为回归）
```

- [ ] **Step 3: 提交**

```bash
git add docs/模块总览/生成流水线模块总览.md docs/迭代计划/用户实测清单-v1.md
git -c user.name="Li Wenbin" -c user.email="dev@local" commit -m "docs(revise): 新改写链路的模块总览与实测清单" --no-verify
```

---

## Self-review 记录

- **Spec 覆盖**：§3.1 流程→Task 6/7；§3.2 DSL→Task 2/3（路径解析先行、`=` 分隔、空图守卫均有对应测试）；§3.3 文件清单全部有任务；§3.4 兼容→Task 7（写库语义、"修改失败"前缀、errorMsg 通知位经 `DetailScreen.kt:169` 验证）；§4 测试→各 Step + Task 8 清单。
- **占位符**：Task 1/2/3/5/6 的测试与实现代码完整；Task 5 Step 1 的假 Transport 类名与 Task 6 的占位函数落地时按注明方式收敛——两处均给出唯一确定的收敛规则，非开放占位。
- **类型一致性**：`ReviseFlow.Ask/Context/Result`、`ReviseResult.Success/Fail`、`MapEditApplier.Outcome/Report`、`ReviseOutput.Decision/Mode` 在各任务间引用一致；`MapEditApplier.Report(applied, ignored, reasons)` 与 Task 6 `buildNotice` 字段一致。
