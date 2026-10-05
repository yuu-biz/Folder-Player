package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerSheetKey
import com.wing.folderplayer.ui.player.PlayerSheetState
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Mini ⇄ full player as one panel (docs/fork/UI_REDESIGN.md, stage 2): tap, drag and fling in both directions,
 * settling at an end, Back, track changes while moving, the gestures that must not move the player (seek bar, lyrics,
 * playlist), rotation / recreation in the middle of a move, repeated and reversed input.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSheetUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val ps get() = player.uiState.value
    private val bs get() = browser.uiState.value

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    /** Fetched off the main thread; its fields are then read on it (see [onUi]). */
    private val sheet: PlayerSheetState
        get() = compose.onNodeWithTag("player_host", useUnmergedTree = true).fetchSemanticsNode().config[PlayerSheetKey]
    private fun <T> onSheet(block: PlayerSheetState.() -> T): T { val s = sheet; return onUi { s.block() } }
    private val fraction get() = onSheet { fraction }

    @Before fun reset() {
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
    }

    @After fun pause() {
        compose.mainClock.autoAdvance = true
        runCatching { Fx.log("after: fraction $fraction, ${onSheet { "expanded=$expanded dragging=$isDragging animating=$isAnimating" }}") }
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
            }
        }
        runCatching { device.setOrientationNatural(); device.unfreezeRotation() }
    }

    private fun playing(fragment: String) = ps.isPlaying && ps.currentMediaId?.contains(fragment) == true

    private fun playLong() {
        toBrowser()
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        until(5_000, "mini player") { exists("mini_player") }
    }

    /** At an end, at rest, and everything agrees with it (player layer, mini player, Back). */
    private fun assertSettled(open: Boolean, what: String) {
        Fx.log("check settled ${if (open) "open" else "closed"}: $what (fraction $fraction)")
        until(5_000, "$what: settled ${if (open) "open" else "closed"} (fraction ${fraction}, expanded ${onSheet { expanded }})") {
            onSheet { expanded == open && !isDragging && !isAnimating && fraction == (if (open) 1f else 0f) }
        }
        compose.waitForIdle()
        if (open) {
            assertTrue("$what: full player shown", playerOpen())
            assertFalse("$what: no mini player under it", exists("mini_player"))
        } else {
            assertFalse("$what: no full player layer left (it would take touches)", playerOpen())
            assertTrue("$what: mini player back", exists("mini_player"))
        }
    }

    /** Distance the finger travels from mini to full (top of the mini player to the top of the screen). */
    private fun range(): Float = node("mini_player").fetchSemanticsNode().boundsInRoot.top

    /** Vertical drag on [tag]: from [startY] (fraction of the node's height) by [dy] px over [ms]; [release] lifts the finger. */
    private fun drag(tag: String, startY: Float, dy: Float, ms: Long, release: Boolean = true, x: Float = 0.5f, steps: Int = 30) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
            down(Offset(width * x, height * startY))
            moveInSteps(Offset(0f, dy), ms, steps)
            if (release) up()
        }
        compose.waitForIdle()
    }

    private fun TouchInjectionScope.moveInSteps(by: Offset, ms: Long, steps: Int) {
        repeat(steps) {
            advanceEventTime(ms / steps)
            moveBy(Offset(by.x / steps, by.y / steps))
        }
    }

    /** The full player's cover, as drawn on screen (moved / scaled), shows the red fixture picture. */
    private fun coverIsRed(): Boolean {
        val bmp = node("player_cover").captureToImage().asAndroidBitmap()
        val c = bmp.getPixel(bmp.width / 2, bmp.height * 2 / 3)
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        Fx.log("cover pixel $r,$g,$b (${bmp.width}x${bmp.height})")
        return r > 180 && g < 70 && b < 70
    }

    private fun lift(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun s01_tapOpensBackAndButtonFold() {
        playLong()
        assertSettled(false, "start")
        click("mini_player")
        assertSettled(true, "tap")
        pressBack()
        assertSettled(false, "Back")
        click("mini_player")
        assertSettled(true, "tap again")
        click("btn_collapse_player")
        assertSettled(false, "collapse button")
        assertTrue(playing("Long"))
    }

    @Test fun s02_slowDragFollowsTheFingerAndSettlesAtTheNearerEnd() {
        playLong()
        val r = range()
        // Held part of the way: the player follows the finger.
        drag("mini_player", 0.5f, -0.3f * r, 1_500, release = false)
        val held = fraction
        Fx.log("held at $held (range $r)")
        assertTrue("follows the finger: $held", held in 0.2f..0.4f && onSheet { isDragging })
        assertTrue("the full player is drawn while held", playerOpen())
        lift("mini_player")
        assertSettled(false, "slow release below half")

        drag("mini_player", 0.5f, -0.7f * r, 1_500)
        assertSettled(true, "slow release above half")

        // Full → down, slowly: from the cover area (not the seek bar, not the lyrics).
        drag("player_full", 0.2f, 0.3f * r, 1_500)
        assertSettled(true, "short way down")
        drag("player_full", 0.2f, 0.7f * r, 1_500)
        assertSettled(false, "most of the way down")

        // Reversed in the middle: up 70 %, back down to 20 %, released slowly → mini.
        compose.onNodeWithTag("mini_player", useUnmergedTree = true).performTouchInput {
            down(center)
            moveInSteps(Offset(0f, -0.7f * r), 1_000, 25)
            moveInSteps(Offset(0f, 0.5f * r), 1_000, 25)
            up()
        }
        compose.waitForIdle()
        assertSettled(false, "reversed")
        assertTrue("playback untouched by all this", playing("Long"))
    }

    @Test fun s03_flingDecidesByDirection() {
        playLong()
        val r = range()
        drag("mini_player", 0.5f, -0.12f * r, 60, steps = 6)
        assertSettled(true, "short fast flick up")
        drag("player_full", 0.2f, 0.12f * r, 60, steps = 6)
        assertSettled(false, "short fast flick down")
        // A flick against the position wins: dragged most of the way up, then flicked down.
        compose.onNodeWithTag("mini_player", useUnmergedTree = true).performTouchInput {
            down(center)
            moveInSteps(Offset(0f, -0.8f * r), 1_000, 25)
            moveInSteps(Offset(0f, 0.1f * r), 40, 4)
            up()
        }
        compose.waitForIdle()
        assertSettled(false, "flick down at 70 %")
    }

    @Test fun s04_backFoldsTheFullPlayerThenGoesToTheBrowser() {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, "$fx/Album-A")) }
        until(15_000, "folder") { bs.currentFolder?.path == "$fx/Album-A" && !bs.isLoading }
        if (bs.viewMode != "LIST") click("btn_view_mode")
        click("item_02 track.mp3")
        assertSettled(true, "song tap")
        pressBack()
        assertSettled(false, "Back from full")
        assertEquals("$fx/Album-A", bs.currentFolder?.path)

        // Back while a finger holds the player half-way: folds it; the finger's release changes nothing.
        drag("mini_player", 0.5f, -0.4f * range(), 800, release = false)
        assertTrue(onSheet { isDragging })
        pressBack()
        assertEquals("the browser did not move", "$fx/Album-A", bs.currentFolder?.path)
        lift("mini_player")
        assertSettled(false, "Back during a drag")

        // From the mini player Back belongs to the browser.
        pressBack()
        until(5_000, "browser went up") { bs.currentFolder?.path == fx }
        assertSettled(false, "after the browser's Back")
    }

    @Test fun s05_trackChangesWhileDraggingAndAnimating() {
        toBrowser()
        val files = (100..102).map { i -> MusicFile("track %03d.flac".format(i), "$fx/Many/Folder %03d/track %03d.flac".format(i, i), false, 0, 0, local) }
        onUi { player.playCustomList(files, 0) }
        until(15_000, "first track") { playing("track%20100") }
        until(5_000, "mini") { exists("mini_player") }
        val r = range()

        // Next while a finger holds the player.
        drag("mini_player", 0.5f, -0.4f * r, 800, release = false)
        onUi { player.next() }
        until(10_000, "second track") { playing("track%20101") }
        compose.onNodeWithTag("mini_player", useUnmergedTree = true).performTouchInput {
            moveInSteps(Offset(0f, -0.3f * r), 600, 15)
            up()
        }
        compose.waitForIdle()
        assertSettled(true, "released after the track change")
        until(5_000, "full player shows the new title") { textExists("track 101") }

        // Next while folding (clock held in the middle of the animation).
        compose.mainClock.autoAdvance = false
        click("btn_collapse_player")
        compose.mainClock.advanceTimeBy(80)
        val mid = fraction
        assertTrue("in the middle of folding: $mid", mid > 0f && mid < 1f)
        onUi { player.next() }
        compose.mainClock.autoAdvance = true
        until(10_000, "third track") { playing("track%20102") }
        assertSettled(false, "folded after the track change")
        until(5_000, "mini shows the new title") { text("mini_title").contains("track 102") }
    }

    @Test fun s06_seekBarLyricsAndPlaylistKeepTheirGestures() {
        toBrowser()
        onUi { player.playFolder(SourceRef(local, "$fx/Album-A"), "$fx/Album-A/01 曲 #1+%.flac") }
        until(15_000, "playing") { ps.isPlaying && ps.currentMediaId?.contains("Album-A") == true }
        until(10_000, "lyrics") { ps.lyrics.isNotEmpty() }
        // The moving cover is drawn (Album-A's cover.jpg is red): held part-way up, then fully open.
        toBrowser()
        until(5_000, "mini") { exists("mini_player") }
        drag("mini_player", 0.5f, -0.4f * range(), 800, release = false)
        until(5_000, "cover drawn while held part-way") { coverIsRed() }
        lift("mini_player")
        assertSettled(false, "released")
        toPlayer()
        assertSettled(true, "open")
        until(5_000, "cover drawn when open") { coverIsRed() }

        // Seek bar, sideways: seeks, the player stays.
        val before = ps.currentPosition
        compose.onNodeWithTag("seek_bar", useUnmergedTree = true).performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveInSteps(Offset(width * 0.7f, 0f), 600, 20)
            up()
        }
        compose.waitForIdle()
        until(5_000, "seeked forward (${ps.currentPosition} after $before)") { ps.currentPosition > 30_000 }
        assertSettled(true, "after seeking")

        // Downward flings that would fold the player at once if it took them (fast and long; released, so a held
        // overscroll of the short lyrics list does not keep the screen animating).
        val r = compose.onNodeWithTag("player_full").fetchSemanticsNode().boundsInRoot.height
        // Seek bar, downwards: neither a seek nor a fold.
        val atFling = ps.currentPosition
        compose.onNodeWithTag("seek_bar", useUnmergedTree = true).performTouchInput {
            down(Offset(width * 0.5f, centerY))
            moveInSteps(Offset(0f, 0.6f * r), 120, 8)
            up()
        }
        compose.waitForIdle()
        assertSettled(true, "fling down on the seek bar")
        assertTrue("no seek to the middle (${ps.currentPosition} after $atFling)", abs(ps.currentPosition - atFling) < 5_000)

        // Lyrics list: scrolling it does not fold the player.
        until(5_000, "lyrics list") { exists("lyrics_list") }
        compose.onNodeWithTag("lyrics_list", useUnmergedTree = true).performTouchInput {
            down(Offset(centerX, height * 0.2f))
            moveInSteps(Offset(0f, 0.6f * r), 120, 8)
            up()
        }
        compose.waitForIdle()
        assertSettled(true, "fling down on the lyrics")

        // Swipe up (outside seek bar and lyrics) still opens the playlist; reordering there leaves the player alone.
        compose.onNodeWithTag("player_full").performTouchInput { swipe(Offset(centerX, height * 0.3f), Offset(centerX, height * 0.1f), 200) }
        until(5_000, "playlist") { exists("playlist_list") }
        assertSettled(true, "playlist opened")
        val first = ps.activePlaylistItems.firstOrNull()?.title
        if (ps.activePlaylistItems.size >= 2 && first != null) {
            val tops = ps.activePlaylistItems.take(2).map { node("playlist_row_${it.title}").fetchSemanticsNode().boundsInRoot.top }
            compose.onNodeWithTag("playlist_handle_$first", useUnmergedTree = true).performTouchInput {
                down(center)
                moveInSteps(Offset(0f, (tops[1] - tops[0]) * 1.2f), 500, 20)
                up()
            }
            compose.waitForIdle()
            until(5_000, "moved") { ps.activePlaylistItems.getOrNull(1)?.title == first }
            assertEquals(1f, fraction)
            // Put it back.
            onUi { player.moveInActivePlaylist(1, 0) }
        }
        device.pressBack()
        until(5_000, "playlist closed") { !exists("playlist_list") }
        assertSettled(true, "Back closes the playlist first")
        pressBack()
        assertSettled(false, "then folds")
    }

    @Test fun s07_rotationAndRecreationInTheMiddleOfAMove() {
        playLong()
        // Recreated while opening: lands open.
        compose.mainClock.autoAdvance = false
        click("mini_player")
        compose.mainClock.advanceTimeBy(64)
        assertTrue("opening: ${fraction}", fraction > 0f && fraction < 1f)
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.recreate()
        until(10_000, "recreated") { exists("player_host") }
        assertSettled(true, "recreated while opening")

        // Recreated while folding: lands folded.
        compose.mainClock.autoAdvance = false
        click("btn_collapse_player")
        compose.mainClock.advanceTimeBy(64)
        assertTrue("folding: ${fraction}", fraction > 0f && fraction < 1f)
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.recreate()
        until(10_000, "recreated") { exists("player_host") }
        assertSettled(false, "recreated while folding")

        // Turned while opening (no recreation: the layout switches): goes on and ends open in landscape.
        compose.mainClock.autoAdvance = false
        click("mini_player")
        compose.mainClock.advanceTimeBy(64)
        device.setOrientationLeft()
        compose.mainClock.autoAdvance = true
        until(10_000, "landscape") { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        assertSettled(true, "turned while opening")
        // Landscape: down from the left panel folds, following the finger.
        drag("player_full", 0.3f, 0.7f * compose.onNodeWithTag("player_full").fetchSemanticsNode().boundsInRoot.height, 1_200, x = 0.25f)
        assertSettled(false, "landscape drag down")
        device.setOrientationNatural()
        until(10_000, "portrait") { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        assertSettled(false, "turned back")
        assertTrue(playing("Long"))
    }

    // Found while moving the seek gesture: the landscape seek bar kept the length of the track it was first shown with.
    @Test fun s09_landscapeSeekUsesTheCurrentTrackLength() {
        playLong() // 10:00
        device.setOrientationLeft()
        until(10_000, "landscape") { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        toPlayer()
        assertSettled(true, "open in landscape")
        // A 30 s track while the landscape player stays open.
        onUi { player.playCustomList(listOf(MusicFile("02 track.mp3", "$fx/Album-A/02 track.mp3", false, 0, 0, local)), 0) }
        until(15_000, "30 s track") { playing("02%20track") && ps.duration in 25_000..35_000 }
        compose.onNodeWithTag("seek_bar", useUnmergedTree = true).performTouchInput { click(Offset(width * 0.5f, centerY)) }
        until(5_000, "seeked to the middle of the 30 s track (${ps.currentPosition} ms)") { playing("02%20track") && ps.currentPosition in 12_000..22_000 }
    }

    @Test fun s08_repeatedAndReversedInputNeverLeavesItHalfWay() {
        playLong()
        val wasPlaying = ps.isPlaying
        compose.mainClock.autoAdvance = false
        click("mini_player")
        compose.mainClock.advanceTimeBy(48)
        // A tap on a fading button while the player moves does nothing but take the player (and let it go on).
        compose.onNodeWithTag("btn_play_pause", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(32)
        pressBack()                                   // reverse
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("player_full").performTouchInput { click(center) }   // caught, goes on folding
        compose.mainClock.advanceTimeBy(32)
        onSheet { expand() }                       // e.g. a song tapped
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("player_full").performTouchInput { click(center) }   // caught, goes on opening
        compose.mainClock.advanceTimeBy(16)
        onSheet { collapse() }
        compose.mainClock.advanceTimeBy(16)
        onSheet { expand() }
        compose.mainClock.autoAdvance = true
        assertSettled(true, "after the burst")
        Thread.sleep(500)
        assertEquals("the fading play button was not pressed", wasPlaying, ps.isPlaying)

        click("btn_collapse_player")
        assertSettled(false, "folded")

        // Caught in the middle of opening and dragged down: follows the finger, then folds.
        compose.mainClock.autoAdvance = false
        click("mini_player")
        compose.mainClock.advanceTimeBy(48)
        val h = compose.onNodeWithTag("player_full").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("player_full").performTouchInput {
            down(Offset(centerX, height * 0.4f))
            moveInSteps(Offset(0f, 0.6f * h), 1_000, 25)
            up()
        }
        compose.mainClock.autoAdvance = true
        assertSettled(false, "caught and pulled down")

        // Taps in quick succession where the mini player was: the first opens, the others only catch it.
        compose.mainClock.autoAdvance = false
        click("mini_player")
        repeat(5) {
            compose.mainClock.advanceTimeBy(16)
            compose.onNodeWithTag("player_full").performTouchInput { click(Offset(width * 0.5f, height - 40f)) }
        }
        compose.mainClock.autoAdvance = true
        assertSettled(true, "tapped repeatedly")
        pressBack()
        assertSettled(false, "Back")
        assertTrue("browser reachable after all this", browserShown())
    }
}
