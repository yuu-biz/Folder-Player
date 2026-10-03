package com.wing.folderplayer

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Browser UI: grid/list, thumbnails, refresh, cache clear, 300 folders. */
@RunWith(AndroidJUnit4::class)
class BrowserUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = "/Music/fixture"

    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun open(sourceId: String, path: String, mode: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(sourceId, path)) }
        until(15_000, "folder $path") { browser.uiState.value.currentFolder?.path == path && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != mode) click("btn_view_mode")
        until(5_000, "$mode view") { exists(if (mode == "GRID") "file_grid" else "file_list") }
    }

    private fun thumbColour(name: String): Triple<Int, Int, Int> {
        val bmp = node("thumb_$name").captureToImage().asAndroidBitmap()
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in bmp.width / 4 until bmp.width * 3 / 4 step 3) for (y in bmp.height / 2 until bmp.height * 7 / 8 step 3) {
            val c = bmp.getPixel(x, y); r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun close(c: Triple<Int, Int, Int>, rgb: Triple<Int, Int, Int>, tol: Int = 45) =
        kotlin.math.abs(c.first - rgb.first) <= tol && kotlin.math.abs(c.second - rgb.second) <= tol && kotlin.math.abs(c.third - rgb.third) <= tol

    private fun settingSwitch(tag: String, on: Boolean) {
        toSettings()
        compose.onNodeWithTagScrolled(tag)
        val isOn = runCatching { node(tag).assertIsOn(); true }.getOrDefault(false)
        if (isOn != on) click(tag)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithTagScrolled(tag: String) =
        onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private fun shell(cmd: String) = Fx.shell(cmd).also { Fx.log("shell $cmd -> ${it.trim()}") }
    private fun rescan() = shell("content call --uri content://media/external/file --method scan_volume --arg external_primary")

    @Before fun reset() {
        Fx.ctx.getSharedPreferences("artwork_prefs", 0).edit().clear().commit()
        // a14 ends with LateCover/cover.jpg found (and indexed) and its @After deletes the file: forget that entry, or a
        // second run on the same app data sees a stale "found" image (the browser, now the start page, shows it at once).
        com.wing.folderplayer.data.artwork.ThumbnailRepository.get(Fx.ctx).invalidate(SourceRef(local, "$fx/LateCover"))
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
        SourceRegistry.savedSources().filter { it.type != SourceType.SAF }.forEach { SourceRegistry.remove(it.id) }
        compose.activityRule.scenario.recreate()
    }

    @After fun cleanup() {
        shell("rm -f /sdcard/Music/fixture/LateCover/cover.jpg")
    }

    @Test fun a13_thumbnailDefaultsAndGridListMemoryAndDensity() {
        // Defaults: Local/SAF on, network off.
        toSettings()
        for (t in listOf("LOCAL", "SAF")) { compose.onNodeWithTagScrolled("thumbs_$t"); node("thumbs_$t").assertIsOn() }
        for (t in listOf("WEBDAV", "SMB", "FTP")) { compose.onNodeWithTagScrolled("thumbs_$t"); node("thumbs_$t").assertIsOff() }
        compose.onNodeWithTagScrolled("thumbs_wifi")

        // Per-folder view memory.
        open(local, fx, "GRID")
        until(15_000, "Album-A thumbnail") { exists("thumb_Album-A") }
        assertTrue("red folder image", close(thumbColour("Album-A"), Triple(230, 20, 20)))
        onUi { browser.loadFolder(SourceRef(local, "$fx/Album-A")) }
        until(10_000, "Album-A list (default view)") { browser.uiState.value.currentFolder?.path == "$fx/Album-A" && exists("file_list") }
        onUi { browser.navigateUp() }
        until(10_000, "fixture grid remembered") { browser.uiState.value.currentFolder?.path == fx && exists("file_grid") }

        // Grid density from settings changes the number of columns.
        for (cols in listOf(2, 5)) {
            toSettings()
            compose.onNodeWithTagScrolled("grid_$cols"); click("grid_$cols")
            toBrowser()
            until(5_000, "grid") { exists("file_grid") }
            val gridW = node("file_grid").fetchSemanticsNode().boundsInRoot.width
            val itemW = node("item_Album-A").fetchSemanticsNode().boundsInRoot.width
            val measured = (gridW / itemW).toInt()
            Fx.log("density $cols: grid=$gridW item=$itemW -> $measured columns")
            assertEquals(cols, measured)
        }
    }

    @Test fun a13_thumbnailsOffKeepPlayerCover() {
        settingSwitch("thumbs_LOCAL", false)
        open(local, fx, "GRID")
        Thread.sleep(2_000)
        assertFalse("no folder image while thumbnails are off", exists("thumb_Album-A"))
        onUi { player.playFolder(SourceRef(local, "$fx/Album-A"), "$fx/Album-A/02 track.mp3") }
        until(15_000, "player cover from folder image") { player.uiState.value.coverUri?.toString()?.contains("Album-A/cover.jpg") == true && player.uiState.value.isPlaying }
        toPlayer()
        until(10_000, "cover drawn") { exists("player_cover") }
        var rgb = Triple(0, 0, 0)
        until(15_000, "red cover drawn in the player") {
            val bmp = node("player_cover").captureToImage().asAndroidBitmap()
            val c = bmp.getPixel(bmp.width / 2, bmp.height * 2 / 3)
            rgb = Triple((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
            close(rgb, Triple(230, 20, 20))
        }
        Fx.log("player cover pixel with thumbnails off: $rgb")
        onUi { player.playPause() }
    }

    @Test fun a13_networkThumbnailsOptInAndWifiOnly() {
        Fx.require("smb_host")
        val smb = SourceConfig(name = "smb-thumbs", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", path = "/fixture", username = "alice")
        SourceRegistry.upsert(smb, "alicepass")
        open(smb.id, "/", "GRID")
        Thread.sleep(3_000)
        assertFalse("network thumbnails are off by default", exists("thumb_Album-A"))
        settingSwitch("thumbs_SMB", true)
        open(smb.id, "/", "GRID")
        click("btn_refresh")
        until(20_000, "SMB thumbnail") { exists("thumb_Album-A") }
        assertTrue(close(thumbColour("Album-A"), Triple(230, 20, 20)))
        // Wi-Fi only: without Wi-Fi the network thumbnails are not loaded (not an error, not "no image").
        settingSwitch("thumbs_wifi", true)
        shell("svc wifi disable")
        try {
            Thread.sleep(3_000)
            val cm = Fx.ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            Fx.log("after Wi-Fi off: active network caps = $caps")
            open(smb.id, "/", "GRID")
            click("btn_refresh")
            Thread.sleep(4_000)
            assertFalse(exists("thumb_Album-A")); assertFalse(exists("thumb_err_Album-A")); assertFalse(exists("thumb_none_Album-A"))
        } finally {
            shell("svc wifi enable")
        }
        until(60_000, "Wi-Fi back") { runCatching { java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(Fx.arg("smb_host"), 445), 2_000) } }.isSuccess }
        click("btn_refresh")
        until(20_000, "thumbnail back on Wi-Fi") { exists("thumb_Album-A") }
    }

    @Test fun a14_lateCoverSameNameUpdateErrorVsNoneAndCacheClear() {
        shell("rm -f /sdcard/Music/fixture/LateCover/cover.jpg"); rescan()
        open(local, fx, "GRID")
        compose.onNodeWithTag("file_grid").performScrollToIndex(browser.uiState.value.files.indexOfFirst { it.name == "LateCover" })
        until(15_000, "LateCover has no image") { exists("thumb_none_LateCover") }

        // Image added later → found after refresh.
        shell("cp /sdcard/Music/fixture/Album-A/cover.jpg /sdcard/Music/fixture/LateCover/cover.jpg"); rescan()
        until(20_000, "new image visible to the app") { Fx.localFile("/Music/fixture/LateCover").list()?.contains("cover.jpg") == true }
        click("btn_refresh")
        until(15_000, "late cover") { exists("thumb_LateCover") }
        assertTrue(close(thumbColour("LateCover"), Triple(230, 20, 20)))
        // Same name, new content (cyan) → refresh shows the new image.
        Thread.sleep(1_100)
        shell("cp /sdcard/Music/fixture/Parent/cover.jpg /sdcard/Music/fixture/LateCover/cover.jpg"); rescan()
        click("btn_refresh")
        until(15_000, "updated cover") { exists("thumb_LateCover") && close(thumbColour("LateCover"), Triple(0, 210, 220)) }

        // A folder whose listing is denied is an error, not "no image".
        Fx.require("smb_host")
        val smbRoot = SourceConfig(name = "smb-root", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", username = "alice")
        SourceRegistry.upsert(smbRoot, "alicepass")
        settingSwitch("thumbs_SMB", true)
        open(smbRoot.id, "/", "GRID")
        until(20_000, "permission error shown for Locked") { exists("thumb_err_Locked") }
        until(20_000, "fixture folder has no image") { exists("thumb_none_fixture") }

        // Clearing the image cache keeps settings, sources and playlists.
        onUi { player.createPlaylist("cache-test") }
        until(5_000, "playlist") { player.uiState.value.allPlaylists.any { it.name == "cache-test" } }
        val density = browser.uiState.value.gridDensity
        val sources = SourceRegistry.savedSources().map { it.id }
        toSettings()
        compose.onNodeWithTagScrolled("clear_image_cache"); click("clear_image_cache")
        assertEquals(density, browser.uiState.value.gridDensity)
        assertEquals(sources, SourceRegistry.savedSources().map { it.id })
        assertTrue(player.uiState.value.allPlaylists.any { it.name == "cache-test" })
        onUi { player.uiState.value.allPlaylists.firstOrNull { it.name == "cache-test" }?.let { player.deletePlaylist(it.id) } }
        open(local, fx, "GRID")
        until(15_000, "thumbnails reload after clearing") { exists("thumb_Album-A") }
    }

    @Test fun a18_threeHundredFoldersFastScrollNoMixups() {
        toSettings(); compose.onNodeWithTagScrolled("grid_4"); click("grid_4")
        open(local, "$fx/Many", "GRID")
        val grid = compose.onNodeWithTag("file_grid")
        val rt = Runtime.getRuntime()
        for (i in listOf(40, 120, 299, 10, 200, 0, 150)) {
            grid.performScrollToIndex(i)
            Thread.sleep(150)
        }
        // Let the visible items load, then check each visible thumbnail shows its own folder's colour.
        Thread.sleep(3_000)
        compose.waitForIdle()
        var checked = 0
        // Only rows fully inside the grid: a row cut off at the bottom edge (now above the mini player) shows a sliver
        // of the tile plus whatever is drawn below it. The bounds are clipped to the grid, so a cut-off (square) tile
        // is one that is not square.
        for (i in 150..175) {
            val name = "Folder %03d".format(i)
            if (!exists("thumb_$name")) continue
            val b = node("thumb_$name").fetchSemanticsNode().boundsInRoot
            if (b.height < b.width * 0.95f) continue
            val expected = Triple((i * 53) % 256, (i * 97) % 256, (i * 151) % 256)
            val got = thumbColour(name)
            assertTrue("$name shows its own image: got $got expected $expected", close(got, expected))
            checked++
        }
        Fx.log("checked $checked visible thumbnails; heap used ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MiB of ${rt.maxMemory() / 1_048_576}")
        assertTrue("several thumbnails visible and checked", checked >= 8)
        assertTrue("heap stays bounded", rt.totalMemory() - rt.freeMemory() < rt.maxMemory() * 0.8)

        // Rapid skipping through 30 tracks from different folders: the final cover belongs to the final track.
        val files = (100 until 130).map { i -> com.wing.folderplayer.data.source.MusicFile("track %03d.flac".format(i), "$fx/Many/Folder %03d/track %03d.flac".format(i, i), false, 0, 0, local) }
        onUi { player.playCustomList(files, 0) }
        until(10_000, "playing") { player.uiState.value.isPlaying }
        repeat(29) { onUi { player.next() }; Thread.sleep(60) }
        until(15_000, "last track") { player.uiState.value.currentMediaId?.contains("Folder%20129") == true }
        until(10_000, "cover of the last track") { player.uiState.value.coverUri?.toString()?.contains("Folder%20129/cover.png") == true ||
            player.uiState.value.coverUri?.toString()?.contains("Folder 129/cover.png") == true }
        onUi { player.playPause() }
    }
}
