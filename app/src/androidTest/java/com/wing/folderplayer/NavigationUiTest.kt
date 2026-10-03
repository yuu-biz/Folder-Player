package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.browser.BrowserViewModel
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
 * Browser-based navigation (docs/fork/UI_REDESIGN.md): browser start page, full player over it, mini player,
 * settings from the ⋮ menu, Back order, kept browsing state, notification tap, recreation and layout limits.
 */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest : UiTestBase() {
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

    @Before fun reset() {
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
    }

    @After fun pause() {
        // Through the service: the activity may already be gone (system Back test).
        runCatching {
            onMain { MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) } }
        }
    }

    private fun open(folder: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, folder)) }
        until(15_000, "folder $folder") { path == folder && !bs.isLoading }
        if (bs.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    private fun scrollList(index: Int) {
        compose.onNodeWithTag("file_list").performScrollToIndex(index)
        compose.waitForIdle()
    }

    private fun viaSettingsAndBack(useScreenButton: Boolean = false) {
        click("btn_overflow")
        click("menu_settings")
        until(10_000, "settings") { exists("settings_column") && !exists("btn_overflow") }
        if (useScreenButton) compose.onNodeWithContentDescription(str(R.string.common_close)).performClick() else pressBack()
        until(10_000, "browser after settings") { browserShown() }
    }

    // §9-1
    @Test fun n01_coldStartShowsTheBrowserAndPlaysWithoutThePlayerPage() {
        until(10_000, "browser at start") { browserShown() }
        assertFalse("the player is not the start page", playerOpen())

        // Playback does not depend on the full player having been shown.
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing without the full player") { playing("Long") }
        assertFalse(playerOpen())
        until(5_000, "mini player") { exists("mini_player") }

        // Tapping a song plays it and opens the full player.
        open("$fx/Album-A")
        click("item_02 track.mp3")
        until(5_000, "full player opened by the song tap") { playerOpen() }
        until(15_000, "song playing") { playing("02%20track") }
    }

    // §9-2
    @Test fun n02_backFromThePlayerKeepsFolderScrollPositionAndPlayback() {
        open("$fx/Many")
        scrollList(150)
        until(5_000, "row 150") { exists("item_Folder 150") }
        click("btn_shuffle")
        until(5_000, "full player") { playerOpen() }
        until(15_000, "a folder of Many playing") { playing("Many") }
        val id = ps.currentMediaId
        pressBack()
        until(5_000, "browser") { browserShown() }
        assertEquals("$fx/Many", path)
        assertTrue("same scroll position", exists("item_Folder 150"))
        assertFalse(exists("item_Folder 000"))
        Thread.sleep(1_000)
        assertTrue("still playing", ps.isPlaying)
        assertEquals(id, ps.currentMediaId)

        // A song in a deeper folder; the on-screen collapse button does what Back does.
        open("$fx/Album-A")
        click("item_02 track.mp3")
        until(5_000, "full player") { playerOpen() }
        until(15_000, "playing") { playing("02%20track") }
        click("btn_collapse_player")
        until(5_000, "browser") { browserShown() }
        assertEquals("$fx/Album-A", path)
        assertTrue(exists("item_02 track.mp3"))
        assertTrue(playing("02%20track"))
    }

    // §9-3 (and §9-6 for search / favourites)
    @Test fun n03_searchAndFavoritesStayAfterThePlayerAndSettings() {
        open(fx)
        click("btn_search")
        compose.onNodeWithTag("search_field").performTextInput("track 01")
        until(30_000, "search done") { bs.search.let { !it.running && it.results.isNotEmpty() } }
        val results = bs.search.results.size
        until(5_000, "result row") { exists("item_track 015.flac") }
        click("item_track 015.flac")
        until(5_000, "full player") { playerOpen() }
        until(15_000, "result playing") { playing("track%20015") }
        pressBack()
        until(5_000, "search results again") { !playerOpen() && exists("search_field") }
        assertTrue(bs.search.active)
        assertEquals("track 01", bs.search.query)
        assertEquals(results, bs.search.results.size)
        assertEquals("track 01", text("search_field"))
        assertTrue(exists("item_track 015.flac"))

        viaSettingsAndBack()
        assertTrue("search kept after Settings", bs.search.active && exists("search_field"))
        assertEquals("track 01", text("search_field"))
        assertEquals(results, bs.search.results.size)

        pressBack()
        until(5_000, "search closed") { !bs.search.active }
        assertEquals(fx, path)

        // Favourites list.
        val song = MusicFile("02 track.mp3", "$fx/Album-A/02 track.mp3", false, 0, 0, local)
        val wasFavorite = onUi { browser.isFavorite(song) }
        if (!wasFavorite) onUi { browser.toggleFavorite(song) }
        try {
            onUi { browser.exitSource() }
            until(5_000, "source list") { exists("entry_favorites") }
            click("entry_favorites")
            until(5_000, "favourites") { bs.showingFavorites && exists("item_02 track.mp3") }
            click("item_02 track.mp3")
            until(5_000, "full player") { playerOpen() }
            until(15_000, "favourite playing") { playing("02%20track") }
            pressBack()
            until(5_000, "favourites again") { browserShown() }
            assertTrue(bs.showingFavorites)
            viaSettingsAndBack()
            assertTrue("favourites kept after Settings", bs.showingFavorites)
            pressBack()
            until(5_000, "source list after favourites") { bs.isRoot && exists("source_list") }
        } finally {
            if (!wasFavorite) onUi { browser.toggleFavorite(song) }
        }
    }

    // §9-4
    @Test fun n04_miniPlayerTapOpensTheButtonsOnlyControlPlayback() {
        toBrowser()
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        until(5_000, "mini player") { exists("mini_player") }
        compose.onNodeWithTag("mini_play_pause").assert(hasContentDescription(str(R.string.player_pause)))

        click("mini_play_pause")
        until(5_000, "paused") { !ps.isPlaying }
        assertFalse("play/pause does not open the player", playerOpen())
        Thread.sleep(1_000)
        assertTrue("paused: mini player stays", exists("mini_player"))
        compose.onNodeWithTag("mini_play_pause").assert(hasContentDescription(str(R.string.player_play)))

        click("mini_play_pause")
        until(5_000, "playing again") { ps.isPlaying }
        assertFalse(playerOpen())

        click("mini_player")
        until(5_000, "full player") { playerOpen() }
        // Not shown twice: the mini player under the full one is hidden from what the user / TalkBack can reach.
        compose.onAllNodesWithTag("mini_player").assertCountEquals(0)
        click("btn_collapse_player")
        until(5_000, "mini again") { browserShown() && exists("mini_player") }
    }

    // §9-5, §5 (no duplicate controller), recreation
    @Test fun n05_openingAndClosingKeepsTrackQueuePositionAndController() {
        toBrowser()
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") && ps.currentPosition > 1_000 }
        val id = ps.currentMediaId
        val queue = ps.playlist.map { it.mediaId }
        val active = ps.activePlaylistItems.map { it.path }
        val controllers = onMain { service().connectedControllerCount }
        Fx.log("controllers before: $controllers")
        var lastPos = ps.currentPosition
        repeat(6) { i ->
            click("mini_player")
            until(5_000, "open #$i") { playerOpen() }
            Thread.sleep(300)
            if (i % 2 == 0) pressBack() else click("btn_collapse_player")
            until(5_000, "closed #$i") { browserShown() }
            Thread.sleep(1_100)
            assertEquals(id, ps.currentMediaId)
            assertEquals(queue, ps.playlist.map { it.mediaId })
            assertEquals(active, ps.activePlaylistItems.map { it.path })
            assertTrue("playing after #$i", ps.isPlaying)
            assertTrue("position advances (#$i: ${ps.currentPosition} after $lastPos)", ps.currentPosition > lastPos)
            lastPos = ps.currentPosition
        }
        assertEquals("no controller added by opening the player", controllers, onMain { service().connectedControllerCount })

        // Recreation (as on a language change): same session, nothing restarted, state kept.
        click("mini_player")
        until(5_000, "open") { playerOpen() }
        compose.activityRule.scenario.recreate()
        until(10_000, "player open after recreation") { playerOpen() }
        assertEquals(id, ps.currentMediaId)
        assertTrue(ps.isPlaying)
        assertTrue(ps.currentPosition >= lastPos)
        assertEquals("recreation keeps one controller", controllers, onMain { service().connectedControllerCount })
        pressBack()
        until(5_000, "browser") { browserShown() }
    }

    // §9-6
    @Test fun n06_settingsFromEveryBrowserLevelReturnsToTheSamePlace() {
        toBrowser()
        onUi { browser.exitSource() }
        until(5_000, "source list") { bs.isRoot && exists("source_list") }
        viaSettingsAndBack()
        assertTrue(bs.isRoot && exists("source_list"))

        open("/")
        viaSettingsAndBack(useScreenButton = true)
        assertEquals("/", path)

        open("$fx/Many")
        scrollList(200)
        until(5_000, "row 200") { exists("item_Folder 200") }
        viaSettingsAndBack()
        assertEquals("$fx/Many", path)
        assertTrue("scroll position kept", exists("item_Folder 200"))
        assertFalse(exists("item_Folder 000"))

        click("btn_view_mode")
        until(5_000, "grid") { exists("file_grid") }
        viaSettingsAndBack()
        assertTrue("view mode kept", exists("file_grid"))
        click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }

        onUi { browser.showFavorites() }
        until(5_000, "favourites") { bs.showingFavorites }
        viaSettingsAndBack()
        assertTrue(bs.showingFavorites)
    }

    // §9-7
    @Test fun n07_backGoesUpToTheSourceRootThenTheSourceListThenToTheSystem() {
        open("$fx/Album-A")
        // The on-screen arrow is the same as Back.
        click("btn_browser_back")
        until(5_000, "fixture") { path == fx }
        pressBack(); until(5_000, "Music") { path == "/Music" }
        pressBack(); until(5_000, "source root") { path == "/" }
        assertFalse("the source root is not the source list", bs.isRoot)
        pressBack(); until(5_000, "source list") { bs.isRoot && exists("source_list") }
        assertFalse(exists("btn_browser_back"))
        assertFalse("source list: Back is left to the system", onUi { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() })

        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        assertFalse(onUi { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() })
        device.pressBack()
        Thread.sleep(2_000)
        assertTrue("system Back leaves the app without stopping playback", onMain { service().isPlayingForTest })
    }

    // §9-8
    @Test fun n08_backClosesSheetMenuKeyboardAndPlayerBeforeTheBrowserMoves() {
        open("$fx/Album-A")

        longClick("item_02 track.mp3")
        until(5_000, "file actions sheet") { exists("action_favorite") }
        device.pressBack()
        until(5_000, "sheet closed") { !exists("action_favorite") }
        assertEquals("$fx/Album-A", path)

        click("btn_overflow")
        until(5_000, "menu") { exists("menu_settings") }
        device.pressBack()
        until(5_000, "menu closed") { !exists("menu_settings") }
        assertEquals("$fx/Album-A", path)
        assertFalse(exists("settings_column"))

        click("btn_search")
        compose.onNodeWithTag("search_field").performClick()
        val ime = runCatching { until(5_000, "keyboard") { imeVisible() }; true }.getOrDefault(false)
        if (ime) {
            device.pressBack()
            until(5_000, "keyboard hidden") { !imeVisible() }
            assertTrue("the first Back only hides the keyboard", bs.search.active)
        } else Fx.log("no on-screen keyboard on this device: keyboard step not checked")
        device.pressBack()
        until(5_000, "search closed") { !bs.search.active }
        assertEquals("$fx/Album-A", path)

        click("item_02 track.mp3")
        until(5_000, "full player") { playerOpen() }
        device.pressBack()
        until(5_000, "player closed") { browserShown() }
        assertEquals("$fx/Album-A", path)

        toSettings()
        device.pressBack()
        until(5_000, "settings closed") { browserShown() }
        assertEquals("$fx/Album-A", path)
    }

    private fun imeVisible(): Boolean = onUi {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    // §9-9
    @Test fun n09_failedAndWaitingTracksStillOpenCloseAndReturn() {
        open("$fx/Album-A")
        val missing = MusicFile("missing.flac", "$fx/Album-A/missing.flac", false, 0, 0, local)
        onUi { player.playCustomList(listOf(missing), 0) }
        until(5_000, "mini player at once") { exists("mini_player") }
        until(30_000, "error") { ps.playbackError != null }
        until(5_000, "error in the mini player") { exists("mini_error") }
        click("mini_player")
        until(5_000, "full player") { playerOpen() }
        until(5_000, "error in the full player") { exists("playback_error") }
        pressBack()
        until(5_000, "browser") { browserShown() }
        assertEquals("$fx/Album-A", path)

        // A server that never answers: the track stays loading; the player still opens and closes.
        val dead = SourceConfig(name = "nav-unreachable", type = SourceType.WEBDAV, url = "http://10.255.255.1/dav")
        SourceRegistry.upsert(dead, "x")
        try {
            onUi { player.playCustomList(listOf(MusicFile("wait.flac", "/wait.flac", false, 0, 0, dead.id)), 0) }
            until(5_000, "loading shown") { ps.isBuffering && exists("mini_buffering") }
            click("mini_player")
            until(5_000, "full player while loading") { playerOpen() }
            pressBack()
            until(5_000, "browser while loading") { browserShown() }
            assertEquals("$fx/Album-A", path)
            assertTrue(exists("mini_player"))
        } finally {
            onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
            until(15_000, "local track again") { playing("Long") }
            SourceRegistry.remove(dead.id)
        }
    }

    // §9-10
    @Test fun n10_notificationTapOpensThePlayerInTheSameActivityAndBackReturnsToTheBrowser() {
        open("$fx/Album-A")
        onUi { player.playFolder(SourceRef(local, "$fx/Long"), null) }
        until(15_000, "playing") { playing("Long") }
        toSettings()
        val activity = compose.activity
        val controllers = onMain { service().connectedControllerCount }
        // The intent MusicService puts into the notification (session activity).
        activity.startActivity(
            Intent(Fx.ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        until(10_000, "full player from the notification") { playerOpen() }
        val resumed = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
        assertEquals("no second activity", 1, resumed.size)
        assertSame(activity, resumed.single())
        assertEquals(controllers, onMain { service().connectedControllerCount })
        assertTrue(playing("Long"))

        pressBack()
        until(5_000, "browser, not settings") { browserShown() }
        assertEquals("$fx/Album-A", path)

        // Recreation keeps the open page and does not apply the intent again.
        toSettings()
        compose.activityRule.scenario.recreate()
        until(10_000, "settings after recreation") { exists("settings_column") }
        assertFalse(playerOpen())
        pressBack()
        until(5_000, "browser") { browserShown() }
        assertEquals("$fx/Album-A", path)
        assertTrue(playing("Long"))
    }

    // §9-11
    @Test fun n11_miniPlayerFitsLongTitlesNarrowScreensAndLandscapeAndNeverCoversTheLastRow() {
        open("$fx/Many")
        val longName = "とても長い日本語の曲名がミニプレーヤーの幅を超えても操作ボタンを押し出さないことの確認用ファイル名.flac"
        onUi { player.playCustomList(listOf(MusicFile(longName, "$fx/none/$longName", false, 0, 0, local)), 0) }
        until(5_000, "mini player") { exists("mini_player") && exists("mini_title") }

        fun checkMini(label: String) {
            compose.waitForIdle()
            val root = compose.onNodeWithTag("app_pages").fetchSemanticsNode().boundsInRoot
            val title = node("mini_title").fetchSemanticsNode().boundsInRoot
            val button = node("mini_play_pause").fetchSemanticsNode().boundsInRoot
            val minPx = 44 * compose.activity.resources.displayMetrics.density
            Fx.log("$label: root=$root title=$title button=$button")
            assertTrue("$label: button stays on screen", button.right <= root.right + 1 && button.left >= root.left)
            assertTrue("$label: button keeps its size", button.width >= minPx && button.height >= minPx)
            assertTrue("$label: the title ends before the button", title.right <= button.left + 1)
        }
        checkMini("portrait")

        // The last row can be scrolled above the mini player.
        scrollList(299)
        until(5_000, "last row") { exists("item_Folder 299") }
        val last = node("item_Folder 299").fetchSemanticsNode().boundsInRoot
        val mini = node("mini_player").fetchSemanticsNode().boundsInRoot
        assertTrue("last row ($last) not under the mini player ($mini)", last.bottom <= mini.top + 1)

        // Narrow phone (about 320 dp wide).
        val density = compose.activity.resources.displayMetrics.density
        Fx.shell("wm size ${(320 * density).toInt()}x${(640 * density).toInt()}")
        try {
            until(10_000, "narrow layout") { compose.activity.resources.configuration.screenWidthDp in 300..340 }
            checkMini("narrow")
            assertFalse("no next button on a narrow screen", exists("mini_next"))
        } finally {
            Fx.shell("wm size reset")
        }

        device.setOrientationLeft()
        try {
            until(10_000, "landscape") { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            until(5_000, "browser in landscape") { browserShown() && exists("mini_player") }
            checkMini("landscape")
            click("mini_player")
            until(5_000, "full player in landscape") { playerOpen() }
            pressBack()
            until(5_000, "browser in landscape again") { browserShown() }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
}
