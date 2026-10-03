package com.wing.folderplayer

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.favorites.FavoriteItem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.utils.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Search, favorites (incl. restart), language and display settings restored after restart, on the real
 * UI. Methods run in order, one process each (instrument.sh run-each); the `b_` methods check what `a_` stored.
 */
@RunWith(AndroidJUnit4::class)
class LibraryUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = "/Music/fixture"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun open(sourceId: String, path: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(sourceId, path)) }
        until(15_000, "folder $path") { browser.uiState.value.currentFolder?.path == path && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    private fun scrolled(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo(); compose.waitForIdle() }

    // ---------------- search ----------------

    @Test fun a19_searchSubfoldersPlayResultQueryChangeAndUnreadableSkip() {
        open(local, fx)
        click("btn_search")
        compose.onNodeWithTag("search_field").performTextInput("track 01")
        until(30_000, "search done") { browser.uiState.value.search.let { !it.running && it.results.isNotEmpty() } }
        val s1 = browser.uiState.value.search
        Fx.log("search 'track 01': ${s1.results.size} results, ${s1.foldersScanned} folders, skipped ${s1.foldersSkipped}")
        val names = s1.results.map { it.name }
        assertTrue(names.containsAll((10..19).map { "track %03d.flac".format(it) }))
        assertTrue("results stay under the root", s1.results.all { it.path.startsWith("$fx/") && it.sourceId == local })
        assertEquals("no duplicates", s1.results.size, s1.results.map { it.sourceId + it.path }.toSet().size)
        until(5_000, "result rows") { exists("item_track 015.flac") }

        // Changing the query replaces the previous search (no stale results).
        compose.onNodeWithTag("search_field").performTextReplacement("Cue")
        compose.onNodeWithTag("search_field").performTextReplacement("曲 #1")
        until(30_000, "second search") { browser.uiState.value.search.let { !it.running && it.query == "曲 #1" } }
        val s2 = browser.uiState.value.search.results.map { it.name }
        Fx.log("search '曲 #1': $s2")
        assertTrue(s2.isNotEmpty() && s2.all { it.contains("曲 #1") })

        // A result can be played from the list.
        click("item_01 曲 #1+%.flac")
        until(15_000, "playing the result") { player.uiState.value.isPlaying && player.uiState.value.currentMediaId?.contains("Album-A") == true }
        onUi { player.playPause() }

        // Unreadable child folders are skipped and counted (SMB share root with a 0700 folder).
        Fx.require("smb_host")
        val smbRoot = SourceConfig(name = "smb-search", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", username = "alice")
        SourceRegistry.upsert(smbRoot, "alicepass")
        onUi { browser.closeSearch() }
        open(smbRoot.id, "/")
        click("btn_search")
        compose.onNodeWithTag("search_field").performTextInput("cover")
        until(60_000, "SMB search") { browser.uiState.value.search.let { !it.running && it.query == "cover" && it.foldersScanned > 0 } }
        val s3 = browser.uiState.value.search
        Fx.log("SMB search: ${s3.results.size} results, scanned ${s3.foldersScanned}, skipped ${s3.foldersSkipped}")
        assertTrue("Locked folder skipped", s3.foldersSkipped >= 1)
        assertTrue(s3.results.none { it.path.startsWith("/Locked") })
        assertTrue(text("search_status").isNotBlank())
        SourceRegistry.remove(smbRoot.id)
    }

    // ---------------- favorites ----------------

    private val favExpect get() = File(Fx.ctx.filesDir, "ui-favorites-expected.txt")

    @Test fun a20_addFavoritesFromUiAndPlayFromVirtualList() {
        // Start clean (only favourites created here).
        onUi { browser.uiState.value.favorites.forEach { f -> f.ref?.let { r -> browser.toggleFavorite(com.wing.folderplayer.data.source.MusicFile(r.name, r.path, f.type == FavoriteItem.TYPE_FOLDER, 0, 0, r.sourceId)) } } }
        until(5_000, "no favourites") { browser.uiState.value.favorites.isEmpty() }
        open(local, fx)
        longClick("item_Album-B")
        click("action_favorite")
        open(local, "$fx/Album-A")
        longClick("item_02 track.mp3")
        click("action_favorite")
        until(5_000, "2 favourites") { browser.uiState.value.favorites.size == 2 }
        val playlistsBefore = player.uiState.value.allPlaylists.map { it.id to it.items.size }

        onUi { browser.exitSource() }
        until(5_000, "source list") { exists("entry_favorites") }
        click("entry_favorites")
        until(5_000, "favourites list") { exists("item_Album-B") && exists("item_02 track.mp3") }
        click("item_02 track.mp3")
        until(15_000, "favourite track plays") { player.uiState.value.isPlaying && player.uiState.value.currentMediaId?.endsWith("Album-A/02%20track.mp3") == true }
        onUi { player.playPause() }
        assertEquals("favourites are independent of playlists", playlistsBefore.filter { it.first != "default" },
            player.uiState.value.allPlaylists.map { it.id to it.items.size }.filter { it.first != "default" })
        favExpect.writeText(browser.uiState.value.favorites.joinToString("\n") { "${it.sourceId}|${it.path}|${it.type}" })
    }

    @Test fun b20_favoritesRestoredAfterRestartAndRemovable() {
        val expected = favExpect.takeIf { it.exists() }?.readLines() ?: error("run a20_addFavoritesFromUiAndPlayFromVirtualList first")
        until(5_000, "favourites loaded") { browser.uiState.value.favorites.isNotEmpty() }
        assertEquals(expected.toSet(), browser.uiState.value.favorites.map { "${it.sourceId}|${it.path}|${it.type}" }.toSet())
        toBrowser()
        click("entry_favorites")
        until(5_000, "favourites list") { exists("item_Album-B") }
        longClick("item_Album-B")
        click("action_favorite")
        until(5_000, "removed") { browser.uiState.value.favorites.size == expected.size - 1 }
        click("item_02 track.mp3")
        until(15_000, "still playable") { player.uiState.value.isPlaying }
        onUi { player.playPause() }
    }

    // ---------------- language and settings ----------------

    @Test fun a31_languageAndDisplaySettings() {
        toSettings()
        for (tag in listOf("grid_5", "bg_BLACK", "cover_LARGE", "notch_BLACK_BAR", "title_TAGS", "sort_DATE", "defview_GRID")) {
            scrolled(tag); click(tag)
        }
        scrolled("lang_fr"); click("lang_fr")
        // The activity is recreated in French.
        until(15_000, "French UI") { AppLocale.get(Fx.ctx) == "fr" && textExists("Réglages") }
        Fx.log("settings title now: ${compose.activity.getString(R.string.settings_title)}")
    }

    @Test fun b31_settingsRestoredAfterRestart() {
        assertEquals("fr", AppLocale.get(Fx.ctx))
        toSettings()
        until(10_000, "French UI after restart") { textExists("Réglages") }
        for (tag in listOf("grid_5", "bg_BLACK", "cover_LARGE", "notch_BLACK_BAR", "title_TAGS", "sort_DATE", "defview_GRID")) {
            scrolled(tag); node(tag).assertIsSelected()
        }
        // Every language can be selected; switch through them and back to the system language.
        for (tag in listOf("zh-CN", "zh-TW", "it", "ja", "en", "")) {
            toSettings()
            scrolled("lang_$tag"); click("lang_$tag")
            until(15_000, "language $tag") { AppLocale.get(Fx.ctx) == tag }
            Thread.sleep(1_500)
            Fx.log("language '$tag': settings title = ${compose.activity.getString(R.string.settings_title)}")
        }
        // Back to defaults for other suites.
        toSettings()
        for (tag in listOf("grid_3", "bg_GRADIENT", "cover_STANDARD", "notch_FULLSCREEN", "title_FILENAME", "sort_NAME", "defview_LIST")) { scrolled(tag); click(tag) }
        assertFalse(AppLocale.get(Fx.ctx).isNotEmpty())
    }
}
