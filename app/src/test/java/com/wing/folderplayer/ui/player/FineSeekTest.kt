package com.wing.folderplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FineSeekTest {
    private fun ms(f: Float, durationMs: Long) = (f * durationMs).toLong()

    @Test fun oneBarWidthIsHalfAMinuteOfALongTrack() {
        val d = 600_000L
        assertEquals(330_000.0, ms(FineSeek.position(0.5f, 1f, d), d).toDouble(), 50.0)
        assertEquals(285_000.0, ms(FineSeek.position(0.5f, -0.5f, d), d).toDouble(), 50.0)
    }

    @Test fun aShortTrackMovesAtMostAQuarterPerBarWidth() {
        val d = 40_000L
        assertEquals(30_000.0, ms(FineSeek.position(0.5f, 1f, d), d).toDouble(), 10.0)
    }

    @Test fun staysOnTheTrack() {
        assertEquals(0f, FineSeek.position(0.01f, -1f, 600_000L))
        assertEquals(1f, FineSeek.position(0.99f, 1f, 600_000L))
        assertEquals(0.75f, FineSeek.position(0.5f, 1f, 0L))
    }
}
