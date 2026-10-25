package com.wing.folderplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerColorsTest {
    private val hsv = FloatArray(3)
    private fun hue(argb: Int): Float { VisualizerColors.hsv(argb, hsv); return hsv[0] }
    private fun value(argb: Int): Float { VisualizerColors.hsv(argb, hsv); return hsv[2] }
    private fun sat(argb: Int): Float { VisualizerColors.hsv(argb, hsv); return hsv[1] }

    private fun cover(vararg parts: Pair<Int, Int>): IntArray = parts.flatMap { (color, n) -> List(n) { color } }.toIntArray()

    @Test fun mostOfTheCoverAndASecondHue() {
        val red = 0xFFC0201A.toInt()
        val blue = 0xFF1A40C0.toInt()
        val (c1, c2) = VisualizerColors.pick(cover(red to 700, blue to 300))!!
        assertTrue("first is red, hue ${hue(c1)}", VisualizerColors.hueDistance(hue(c1), hue(red)) < 10)
        assertTrue("second is blue, hue ${hue(c2)}", VisualizerColors.hueDistance(hue(c2), hue(blue)) < 10)
        assertTrue("bright on a dark background", value(c1) > 0.95f && value(c2) > 0.85f)
    }

    @Test fun oneHueOnlyGivesAShiftedSecond() {
        val (c1, c2) = VisualizerColors.pick(cover(0xFF20A040.toInt() to 1000))!!
        assertEquals(40f, VisualizerColors.hueDistance(hue(c1), hue(c2)), 3f)
    }

    @Test fun greyDarkAndTransparentCoversGiveNone() {
        assertNull(VisualizerColors.pick(cover(0xFF808080.toInt() to 900, 0xFFFFFFFF.toInt() to 100)))
        assertNull(VisualizerColors.pick(cover(0xFF100804.toInt() to 1000)))
        assertNull(VisualizerColors.pick(cover(0x00FF0000 to 1000)))
        assertNull(VisualizerColors.pick(IntArray(0)))
    }

    @Test fun aDullCoverIsMadeVivid() {
        val (c1, _) = VisualizerColors.pick(cover(0xFF7A6A5A.toInt() to 900, 0xFF000000.toInt() to 100))!!
        assertTrue("saturation ${sat(c1)}", sat(c1) >= 0.49f) // 0.5, give or take the rounding to 8-bit channels
    }

    @Test fun hsvRoundTrip() {
        for (c in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF336699.toInt(), 0xFFFFCC00.toInt())) {
            VisualizerColors.hsv(c, hsv)
            assertEquals(Integer.toHexString(c), c, VisualizerColors.argb(hsv[0], hsv[1], hsv[2]))
        }
    }
}
