package com.tingsiwei.app

import com.tingsiwei.app.transcribe.SpeechScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T4 出块点选择：静音中点、硬上限、碎块下限、增量扫描、流式覆盖不丢样。 */
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

    private fun scanner(target: Int = 1600, max: Int = 3200, minSilence: Int = 320, thresh: Int = 600, min: Int = target / 2) =
        SpeechScanner(target, max, minSilence, thresh, min)

    @Test
    fun `缓冲区不足目标长度时不出块`() {
        val buf = join(loud(800), quiet(640))
        assertEquals(0, scanner().pickCut(buf, buf.size))
    }

    @Test
    fun `攒够后优先切在静音中点`() {
        val buf = join(loud(1600), quiet(640), loud(1600))
        assertEquals("静音区 1600..2240 的中点", 1760, scanner().pickCut(buf, buf.size))
    }

    @Test
    fun `静音必须足够长才算边界`() {
        val buf = join(loud(1600), quiet(160), loud(1000))
        assertEquals("只有一帧静音不该切", 0, scanner().pickCut(buf, buf.size))
    }

    @Test
    fun `全程有声时攒到硬上限才切`() {
        assertEquals(0, scanner().pickCut(loud(3000), 3000))
        assertEquals(3200, scanner().pickCut(loud(3600), 3600))
    }

    @Test
    fun `达到硬上限时宁可在更早的静音处切`() {
        val buf = join(loud(1000), quiet(640), loud(3000))
        assertEquals("静音区内的中点，早于 target 但优于硬切句中", 1280, scanner().pickCut(buf, buf.size))
    }

    @Test
    fun `碎块下限 不会产出比 min 更短的块`() {
        val buf = join(quiet(400), loud(4000))
        val cut = scanner(min = 800).pickCut(buf, buf.size)
        assertTrue("cut=$cut 必须不早于 min", cut >= 800)
    }

    @Test
    fun `全静音也要能出块且不越界`() {
        val cut = scanner().pickCut(quiet(4000), 4000)
        assertTrue("cut=$cut", cut in 800..3200)
    }

    @Test
    fun `返回值恒为 0 或落在合法区间`() {
        listOf(0, 100, 1599, 1600, 3199, 3200, 9000).forEach { n ->
            val buf = join(loud(n / 2), quiet(n - n / 2))
            val cut = scanner().pickCut(buf, buf.size)
            assertTrue("n=$n cut=$cut", cut == 0 || (cut in 1..minOf(buf.size, 3200)))
        }
    }

    @Test
    fun `增量扫描给出的切点必定落在静音区内`() {
        val original = join(loud(900), quiet(700), loud(2000), quiet(640), loud(4000))
        fun assertQuiet(cut: Int, from: Int) {
            var sum = 0L
            val lo = maxOf(0, cut - 160)
            val hi = minOf(from, cut + 160)
            for (i in lo until hi) sum += abs(original[i].toInt())
            assertTrue("切点 $cut 处平均能量 ${sum / (hi - lo)} 不静音", sum / (hi - lo) < 600)
        }
        // 一次扫完整缓冲区
        val oneShot = scanner().pickCut(original, original.size)
        assertTrue(oneShot > 0)
        assertQuiet(oneShot, original.size)
        // 按解码器节奏分批喂进来，结论同样落在静音区
        val inc = scanner()
        var fed = 0
        var cut = 0
        while (fed < original.size && cut == 0) {
            fed = minOf(fed + 137, original.size)
            cut = inc.pickCut(original, fed)
        }
        assertTrue("增量扫描也要能出块", cut > 0)
        assertQuiet(cut, fed)
    }

    @Test
    fun `出块后自动复位可继续扫描`() {
        val buf = join(loud(1600), quiet(640), loud(1600), quiet(640), loud(1600))
        val s = scanner()
        val first = s.pickCut(buf, buf.size)
        assertTrue(first > 0)
        // 复位后同一缓冲区仍能再次找到切点（调用方前移样本后会重新攒）
        val second = s.pickCut(buf, buf.size)
        assertTrue("复位后应能继续出块", second > 0)
    }

    @Test
    fun `流式排空后样本不丢不重`() {
        val original = join(
            loud(1600), quiet(640), loud(2400), quiet(700), loud(1600), quiet(640), loud(500)
        )
        val buf = original.copyOf()
        var len = buf.size
        val s = scanner()
        val blocks = ArrayList<ShortArray>()
        var guard = 0
        while (guard++ < 100) {
            val cut = s.pickCut(buf, len)
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
        assertEquals(0, scanner(thresh = 600).pickCut(buf, buf.size))
        assertTrue("放宽门限后同一段可切", scanner(thresh = 6000).pickCut(buf, buf.size) > 0)
    }

    @Test
    fun `空缓冲区与零上限都安全返回 0`() {
        assertEquals(0, scanner().pickCut(ShortArray(0), 0))
        assertEquals(0, scanner(max = 0).pickCut(loud(2000), 2000))
    }
}
