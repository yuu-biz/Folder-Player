package com.wing.folderplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A song counts as played after 10 s of playing in all, or when it plays to its end first; a skip after 3 s does not. */
class PlayCounterTest {
    private val c = PlayCounter()

    @Test fun tenSecondsOfPlayingAcrossAPauseCount() {
        c.begin(now = 0, playing = false, previousEnded = false)
        assertNull("paused: nothing to wait for", c.dueInMs(0))
        assertFalse(c.setPlaying(true, 1_000))
        assertEquals(10_000L, c.dueInMs(1_000))
        assertFalse(c.setPlaying(false, 7_000))
        assertFalse("the pause is not playing time", c.tick(30_000))
        assertFalse(c.setPlaying(true, 30_000))
        assertEquals(4_000L, c.dueInMs(30_000))
        assertFalse(c.tick(33_999))
        assertTrue(c.tick(34_000))
        assertFalse("counted once", c.tick(60_000))
        assertFalse(c.setPlaying(false, 61_000))
        assertNull(c.dueInMs(61_000))
    }

    @Test fun aShortSongCountsAtItsEnd() {
        c.begin(now = 0, playing = true, previousEnded = false)
        assertTrue("a 4 s song moved on to the next by itself", c.begin(now = 4_000, playing = true, previousEnded = true))
        assertTrue("the last song of the queue ended", c.end(8_000))
        assertFalse(c.end(8_000))
    }

    @Test fun aSongSkippedAfterThreeSecondsDoesNotCount() {
        c.begin(now = 0, playing = true, previousEnded = false)
        assertFalse(c.begin(now = 3_000, playing = true, previousEnded = false))
        assertTrue("the next one counts on its own", c.tick(13_000))
    }

    @Test fun aSongPlayedLongEnoughCountsWhenLeftBeforeTheTimerRan() {
        c.begin(now = 0, playing = true, previousEnded = false)
        assertTrue(c.begin(now = 10_500, playing = true, previousEnded = false))
    }

    @Test fun eachRepeatOfTheSameSongCountsAgain() {
        c.begin(now = 0, playing = true, previousEnded = false)
        assertTrue(c.tick(10_000))
        assertFalse("already counted in this round", c.begin(now = 200_000, playing = true, previousEnded = true))
        assertTrue(c.tick(210_000))
    }
}
