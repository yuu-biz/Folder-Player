package com.wing.folderplayer.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioTapTest {
    @Before fun setUp() { AudioTap.attach() }
    @After fun tearDown() { AudioTap.detach() }

    /** One second at 48 kHz, sample i = i / 48000, written in 100 ms buffers with their times. */
    private fun writeRamp(startUs: Long = 0) {
        val chunk = FloatArray(4_800)
        for (c in 0 until 10) {
            for (i in chunk.indices) chunk[i] = (c * 4_800 + i) / 48_000f
            AudioTap.write(chunk, chunk.size, startUs + c * 100_000L, 48_000)
        }
    }

    @Test fun readsTheSamplesBeingHeardNotTheNewest() {
        AudioTap.configure(48_000)
        writeRamp()
        AudioTap.onPlaying(false)
        AudioTap.onPosition(500_000) // half a second heard, a whole second taken by the sink
        val out = FloatArray(4_800)
        assertTrue(AudioTap.read(out))
        assertEquals(23_999 / 48_000f, out.last(), 1e-4f)
        assertEquals(19_200 / 48_000f, out.first(), 1e-4f)
    }

    @Test fun aJumpInTimeMovesTheClockOfWhatIsKept() {
        AudioTap.configure(48_000)
        writeRamp()
        AudioTap.write(FloatArray(4_800) { 0.5f }, 4_800, 30_000_000L, 48_000) // seeked without a flush
        AudioTap.onPlaying(false)
        AudioTap.onPosition(500_000)
        assertFalse("the old samples moved with the clock: nothing at the old place", AudioTap.read(FloatArray(1_024)))
        AudioTap.onPosition(30_100_000)
        val out = FloatArray(1_024)
        assertTrue(AudioTap.read(out))
        assertEquals(0.5f, out.last(), 1e-6f)
    }

    @Test fun aHighRateIsKeptAtMostAt48kHz() {
        AudioTap.configure(192_000)
        assertEquals(48_000, AudioTap.rate)
        val frames = FloatArray(19_200) { (it / 4) / 48_000f } // 100 ms at 192 kHz, each value four times
        AudioTap.write(frames, frames.size, 0, 192_000)
        AudioTap.onPlaying(false)
        AudioTap.onPosition(100_000)
        val out = FloatArray(4_800)
        assertTrue(AudioTap.read(out))
        assertEquals(4_799 / 48_000f, out.last(), 1e-4f)
    }

    @Test fun nothingIsKeptWithoutAViewerOrAfterClear() {
        AudioTap.configure(48_000)
        writeRamp()
        AudioTap.clear()
        AudioTap.onPosition(500_000)
        assertFalse(AudioTap.read(FloatArray(256)))
    }

    @Test fun spectrumFindsASine() {
        val rate = 48_000
        val n = 2_048
        val s = FloatArray(n) { (0.8 * sin(2 * PI * 1_000.0 * it / rate)).toFloat() }
        val spectrum = Spectrum(n, 32)
        spectrum.analyse(s, rate)
        val peak = spectrum.levels.indices.maxByOrNull { spectrum.levels[it] }!!
        // The band that holds 1 kHz on the 40 Hz .. 16 kHz log scale.
        val expected = (32 * kotlin.math.ln(1_000.0 / 40) / kotlin.math.ln(16_000.0 / 40)).toInt()
        assertTrue("peak band $peak, expected about $expected", kotlin.math.abs(peak - expected) <= 1)
        assertTrue("loud", spectrum.levels[peak] > 0.8f)
        assertTrue("quiet far away", spectrum.levels[31] < 0.2f)
        Spectrum(n, 32).apply { analyse(FloatArray(n), rate); assertTrue(levels.all { it == 0f }) }
    }
}
