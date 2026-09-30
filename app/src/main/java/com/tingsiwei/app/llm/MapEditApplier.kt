package com.tingsiwei.app.llm

import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote

/**
 * 指令应用：所有路径先对原树解析成引用，再统一执行（删 → 摘链 → 改 → 加）。
 * 返回 null = 应用后导图为空，整次改动作废。未点名节点不经模型，逐字不变。
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
            val node = byPath[op.path]
            if (node == null) { ignore(op.path, "位置不存在"); continue }
            if (!reachable(node)) { ignore(op.path, "已随父枝删除"); continue }
            deleted.add(node)
            applied++
        }
        for (n in deleted) parents[n]?.children?.remove(n)

        for (op in ops) when (op) {
            is MapEdit.Delete -> Unit
            is MapEdit.Rename -> {
                val node = byPath[op.path]
                if (node == null) { ignore(op.path, "位置不存在"); continue }
                if (!reachable(node)) { ignore(op.path, "已被删除"); continue }
                node.topic = op.topic
                applied++
            }
            is MapEdit.AddChild -> {
                val node = byPath[op.path]
                if (node == null) { ignore(op.path, "位置不存在"); continue }
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
