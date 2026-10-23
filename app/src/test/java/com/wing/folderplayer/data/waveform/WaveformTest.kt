package com.wing.folderplayer.data.waveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveformTest {

    /** Square wave of amplitude [amp]: its RMS is exactly [amp]. */
    private fun LevelAccumulator.square(amp: Float, frames: Int, channels: Int = 1) {
        repeat(frames) { i -> val v = if (i % 2 == 0) amp else -amp; repeat(channels) { add(v) } }
    }

    @Test fun `quiet then loud shows as a step in the bins`() {
        val acc = LevelAccumulator(sampleRate = 1000, channels = 1, binMs = 100)
        acc.square(0.05f, 1000)
        acc.square(0.8f, 1000)
        val levels = acc.levels(final = true)
        assertEquals(20, levels.size)
        for (i in 0 until 10) assertEquals(0.05f, levels[i], 1e-4f)
        for (i in 10 until 20) assertEquals(0.8f, levels[i], 1e-4f)
    }

    @Test fun `out of phase stereo does not cancel out`() {
        val acc = LevelAccumulator(sampleRate = 1000, channels = 2, binMs = 100)
        repeat(100) { acc.add(0.5f); acc.add(-0.5f) }
        assertEquals(0.5f, acc.levels(final = true).single(), 1e-4f)
    }

    @Test fun `16-bit little-endian PCM is read as signed samples`() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putShort((-16384).toShort()).putShort(16384.toShort()).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE)
        val out = FloatArray(4)
        assertEquals(4, pcm16ToFloats(pcm.array(), 8, out))
        assertArrayEquals(floatArrayOf(-0.5f, 0.5f, -1f, 32767f / 32768f), out, 1e-6f)
        // An odd trailing byte and a short output buffer are not overrun.
        assertEquals(2, pcm16ToFloats(pcm.array(), 5, FloatArray(4)))
        assertEquals(1, pcm16ToFloats(pcm.array(), 8, FloatArray(1)))
    }

    @Test fun `a short last bin is kept only at the end and only when it is a quarter full`() {
        val acc = LevelAccumulator(sampleRate = 1000, channels = 1, binMs = 100)
        acc.square(0.3f, 130)
        assertEquals(1, acc.levels().size)
        assertEquals(2, acc.levels(final = true).size)
        val tiny = LevelAccumulator(sampleRate = 1000, channels = 1, binMs = 100)
        tiny.square(0.3f, 110)
        assertEquals(1, tiny.levels(final = true).size)
    }

    private fun stepWave(complete: Boolean = true) =
        Waveform(binMs = 100, levels = FloatArray(20) { if (it < 10) 0.05f else 0.8f }, durationMs = 2000, complete = complete)

    @Test fun `bars are relative to the loudest one and quiet parts stay lower`() {
        val bars = stepWave().bars(startMs = 0, lengthMs = 2000, count = 4)
        assertEquals(1f, bars[2], 1e-4f)
        assertEquals(1f, bars[3], 1e-4f)
        assertTrue("quiet bar ${bars[0]}", bars[0] > 0f && bars[0] < 0.25f)
        assertEquals(bars[0], bars[1], 1e-6f)
    }

    @Test fun `a CUE track shows only its slice of the file`() {
        val bars = stepWave().bars(startMs = 1000, lengthMs = 1000, count = 5)
        bars.forEach { assertEquals(1f, it, 1e-4f) }
    }

    @Test fun `the part not measured yet is NaN, past the end of a measured file is silent`() {
        val partial = Waveform(100, FloatArray(10) { 0.5f }, 2000, complete = false).bars(0, 2000, 4)
        assertEquals(1f, partial[0], 1e-4f)
        assertTrue(partial[2].isNaN() && partial[3].isNaN())
        // The player may report a longer duration than the decoder found (VBR estimate): the rest is drawn flat.
        val done = Waveform(100, FloatArray(10) { 0.5f }, 1000, complete = true).bars(0, 2000, 4)
        assertEquals(0f, done[3], 0f)
    }

    @Test fun `sampled bins not measured yet are skipped, a bar with none is NaN until complete`() {
        val levels = FloatArray(8) { Float.NaN }
        levels[0] = 0.8f; levels[5] = 0.2f
        val partial = Waveform(100, levels, 800, complete = false).bars(0, 800, 4)
        assertEquals(1f, partial[0], 1e-4f)
        assertTrue(partial[1].isNaN())
        assertTrue(partial[2] > 0f && partial[2] < 1f)
        assertTrue(partial[3].isNaN())
        val done = Waveform(100, levels, 800, complete = true).bars(0, 800, 4)
        assertEquals(0f, done[1], 0f)
    }

    @Test fun `sampling order spreads early and visits every bin once`() {
        val order = Waveform.sampleOrder(600)
        assertEquals((0 until 600).toList(), order.sorted())
        assertEquals(listOf(0, 512, 256, 128), order.take(4).toList()) // 768 is past the end and skipped
        // The first eighth already has a bin in every eighth of the file.
        val firstEighth = order.take(75).map { it * 8 / 600 }.toSet()
        assertEquals((0 until 8).toSet(), firstEighth)
        assertEquals(listOf(0), Waveform.sampleOrder(1).toList())
        assertEquals(emptyList<Int>(), Waveform.sampleOrder(0).toList())
    }

    @Test fun `windows of a sampled file`() {
        assertEquals(100, Waveform.windowsFor(30_000))
        assertEquals(200, Waveform.windowsFor(200_000))
        assertEquals(300, Waveform.windowsFor(3_600_000))
    }

    @Test fun `more bars than bins repeat the bins`() {
        val bars = Waveform(1000, floatArrayOf(0.2f, 0.4f), 2000, true).bars(0, 2000, 8)
        assertEquals(8, bars.size)
        assertTrue(bars.none { it.isNaN() })
        assertEquals(1f, bars[7], 1e-4f)
    }

    @Test fun `silence gives flat bars, not NaN`() {
        val bars = Waveform(100, FloatArray(10), 1000, true).bars(0, 1000, 5)
        bars.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun `kept file round trip`() {
        val w = stepWave()
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> w.write(out) } }.toByteArray()
        val back = Waveform.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(w.binMs, back.binMs)
        assertEquals(w.durationMs, back.durationMs)
        assertArrayEquals(w.levels, back.levels, 0f)
        assertTrue(back.complete)
    }

    @Test(expected = java.io.IOException::class)
    fun `a damaged kept file is rejected`() {
        Waveform.read(DataInputStream(ByteArrayInputStream(ByteArray(12) { 7 })))
    }

    @Test fun `bin length follows the duration`() {
        assertEquals(50, Waveform.binMsFor(3 * 60_000L))
        assertEquals(900, Waveform.binMsFor(3_600_000L))
        assertEquals(250, Waveform.binMsFor(-1))
        assertTrue(Waveform.binMsFor(10 * 3_600_000L) * Waveform.MAX_BINS >= 10 * 3_600_000L)
    }
}
