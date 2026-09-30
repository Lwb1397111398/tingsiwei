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

    /** 按点分路径定位节点；格式非法或不存在返回 null */
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

    /** 节点 → 父节点。用引用同一性做键，避免同构子树在结构相等下串键 */
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
