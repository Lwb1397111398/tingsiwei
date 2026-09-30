package com.tingsiwei.app

import com.tingsiwei.app.llm.MapEdit
import com.tingsiwei.app.llm.MapEditApplier
import com.tingsiwei.app.mindmap.TreeNote
import com.tingsiwei.app.mindmap.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 指令应用语义：全部路径先对原树解析再统一执行；未点名节点逐字不变；
 * 空图守卫拒绝整树删除；与删除冲突的指令计入忽略。
 */
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
            TreeText.serialize(out.forest).lines(),
        )
        assertEquals(1, out.report.applied)
    }

    @Test fun deleteRemovesWholeSubtree() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(MapEdit.Delete("1.2")))!!
        assertEquals(
            listOf("A", "\tA1", "B", "\tB1"),
            TreeText.serialize(out.forest).lines(),
        )
    }

    @Test fun addAppendsInOrderAndDeleteThenAddRedrawsBranch() {
        val t = tree()
        val out = MapEditApplier.apply(t, ops(
            MapEdit.Delete("2.1"),
            MapEdit.AddChild("2", "新1"),
            MapEdit.AddChild("2", "新2"),
        ))!!
        assertEquals(
            listOf("A", "\tA1", "\tA2", "\t\tA2a", "B", "\t新1", "\t新2"),
            TreeText.serialize(out.forest).lines(),
        )
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
        assertEquals(listOf("B", "\tB1"), TreeText.serialize(out.forest).lines())
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
