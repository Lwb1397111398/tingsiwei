package com.tingsiwei.app

import com.tingsiwei.app.transcribe.SpeechCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 出块点选择：静音中点、硬上限、碎块下限、流式覆盖不丢样。 */
class SpeechCutTest {

    private fun loud(n: Int) = ShortArray(n) { if (it % 40 < 20) 8000 else -8000 }
    private fun quiet(n: Int) = ShortArray(n) { (it % 7).toShort() }
    private fun level(n: Int, v: Int) = ShortArray(n) { v.toShort() }

    private fun join(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size }
        return out
    }

    @Test
    fun `缓冲区不足目标长度时不出块`() {
        val buf = join(loud(800), quiet(640))
        assertEquals(0, SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600))
    }

    @Test
    fun `攒够后优先切在静音中点`() {
        val buf = join(loud(1600), quiet(640), loud(1600))
        val cut = SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600)
        assertEquals("静音区 1600..2240 的中点", 1760, cut)
    }

    @Test
    fun `静音必须足够长才算边界`() {
        val buf = join(loud(1600), quiet(160), loud(1000))
        assertEquals(
            "只有一帧静音不该切", 0,
            SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600),
        )
    }

    @Test
    fun `全程有声时攒到硬上限才切`() {
        val buf = loud(3000)
        assertEquals(0, SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600))
        val big = loud(3600)
        assertEquals(3200, SpeechCut.pickCut(big, big.size, target = 1600, max = 3200, minSilence = 320, thresh = 600))
    }

    @Test
    fun `达到硬上限时宁可在更早的静音处切`() {
        val buf = join(loud(1000), quiet(640), loud(3000))
        val cut = SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600)
        assertEquals("静音区 1000..1640 内的中点，早于 target 但优于硬切句中", 1280, cut)
        assertTrue(cut > 0)
    }

    @Test
    fun `碎块下限 不会产出比 min 更短的块`() {
        val buf = join(quiet(400), loud(4000))
        val cut = SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600, min = 800)
        assertTrue("cut=$cut 必须不早于 min", cut >= 800)
    }

    @Test
    fun `全静音也要能出块且不越界`() {
        val buf = quiet(4000)
        val cut = SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600)
        assertTrue("cut=$cut", cut >= 800 && cut <= 3200)
    }

    @Test
    fun `返回值恒为 0 或落在合法区间`() {
        listOf(0, 100, 1599, 1600, 3199, 3200, 9000).forEach { n ->
            val buf = join(loud(n / 2), quiet(n - n / 2))
            val cut = SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600)
            assertTrue("n=$n cut=$cut", cut == 0 || (cut in 1..minOf(buf.size, 3200)))
        }
    }

    @Test
    fun `流式排空后样本不丢不重`() {
        val original = join(
            loud(1600), quiet(640), loud(2400), quiet(700), loud(1600), quiet(640), loud(500)
        )
        val buf = original.copyOf()
        var len = buf.size
        val blocks = ArrayList<ShortArray>()
        var guard = 0
        while (guard++ < 100) {
            val cut = SpeechCut.pickCut(buf, len, target = 1600, max = 3200, minSilence = 320, thresh = 600)
            if (cut <= 0) break
            blocks.add(buf.copyOfRange(0, cut))
            System.arraycopy(buf, cut, buf, 0, len - cut)
            len -= cut
        }
        val rebuilt = join(*blocks.toTypedArray(), buf.copyOfRange(0, len))
        assertTrue("至少切出 3 块，实为 ${blocks.size}", blocks.size >= 3)
        assertTrue("每块不超硬上限", blocks.all { it.size <= 3200 })
        assertTrue("每块不碎于 800", blocks.all { it.size >= 800 })
        assertTrue("剩下的不足一块", len < 3200)
        assertEquals(original.size, blocks.sumOf { it.size } + len)
        for (i in original.indices) assertEquals("第 $i 个样本不等", original[i], rebuilt[i])
    }

    @Test
    fun `阈值生效 高于门限的能量不算静音`() {
        val buf = join(loud(1000), level(1400, 5000), loud(400))
        assertEquals(
            0, SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 600)
        )
        assertTrue(
            "放宽门限后同一段可切",
            SpeechCut.pickCut(buf, buf.size, target = 1600, max = 3200, minSilence = 320, thresh = 6000) > 0,
        )
    }

    @Test
    fun `空缓冲区与零上限都安全返回 0`() {
        assertEquals(0, SpeechCut.pickCut(ShortArray(0), 0, 1600, 3200, 320, 600))
        assertEquals(0, SpeechCut.pickCut(loud(2000), 2000, 1600, 0, 320, 600))
    }
}
