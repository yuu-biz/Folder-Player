package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.ui.player.PlayerTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Next / previous without a track to move to (single-track queue, last track, first track) must change nothing:
 * no "Loading…" title, no waiting for a track change that never comes.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSkipTest : ServiceTestBase() {
    private fun file(i: Int) = MusicFile("track %03d.flac".format(i), "${Fx.FX}/Many/Folder %03d/track %03d.flac".format(i, i), false, 0, 0, local)

    private val placeholders = setOf(PlayerTitles.LOADING, PlayerTitles.SWITCHING, "Loading...")

    /** Plays [files] from [start] and waits until the real title of that track is shown. */
    private fun play(files: List<MusicFile>, start: Int) {
        main { vm.playCustomList(files, start) }
        val name = files[start].name.substringBeforeLast('.')
        waitFor(20_000, "playing ${files[start].name}") {
            state.isPlaying && state.currentMediaId?.contains(files[start].name.replace(" ", "%20")) == true && state.currentTitle == name
        }
        Thread.sleep(1_200) // the delayed metadata refresh after start
        // Paused: the fixture tracks last a few seconds and must not end (and advance) during the checks.
        main { vm.playPause() }
        waitFor(5_000, "paused") { !state.isPlaying }
    }

    private fun assertNothingChanged(what: String, title: String, id: String?) {
        Thread.sleep(1_500)
        val s = state
        assertFalse("$what: no placeholder title (${s.currentTitle})", s.currentTitle in placeholders)
        assertEquals("$what: title", title, s.currentTitle)
        assertEquals("$what: track", id, s.currentMediaId)
    }

    @Test fun singleTrackQueueIgnoresNextAndPrevious() {
        play(listOf(file(100)), 0)
        val title = state.currentTitle; val id = state.currentMediaId
        assertFalse("nothing to skip to", state.canSkipNext)
        assertFalse(state.canSkipPrevious)
        main { vm.next() }
        assertNothingChanged("next", title, id)
        main { vm.previous() }
        assertNothingChanged("previous", title, id)
        // The display still follows the player (it is not waiting for a track change): a new track is shown.
        main { vm.playCustomList(listOf(file(101)), 0) }
        waitFor(20_000, "next request shown") { state.currentTitle == "track 101" }
    }

    @Test fun nextAtTheEndAndPreviousAtTheStartChangeNothing() {
        val files = listOf(file(110), file(111), file(112))
        play(files, 2)
        assertFalse("last track: no next", state.canSkipNext)
        assertTrue(state.canSkipPrevious)
        main { vm.next() }
        assertNothingChanged("next on the last track", "track 112", state.currentMediaId)

        play(files, 0)
        assertFalse("first track: no previous", state.canSkipPrevious)
        assertTrue(state.canSkipNext)
        main { vm.previous() }
        assertNothingChanged("previous on the first track", "track 110", state.currentMediaId)

        // Where there is a next track, next still works.
        main { vm.next() }
        waitFor(20_000, "moved to the second track") { state.currentTitle == "track 111" && state.currentMediaId?.contains("track%20111") == true }
    }
}
