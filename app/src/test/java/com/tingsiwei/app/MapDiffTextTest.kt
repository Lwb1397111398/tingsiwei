package com.tingsiwei.app

import com.tingsiwei.app.mindmap.MapDiffText
import com.tingsiwei.app.mindmap.TreeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 改写的编号视图：深度优先路径编号渲染、点分路径定位、全树索引与父节点表。
 * 编号只在改写提示词里流转，这里验证与树结构严格互逆。
 */
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
