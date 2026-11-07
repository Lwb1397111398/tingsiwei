package com.tingsiwei.app

import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.llm.CategoryMergeFlow
import com.tingsiwei.app.mindmap.CategoryMerger
import com.tingsiwei.app.mindmap.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CategoryMergeTest {

    // ---------- 造数据 ----------

    /** 本时区某天上午的时间戳（分支名日期断言依赖本机 GMT+8） */
    private fun ts(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 10, 0, 0)
        }.timeInMillis

    private fun note(title: String, createdAt: Long, outline: String): NoteEntity {
        val (mapJson, virtualRoot) = TreeText.toMapData(TreeText.parse(outline), title)
        return NoteEntity(
            id = 0,
            title = title,
            createdAt = createdAt,
            updatedAt = createdAt,
            status = "READY",
            source = "RECORD",
            mapJson = mapJson,
            virtualRoot = virtualRoot,
        )
    }

    // ---------- CategoryMerger ----------

    @Test
    fun `拼接根为分类名且分支按时间正序`() {
        val a = note("录音 09-01 上午", ts(2026, 9, 1), "民法概述\n\t调整对象\n\t平等原则")
        val b = note("录音 09-08 下午", ts(2026, 9, 8), "法人制度\n\t营利法人\n\t非营利法人")
        val r = CategoryMerger.merge(listOf(a, b), "民法总则")!!
        assertTrue(r.mapJson.contains("\"topic\":\"民法总则\""))
        val first = r.mapJson.indexOf("9月1日 · 录音 09-01 上午")
        val second = r.mapJson.indexOf("9月8日 · 录音 09-08 下午")
        assertTrue(first in 0 until second)
        assertEquals(2, r.noteCount)
        assertFalse(r.virtualRoot)
        // 各篇内容原样挂在对应分支下，零丢失
        assertTrue(r.mapJson.contains("调整对象") && r.mapJson.contains("非营利法人"))
    }

    @Test
    fun `拼接传参乱序也按 createdAt 重排`() {
        val late = note("晚的", ts(2026, 10, 8), "后讲的\n\t乙")
        val early = note("早的", ts(2026, 9, 1), "先讲的\n\t甲")
        val r = CategoryMerger.merge(listOf(late, early), "课")!!
        assertTrue(r.mapJson.indexOf("9月1日 · 早的") < r.mapJson.indexOf("10月8日 · 晚的"))
    }

    @Test
    fun `空列表与全空导图返回 null`() {
        assertNull(CategoryMerger.merge(emptyList(), "分类"))
        val empty = note("没图", ts(2026, 9, 1), "只有根")
            .copy(mapJson = null)
        assertNull(CategoryMerger.merge(listOf(empty), "分类"))
    }

    @Test
    fun `单篇分类也包分类名根`() {
        val a = note("录音 09-01", ts(2026, 9, 1), "唯一一节\n\t要点")
        val r = CategoryMerger.merge(listOf(a), "单课")!!
        assertTrue(r.mapJson.contains("\"topic\":\"单课\""))
        assertTrue(r.mapJson.contains("9月1日 · 录音 09-01"))
        assertEquals(1, r.noteCount)
    }

    @Test
    fun `分支名清洗换行并截断长标题`() {
        val long = note("很".repeat(50) + "\n第二行", ts(2026, 9, 1), "根")
        val topic = CategoryMerger.branchTopic(long)
        assertTrue(topic.startsWith("9月1日 · "))
        assertFalse(topic.contains('\n'))
        assertTrue(topic.removePrefix("9月1日 · ").length <= 30)
    }

    // ---------- CategoryMergeFlow 静态部分 ----------

    @Test
    fun `pruneDepth 按深度裁剪缩进树`() {
        val text = "A\n\tB\n\t\tC\n\t\t\tD\nE"
        assertEquals("A\n\tB\nE", CategoryMergeFlow.pruneDepth(text, 2))
        assertEquals("A\n\tB\n\t\tC\nE", CategoryMergeFlow.pruneDepth(text, 3))
        assertEquals("", CategoryMergeFlow.pruneDepth("", 2))
    }

    @Test
    fun `buildInput 默认保留前三层`() {
        val n = note("t", ts(2026, 9, 1), "根\n\t一层\n\t\t二层\n\t\t\t三层")
        val input = CategoryMergeFlow.buildInput(listOf(n), 1_000_000)
        assertTrue(input.contains("### 9月1日 · t"))
        assertTrue(input.contains("二层"))
        assertFalse(input.contains("三层"))
    }

    @Test
    fun `buildInput 超预算时砍到深层被裁`() {
        val n = note("大篇", ts(2026, 9, 1), bigOutline())
        val input = CategoryMergeFlow.buildInput(listOf(n), 8_000)
        assertFalse(input.contains("第三层"))
        assertTrue(input.contains("第二层"))
        assertTrue(input.contains("### "))
    }

    @Test
    fun `mergeTitle 清洗分类名并截断`() {
        assertEquals("民法 总则·总导图", CategoryMergeFlow.mergeTitle("民法\n总则"))
        assertEquals("民法·总导图", CategoryMergeFlow.mergeTitle("民法"))
        assertEquals("课".repeat(20) + "·总导图", CategoryMergeFlow.mergeTitle("课".repeat(30) + "\nx"))
    }

    /** ~5000 token 的三层大纲，用来触发深度降级 */
    private fun bigOutline(): String {
        val sb = StringBuilder("根主题\n")
        for (i in 0 until 8) {
            sb.append("\t第一层要点").append(i).append("：").append("内容".repeat(60)).append('\n')
            for (j in 0 until 2) {
                sb.append("\t\t第二层要点").append(i).append(j).append("：").append("细节".repeat(60)).append('\n')
                sb.append("\t\t\t第三层细节").append(i).append(j).append("：").append("补充".repeat(60)).append('\n')
            }
        }
        return sb.toString()
    }
}
