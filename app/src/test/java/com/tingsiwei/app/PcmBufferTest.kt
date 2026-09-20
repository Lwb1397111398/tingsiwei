package com.tingsiwei.app

import com.tingsiwei.app.transcribe.PcmBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/** T4 PCM 缓冲：重采样比例、下混、出块后前移、内存有界。 */
class PcmBufferTest {

    private fun shortBufOf(values: ShortArray): ShortBuffer =
        ByteBuffer.allocate(values.size * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().apply { put(values); flip() }

    @Test
    fun `源采样率就是 16k 时等长直通且数值归一`() {
        val buffer = PcmBuffer(16000, 8192)
        buffer.append(shortBufOf(ShortArray(1000) { (it % 200 * 100).toShort() }), 1000, 1)
        val out = buffer.takeAllResampled()
        assertEquals(1000, out.size)
        assertEquals(0f, out[0], 0.0001f)
        assertEquals(19900 / 32768f, out[199], 0.0001f)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `44100 下采样到 16k 的比例正确`() {
        val buffer = PcmBuffer(44100, 8192)
        buffer.append(shortBufOf(ShortArray(44100) { 100 }), 44100, 1)
        assertEquals(16000, buffer.takeAllResampled().size)
    }

    @Test
    fun `多声道按均值下混为单声道`() {
        val buffer = PcmBuffer(16000, 8192)
        // 双声道：左 1000 右 3000 → 均值 2000
        val interleaved = ShortArray(200) { if (it % 2 == 0) 1000 else 3000 }
        buffer.append(shortBufOf(interleaved), 100, 2)
        assertEquals(100, buffer.size)
        assertEquals(2000 / 32768f, buffer.takeAllResampled()[0], 0.0001f)
    }

    @Test
    fun `取走一段后剩余样本前移 连续取完等于原始流`() {
        val values = ShortArray(5000) { (it % 700).toShort() }
        val buffer = PcmBuffer(16000, 8192)
        buffer.append(shortBufOf(values), values.size, 1)

        val first = buffer.takeResampled(2000)
        assertEquals(2000, first.size)
        assertEquals(3000, buffer.size)
        val rest = buffer.takeResampled(1500)
        assertEquals(1500, rest.size)
        assertEquals(1500, buffer.size)
        val tail = buffer.takeAllResampled()
        assertEquals(1500, tail.size)
        assertEquals(0, buffer.size)

        val stitched = first + rest + tail
        assertEquals(values.size, stitched.size)
        for (i in values.indices) {
            assertEquals("第 $i 个样本错位", values[i].toInt(), (stitched[i] * 32768).toInt())
        }
    }

    @Test
    fun `反复出块时峰值占用有界 与音频总时长无关`() {
        val buffer = PcmBuffer(16000, 16000)
        var fed = 0
        while (fed < 60 * 16000) { // 灌 60 秒
            val chunk = ShortArray(1600) { 500 }
            buffer.append(shortBufOf(chunk), chunk.size, 1)
            fed += chunk.size
            if (buffer.size >= 16000) assertEquals(16000, buffer.takeResampled(16000).size)
        }
        assertEquals(60 * 16000, fed)
        assertTrue(
            "峰值 ${(buffer.highWater / 16000.0)} 秒，必须远小于 60 秒",
            buffer.highWater <= 2 * 16000,
        )
    }

    @Test
    fun `容量不足时自动扩容不丢数据`() {
        val buffer = PcmBuffer(16000, 8192)
        repeat(10) {
            val chunk = ShortArray(4000) { (it % 200).toShort() }
            buffer.append(shortBufOf(chunk), chunk.size, 1)
        }
        assertEquals(40000, buffer.size)
        val out = buffer.takeAllResampled()
        assertEquals(40000, out.size)
        assertEquals(199 / 32768f, out[39999], 0.0001f)
    }

    @Test
    fun `8k 源上采样到 16k 长度翻倍`() {
        val buffer = PcmBuffer(8000, 8192)
        buffer.append(shortBufOf(ShortArray(8000) { 100 }), 8000, 1)
        assertEquals(16000, buffer.takeAllResampled().size)
    }

    @Test
    fun `44100 分多次取用后长度与数值仍正确`() {
        val buffer = PcmBuffer(44100, 8192)
        var at = 0
        while (at < 22050) { // 0.5 秒，分小批喂
            val n = minOf(1500, 22050 - at)
            buffer.append(shortBufOf(ShortArray(n) { 5000 }), n, 1)
            at += n
        }
        assertEquals(22050, buffer.size)
        val first = buffer.takeResampled(4410)
        assertEquals(1600, first.size) // 4410 / 44100 * 16000
        assertTrue("常量信号重采样后应保持幅值", first.all { kotlin.math.abs(it - 5000 / 32768f) < 0.0001f })
        val rest = buffer.takeAllResampled()
        assertEquals(8000, first.size + rest.size) // 0.5 秒音频 = 8000 个 16k 样本
        assertEquals(6400, rest.size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `零长度与越界取用都安全`() {
        val buffer = PcmBuffer(16000, 8192)
        assertEquals(0, buffer.takeResampled(1000).size)
        buffer.append(shortBufOf(ShortArray(10) { 100 }), 10, 1)
        buffer.append(shortBufOf(ShortArray(0)), 0, 1)
        assertEquals(10, buffer.size)
        assertEquals(10, buffer.takeResampled(9999).size)
        assertEquals(0, buffer.size)
    }
}
