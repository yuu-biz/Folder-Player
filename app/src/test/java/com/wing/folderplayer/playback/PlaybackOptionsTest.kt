package com.wing.folderplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackOptionsTest {
    @Test fun previousFollowsTheOption() {
        // Default: always the previous song, never a restart; nothing at the first song.
        assertEquals(PreviousAction.PREVIOUS_TRACK, PreviousAction.of(false, 60_000, hasPrevious = true))
        assertEquals(PreviousAction.NONE, PreviousAction.of(false, 60_000, hasPrevious = false))
        // Restart option: the song starts again after 3 s, also at the first song.
        assertEquals(PreviousAction.PREVIOUS_TRACK, PreviousAction.of(true, 3_000, hasPrevious = true))
        assertEquals(PreviousAction.RESTART, PreviousAction.of(true, 3_001, hasPrevious = true))
        assertEquals(PreviousAction.RESTART, PreviousAction.of(true, 1_000, hasPrevious = false))
    }

    private val minutes = 60_000L
    private var saved: String? = null
    private fun store() = ResumePositions({ saved }, { saved = it })

    @Test fun aLongFileResumesWhereItWasLeftAfterARestart() {
        store().leave("a", 25 * minutes, 60 * minutes)
        assertEquals(25 * minutes, store().positionOf("a"))
    }

    @Test fun onlyLongFilesLeftMidwayAreKept() {
        val s = store()
        s.leave("short", 5 * minutes, 9 * minutes)
        assertNull(s.positionOf("short"))
        s.leave("a", 30 * minutes, 60 * minutes)
        s.leave("a", 60 * minutes - 5_000, 60 * minutes) // played to its last seconds
        assertNull(s.positionOf("a"))
        s.leave("b", 30 * minutes, 60 * minutes)
        s.leave("b", 3_000, 60 * minutes) // started again and left at once
        assertNull(s.positionOf("b"))
        assertNull(store().positionOf("a"))
    }

    @Test fun theFilesUsedLastAreKept() {
        val s = store()
        for (i in 0 until ResumePositions.MAX_ENTRIES) s.leave("f$i", 20 * minutes, 60 * minutes)
        s.positionOf("f0") // played again: now the most recent
        s.leave("new", 20 * minutes, 60 * minutes)
        val reloaded = store()
        assertEquals(20 * minutes, reloaded.positionOf("f0"))
        assertNull(reloaded.positionOf("f1"))
        assertEquals(20 * minutes, reloaded.positionOf("new"))
    }
}
