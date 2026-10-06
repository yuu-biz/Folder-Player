package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerSheetKey
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adaptive layout (docs/fork/UI_REDESIGN.md, "Wide windows"): the window size decides between the phone UI (browser, mini
 * player, sheet) and the wide UI (browser and docked player side by side). The size is changed for real (`wm size`), so
 * the activity gets the same configuration changes as on a fold / unfold, a split-screen resize or a tablet.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val ps get() = player.uiState.value
    private val bs get() = browser.uiState.value
    private val path get() = bs.currentFolder?.path

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun <T> onMain(block: () -> T): T {
        var r: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST") return r as T
    }
    private fun service() = MusicService.current ?: error("service not running")
    private fun playing(fragment: String) = ps.isPlaying && ps.currentMediaId?.contains(fragment) == true
    private fun shown(tag: String) = runCatching { node(tag).assertIsDisplayed(); true }.getOrDefault(false)
    private val density get() = compose.activity.resources.displayMetrics.density

    @Before fun reset() {
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
    }

    @After fun cleanup() {
        Fx.shell("wm size reset")
        runCatching {
            onMain { MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) } }
        }
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    /** The window gets this size (in dp, at the screen's density) and the activity has reported it. */
    private fun windowDp(w: Int, h: Int) {
        Fx.shell("wm size ${(w * density).toInt()}x${(h * density).toInt()}")
        until(10_000, "window ${w}x$h dp") {
            val c = compose.activity.resources.configuration
            // The height reported is the window without the system bars (50 to 110 dp less).
            c.screenWidthDp in (w - 8)..(w + 8) && c.screenHeightDp in (h - 130)..(h + 8)
        }
        compose.waitForIdle()
    }

    private fun narrowWindow() {
        Fx.shell("wm size reset")
        until(10_000, "phone window") { compose.activity.resources.configuration.screenWidthDp < 500 }
        compose.waitForIdle()
    }

    private fun open(folder: String) {
        until(10_000, "browser") { exists("source_list") || exists("file_list") || exists("file_grid") }
        onUi { browser.loadFolder(SourceRef(local, folder)) }
        until(15_000, "folder $folder") { path == folder && !bs.isLoading }
        if (bs.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    private fun widePane() = exists("player_pane")

    // Browser and player are on screen together; the phone parts (mini player, sheet) are not.
    @Test fun a01_wideWindowShowsBrowserAndDockedPlayerTogether() {
        toBrowser()
        onUi { player.dismissSession() }
        until(10_000, "no track") { !ps.hasTrack }
        windowDp(780, 600)
        until(5_000, "player pane") { widePane() }
        assertTrue("empty pane says so", exists("player_pane_empty"))
        assertFalse(exists("mini_player"))
        assertFalse(exists("player_full"))

        open("$fx/Album-A")
        click("item_02 track.mp3")
        until(15_000, "playing") { playing("02%20track") }
        until(5_000, "docked player") { exists("player_docked") }
        assertFalse("no mini player beside the player", exists("mini_player"))
        assertFalse("no sheet", exists("player_full"))
        assertFalse("nothing to fold", exists("btn_collapse_player"))
        assertTrue("controls", exists("btn_play_pause") && exists("seek_bar"))
        assertTrue("browser still there", shown("item_02 track.mp3"))
        // Side by side: the browser column is on the left of the player.
        val list = node("file_list").fetchSemanticsNode().boundsInRoot
        val pane = node("player_pane").fetchSemanticsNode().boundsInRoot
        assertTrue("browser ($list) left of player ($pane)", list.right <= pane.left + 2)
        assertTrue("browser is a column, not half the window", list.width < pane.width)

        click("btn_play_pause")
        until(5_000, "paused") { !ps.isPlaying }
        click("btn_play_pause")
        until(5_000, "playing again") { ps.isPlaying }
    }

    // Narrow → wide → narrow: the same session, queue, position, browser place and controller.
    @Test fun a02_resizeKeepsTrackQueueBrowserPositionAndController() {
        toBrowser()
        open("$fx/Many")
        compose.onNodeWithTag("file_list").performScrollToIndex(200)
        compose.waitForIdle()
        until(5_000, "row 200") { exists("item_Folder 200") }
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") && ps.currentPosition > 1_000 }
        until(5_000, "mini player") { exists("mini_player") }
        val activity = compose.activity
        val id = ps.currentMediaId
        val queue = ps.playlist.map { it.mediaId }
        val folder = path
        val controllers = onMain { service().connectedControllerCount }
        val before = ps.currentPosition
        assertTrue(shown("item_Folder 200"))

        windowDp(780, 600)
        until(5_000, "docked player") { exists("player_docked") }
        assertSame("same activity (no recreation)", activity, compose.activity)
        assertEquals(id, ps.currentMediaId)
        assertEquals(queue, ps.playlist.map { it.mediaId })
        assertEquals(folder, path)
        assertTrue("still playing", ps.isPlaying)
        assertTrue("position kept or advancing (${ps.currentPosition} after $before)", ps.currentPosition >= before)
        assertTrue("browser scroll position kept", shown("item_Folder 200"))
        assertFalse(shown("item_Folder 000"))
        assertEquals("no controller added", controllers, onMain { service().connectedControllerCount })

        narrowWindow()
        until(5_000, "mini player again") { exists("mini_player") }
        assertSame(activity, compose.activity)
        assertEquals(id, ps.currentMediaId)
        assertEquals(queue, ps.playlist.map { it.mediaId })
        assertTrue(ps.isPlaying)
        assertEquals(folder, path)
        assertTrue("browser scroll position kept", shown("item_Folder 200"))
        assertEquals("no controller added", controllers, onMain { service().connectedControllerCount })
    }

    // The full player open on the phone, then the window gets wide and narrow again.
    @Test fun a03_fullPlayerOpenOnThePhoneIsTheDockedPlayerWhenWideAndComesBack() {
        toBrowser()
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        toPlayer()
        val id = ps.currentMediaId
        windowDp(780, 600)
        until(5_000, "docked player") { exists("player_docked") }
        assertFalse("the sheet is not drawn in the wide layout", exists("player_full"))
        assertTrue("the browser is usable", shown("item_02 track.mp3"))
        node("item_02 track.mp3").assertIsDisplayed()
        narrowWindow()
        until(5_000, "full player again") { playerOpen() }
        assertEquals(id, ps.currentMediaId)
        click("btn_collapse_player")
        until(5_000, "mini") { browserShown() && exists("mini_player") }
    }

    // Resizing while the track is paused, and right after a play request (still loading).
    @Test fun a04_resizeWhilePausedAndWhileLoading() {
        toBrowser()
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") && ps.currentPosition > 500 }
        onUi { player.playPause() }
        until(5_000, "paused") { !ps.isPlaying }
        val pos = ps.currentPosition
        windowDp(780, 600)
        until(5_000, "docked") { exists("player_docked") }
        Thread.sleep(800)
        assertFalse("still paused", ps.isPlaying)
        assertTrue("position kept ($pos → ${ps.currentPosition})", kotlin.math.abs(ps.currentPosition - pos) < 1_000)
        narrowWindow()
        until(5_000, "mini") { exists("mini_player") }
        assertFalse(ps.isPlaying)

        // A request in flight while the window changes.
        onUi { player.playFolder(SourceRef(local, "$fx/Album-A"), null) }
        windowDp(780, 600)
        until(15_000, "playing after the resize") { ps.isPlaying && ps.currentMediaId?.contains("Album-A") == true }
        until(5_000, "docked") { exists("player_docked") }
        narrowWindow()
        until(5_000, "mini") { exists("mini_player") }
        assertTrue(ps.isPlaying)
    }

    // Back in the wide layout: folder hierarchy, search, Settings pages; the player pane is never "closed".
    @Test fun a05_backInTheWideLayout() {
        toBrowser()
        windowDp(780, 600)
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        until(5_000, "docked") { exists("player_docked") }

        // Search first, then the folder hierarchy.
        click("btn_search")
        until(5_000, "search") { bs.search.active }
        pressBack()
        until(5_000, "search closed") { !bs.search.active }
        assertEquals("$fx/Album-A", path)
        assertTrue("the player pane stays", exists("player_docked"))
        pressBack()
        until(5_000, "parent folder") { path == fx }
        assertTrue(exists("player_docked"))
        assertTrue("playing on", ps.isPlaying)

        // Settings: the page, then the category, then the browser.
        click("btn_overflow")
        click("menu_settings")
        until(10_000, "settings") { exists("settings_column") && !exists("btn_overflow") }
        assertTrue("two panes: categories beside the page", exists("settings_categories") && exists("dlna_enabled"))
        click("settings_cat_display")
        click("settings_sub_language")
        until(5_000, "language page") { exists("lang_ja") }
        pressBack()
        until(5_000, "back to the category") { !exists("lang_ja") && exists("cover_LARGE") && exists("settings_column") }
        pressBack()
        until(10_000, "browser") { exists("player_docked") && !exists("settings_column") }
        assertEquals(fx, path)
        assertTrue(ps.isPlaying)
    }

    // Wide but low window: the pane uses the landscape player layout; Back closes its playlist before the browser moves.
    @Test fun a06_backClosesTheDockedPlaylistOverlayBeforeTheBrowser() {
        toBrowser()
        windowDp(1200, 580)
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        until(5_000, "docked") { exists("player_docked") }
        // Up in the left panel (controls) opens the playlist overlay.
        compose.onNodeWithTag("player_docked").performTouchInput { swipe(Offset(width * 0.2f, height * 0.8f), Offset(width * 0.2f, height * 0.3f), 200) }
        until(5_000, "playlist overlay") { exists("playlist_list") }
        pressBack()
        until(5_000, "overlay closed") { !exists("playlist_list") }
        assertEquals("the browser has not moved", "$fx/Album-A", path)
        pressBack()
        until(5_000, "now the browser goes up") { path == fx }
    }

    // The seek bar, the playlist gesture and the controls work in the pane as in the sheet.
    @Test fun a07_dockedPlayerGestures() {
        toBrowser()
        windowDp(780, 900)
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") && ps.currentPosition > 500 }
        until(5_000, "docked") { exists("player_docked") }
        // Slider semantics for accessibility services.
        compose.onNodeWithTag("seek_bar", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        val dur = ps.duration
        compose.onNodeWithTag("seek_bar", useUnmergedTree = true).performTouchInput { click(Offset(width * 0.5f, centerY)) }
        until(5_000, "seeked to the middle") { kotlin.math.abs(ps.currentPosition - dur / 2) < dur / 10 }
        // Swipe up (outside the seek bar and the lyrics list): the playlist.
        compose.onNodeWithTag("player_docked").performTouchInput { swipe(Offset(centerX, height * 0.85f), Offset(centerX, height * 0.55f), 200) }
        until(5_000, "playlist") { exists("playlist_list") }
        device.pressBack() // the playlist of the portrait player is a sheet in its own window
        until(5_000, "playlist closed") { !exists("playlist_list") }
        // Swipe down does not fold anything.
        compose.onNodeWithTag("player_docked").performTouchInput { swipe(Offset(centerX, height * 0.55f), Offset(centerX, height * 0.9f), 200) }
        compose.waitForIdle()
        assertTrue(exists("player_docked") && exists("btn_play_pause"))
        assertTrue(ps.isPlaying)
    }

    // The notification opens "the player": in the wide layout it is there already; nothing else changes.
    @Test fun a08_notificationTapInTheWideLayout() {
        toBrowser()
        windowDp(780, 600)
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        val activity = compose.activity
        val controllers = onMain { service().connectedControllerCount }
        activity.startActivity(
            Intent(Fx.ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertTrue(exists("player_docked"))
        assertFalse(exists("player_full"))
        assertTrue("browser unchanged", shown("item_02 track.mp3"))
        assertEquals("$fx/Album-A", path)
        assertEquals(controllers, onMain { service().connectedControllerCount })
        assertTrue(ps.isPlaying)
        // And back on the phone it is the mini player (the request was answered by the pane, not stored for later).
        narrowWindow()
        until(5_000, "mini") { exists("mini_player") }
        assertFalse(playerOpen())
    }

    // The window changes while a finger drags the sheet: it never stays half-way.
    @Test fun a09_resizeInTheMiddleOfADragSettlesAtAnEnd() {
        toBrowser()
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        until(5_000, "mini") { exists("mini_player") }
        fun sheet() = compose.onNodeWithTag("player_host").fetchSemanticsNode().config[PlayerSheetKey]
        compose.onNodeWithTag("mini_player").performTouchInput {
            down(center)
            moveBy(Offset(0f, -300f))
        }
        until(5_000, "dragging") { sheet().isDragging && sheet().fraction > 0f }
        val state = sheet()
        windowDp(780, 600)
        compose.waitForIdle()
        assertFalse("not dragging any more", state.isDragging)
        assertFalse(state.isAnimating)
        assertTrue("an end: ${state.fraction}", state.fraction == 0f || state.fraction == 1f)
        narrowWindow()
        until(5_000, "phone UI") { exists("mini_player") || playerOpen() }
        val s2 = sheet()
        assertTrue("an end after the way back: ${s2.fraction}", s2.fraction == 0f || s2.fraction == 1f)
        assertFalse(s2.isDragging)
        assertTrue(ps.isPlaying)
    }

    // Rotation of a wide window (both sides above the limits) keeps the two panes.
    @Test fun a10_wideWindowRotatesWithoutLosingState() {
        toBrowser()
        windowDp(780, 600)
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        windowDp(540, 780)
        // 540 dp wide is narrow: phone UI with the same session.
        until(5_000, "phone UI") { exists("mini_player") }
        assertEquals("$fx/Album-A", path)
        assertTrue(ps.isPlaying)
        windowDp(780, 600)
        until(5_000, "wide again") { exists("player_docked") }
        assertEquals("$fx/Album-A", path)
        assertTrue(ps.isPlaying)
    }
}
