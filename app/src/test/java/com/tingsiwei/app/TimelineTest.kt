package com.tingsiwei.app

import com.tingsiwei.app.transcribe.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放跟随时间轴：切句、块内时间分摊、系统识别行时刻、JSON 编解码、当前句二分查找。 */
class TimelineTest {

    // ---------- splitSentences ----------

    @Test
    fun `按句末标点切句`() {
        val out = Timeline.splitSentences("今天讲正当防卫。首先要背下来！考试重点；记住。")
        assertEquals(listOf("今天讲正当防卫。", "首先要背下来！", "考试重点；", "记住。"), out)
    }

    @Test
    fun `全无标点的短文本整块成一句`() {
        assertEquals(listOf("一句话说到头没有任何标点"), Timeline.splitSentences("一句话说到头没有任何标点"))
    }

    @Test
    fun `无标点长文按三十字硬切成段`() {
        // 70 字无标点（模拟实测 SenseVoice 输出）→ 30/30/10 三段
        val text = "字".repeat(70)
        val out = Timeline.splitSentences(text)
        assertEquals(listOf(30, 30, 10), out.map { it.length })
        assertEquals(text, out.joinToString(""))
    }

    @Test
    fun `硬切后拼回仍等于原文不丢字`() {
        val text = "今天我们讲正当防卫首先要记住法条依据刑法第二十条正当防卫有五个构成要件第一是起因条件必须存在现实的不法侵害"
        val out = Timeline.splitSentences(text)
        assertEquals(text, out.joinToString(""))
        assertTrue(out.all { it.length <= 30 })
        assertTrue(out.size >= 2)
    }

    @Test
    fun `换行也是断句边界`() {
        assertEquals(listOf("第一段。", "第二段"), Timeline.splitSentences("第一段。\n第二段"))
    }

    @Test
    fun `碎句并入前一句`() {
        // 省略号后的单个「。」只剩一个字符，并回前句，不单独成段
        assertEquals(listOf("继续……。"), Timeline.splitSentences("继续……。"))
    }

    @Test
    fun `英文标点同样断句`() {
        assertEquals(listOf("Okay!", "Right?"), Timeline.splitSentences("Okay! Right?"))
    }

    // ---------- buildSegments ----------

    @Test
    fun `块内时间按字数比例分摊`() {
        // 两句含句号各 5、4 字，摊 900ms：[0,500) [500,900)
        val out = Timeline.buildSegments("一二三四。五六七。", 0L, 900L)
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(500L, out[0].endMs)
        assertEquals(500L, out[1].startMs)
        assertEquals(900L, out[1].endMs)
        assertEquals("一二三四。", out[0].text)
    }

    @Test
    fun `单句占满整块`() {
        val out = Timeline.buildSegments("整块就一句话。", 200L, 900L)
        assertEquals(1, out.size)
        assertEquals(200L, out[0].startMs)
        assertEquals(900L, out[0].endMs)
    }

    @Test
    fun `分摊结果单调不减`() {
        val out = Timeline.buildSegments("甲。乙！丙？丁；戊。己。庚。辛。", 0L, 30_000L)
        for (i in 1 until out.size) {
            assertTrue(out[i].startMs >= out[i - 1].startMs)
            assertTrue(out[i].endMs >= out[i].startMs)
        }
        assertEquals(30_000L, out.last().endMs)
    }

    // ---------- buildFromLineArrivals ----------

    @Test
    fun `系统识别行时刻-首行从零起-行起点取上一行到达时刻`() {
        val out = Timeline.buildFromLineArrivals(listOf("甲段", "乙段", "丙段"), listOf(1000L, 2500L, 4000L), 4000L)
        assertEquals(listOf(0L to 1000L, 1000L to 2500L, 2500L to 4000L), out.map { it.startMs to it.endMs })
    }

    @Test
    fun `行数与到达数对不上退化为按总时长均摊`() {
        val out = Timeline.buildFromLineArrivals(listOf("甲段", "乙段"), listOf(1000L), 1000L)
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(1000L, out.last().endMs)
    }

    @Test
    fun `空行列表返回空`() {
        assertTrue(Timeline.buildFromLineArrivals(emptyList(), emptyList(), 1000L).isEmpty())
    }

    // ---------- encode / decode ----------

    @Test
    fun `编码解码往返无损`() {
        val segs = listOf(
            Timeline.Segment(0L, 15_200L, "好，那我们接着往下讲啊。"),
            Timeline.Segment(15_200L, 30_000L, "带\"引号\"和\\反斜杠、\n换行与\t制表符。"),
        )
        val decoded = Timeline.decode(Timeline.encode(segs))
        assertEquals(segs, decoded)
    }

    @Test
    fun `解码空入参返回空表`() {
        assertTrue(Timeline.decode(null).isEmpty())
        assertTrue(Timeline.decode("").isEmpty())
        assertTrue(Timeline.decode("[]").isEmpty())
    }

    @Test
    fun `解码损坏输入容错为空不抛异常`() {
        assertTrue(Timeline.decode("不是json").isEmpty())
        assertTrue(Timeline.decode("[{\"s\":0,\"e\":}]").isEmpty())
        assertTrue(Timeline.decode("[{\"s\":0,\"e\":100").isEmpty())
        // 起止倒挂的段视为脏数据丢弃
        assertTrue(Timeline.decode("[{\"s\":100,\"e\":50,\"t\":\"倒挂\"}]").isEmpty())
    }

    @Test
    fun `解码容忍未知键前向兼容`() {
        val out = Timeline.decode("[{\"s\":0,\"e\":100,\"t\":\"句子\",\"x\":999}]")
        assertEquals(1, out.size)
        assertEquals("句子", out[0].text)
    }

    // ---------- activeIndex ----------

    private val segs = listOf(
        Timeline.Segment(0L, 1000L, "一"),
        Timeline.Segment(1000L, 2500L, "二"),
        Timeline.Segment(2500L, 4000L, "三"),
    )

    @Test
    fun `空表返回负一`() {
        assertEquals(-1, Timeline.activeIndex(emptyList(), 100L))
    }

    @Test
    fun `起点之前归第一句`() {
        assertEquals(0, Timeline.activeIndex(segs, 0L))
    }

    @Test
    fun `边界时刻归后一句`() {
        assertEquals(1, Timeline.activeIndex(segs, 1000L))
        assertEquals(2, Timeline.activeIndex(segs, 2500L))
    }

    @Test
    fun `落在句中间与结尾之后各归其位`() {
        assertEquals(1, Timeline.activeIndex(segs, 2000L))
        assertEquals(2, Timeline.activeIndex(segs, 99_999L))
    }
}
