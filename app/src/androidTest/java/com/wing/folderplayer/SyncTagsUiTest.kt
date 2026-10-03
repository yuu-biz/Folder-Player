package com.wing.folderplayer

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.favorites.FavoriteItem
import com.wing.folderplayer.data.favorites.FavoritesCodec
import com.wing.folderplayer.data.favorites.FavoritesData
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readText
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.data.repo.TitleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit favourites sync from the browser menu against the WebDAV fixture (`rw/` root): creation with
 * version/items, additive merge of remote entries, broken remote never overwritten (until the explicit replace), and a
 * read-only account. Also: file-name vs tag titles, tag-less fallback, audio info, embedded picture.
 */
@RunWith(AndroidJUnit4::class)
class SyncTagsUiTest : UiTestBase() {
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private val local = SourceRegistry.LOCAL_INTERNAL_ID

    /** Opens the browser's ⋮ menu on the source list and starts "Sync favorites". */
    private fun syncFromMenu() {
        toBrowser()
        onUi { browser.exitSource() }
        until(5_000, "source list") { exists("btn_overflow") }
        click("btn_overflow")
        clickText(str(R.string.browser_sync_favorites))
    }

    @Test fun a21_syncCreatesMergesProtectsBrokenAndReadOnly() {
        Fx.require("webdav_url")
        val u = Fx.arg("webdav_url")!!
        val syncPath = "/rw/fp-fav-test.json"
        SourceRegistry.savedSources().filter { it.type != SourceType.SAF }.forEach { SourceRegistry.remove(it.id) }
        val dav = SourceConfig(name = "dav-sync", type = SourceType.WEBDAV, url = u, username = "alice", syncPath = syncPath).also { SourceRegistry.upsert(it, "pa:ss/1") }
        val fs = SourceRegistry.fileSystem(dav.id)
        runCatching { fs.delete(syncPath) }

        // Local favourites: one folder.
        onUi { browser.uiState.value.favorites.forEach { f -> f.ref?.let { r -> browser.toggleFavorite(MusicFile(r.name, r.path, f.type == FavoriteItem.TYPE_FOLDER, 0, 0, r.sourceId)) } } }
        onUi { browser.toggleFavorite(MusicFile("Album-B", "/Music/fixture/Album-B", true, 0, 0, local)) }
        until(5_000, "1 local favourite") { browser.uiState.value.favorites.size == 1 }

        // 1) First sync (from the menu) creates the remote file in the documented format.
        syncFromMenu()
        until(30_000, "first sync") { browser.uiState.value.message?.contains("synced") == true }
        val remote1 = FavoritesCodec.decode(fs.readText(syncPath))
        assertEquals(1, remote1.version)
        assertEquals(listOf("/Music/fixture/Album-B"), remote1.items.map { it.path })
        assertTrue("no credentials in fav.json", !fs.readText(syncPath).contains("pa:ss/1"))
        onUi { browser.clearMessage() }

        // 2) Another device added an entry → merged locally, nothing removed.
        val other = FavoriteItem(type = FavoriteItem.TYPE_SONG, sourceId = local, path = "/Music/fixture/Album-A/02 track.mp3", name = "02 track.mp3", timestamp = System.currentTimeMillis())
        fs.write(syncPath, FavoritesCodec.encode(FavoritesData(1, remote1.items + other)).toByteArray(), overwrite = true)
        onUi { browser.syncFavorites() }
        until(30_000, "merge sync") { browser.uiState.value.message?.contains("synced") == true }
        assertEquals(setOf("/Music/fixture/Album-B", "/Music/fixture/Album-A/02 track.mp3"), browser.uiState.value.favorites.map { it.path }.toSet())
        onUi { browser.clearMessage() }

        // 3) Broken remote JSON is reported and left untouched.
        fs.write(syncPath, "{ this is not json".toByteArray(), overwrite = true)
        onUi { browser.syncFavorites() }
        until(30_000, "broken remote reported") { browser.uiState.value.message?.contains("broken") == true }
        assertEquals("{ this is not json", fs.readText(syncPath))
        assertEquals(2, browser.uiState.value.favorites.size)
        onUi { browser.clearMessage() }
        // Only the explicit "replace remote" action overwrites it (UI: long-press the source → replace).
        toBrowser(); onUi { browser.exitSource() }
        until(5_000, "source row") { exists("source_dav-sync") }
        longClick("source_dav-sync")
        clickText(str(R.string.browser_replace_remote_favorites))
        until(30_000, "replaced") { runCatching { FavoritesCodec.decode(fs.readText(syncPath)).items.size == 2 }.getOrDefault(false) }
        onUi { browser.clearMessage() }

        // 4) Read-only account with a new local entry to upload: remote not writable, local list kept.
        // (A sync with nothing new locally does not write at all, so it would not hit the read-only error.)
        onUi { browser.toggleFavorite(MusicFile("Album-A", "/Music/fixture/Album-A", true, 0, 0, local)) }
        until(5_000, "3 local favourites") { browser.uiState.value.favorites.size == 3 }
        val ro = SourceConfig(name = "dav-sync-ro", type = SourceType.WEBDAV, url = u, username = "bob", syncPath = syncPath).also { SourceRegistry.upsert(it, "bobpass") }
        SourceRegistry.remove(dav.id)
        onUi { browser.syncFavorites() }
        until(30_000, "read-only reported") { browser.uiState.value.message?.contains("read-only") == true }
        assertEquals(3, browser.uiState.value.favorites.size)
        assertEquals(2, FavoritesCodec.decode(SourceRegistry.fileSystem(ro.id).readText(syncPath)).items.size)
        SourceRegistry.remove(ro.id)
        // Clean the dedicated remote file with the writable account.
        SourceRegistry.upsert(dav, "pa:ss/1")
        runCatching { SourceRegistry.fileSystem(dav.id).delete(syncPath) }
        onUi { browser.uiState.value.favorites.forEach { f -> f.ref?.let { r -> browser.toggleFavorite(MusicFile(r.name, r.path, f.type == FavoriteItem.TYPE_FOLDER, 0, 0, r.sourceId)) } } }
    }

    @Test fun a24_titlesTagsFallbackAudioInfoAndEmbeddedArt() {
        val prefs = PlaybackPreferences(Fx.ctx)
        try {
            onUi { player.setTitleMode(TitleMode.TAGS) }
            onUi { player.playFolder(SourceRef(local, "/Music/fixture/Album-A"), "/Music/fixture/Album-A/02 track.mp3") }
            until(15_000, "tag title") { player.uiState.value.isPlaying && player.uiState.value.currentTitle == "Tagged Title Two" }
            assertEquals("Fixture Artist", player.uiState.value.currentArtist)
            assertTrue(player.uiState.value.audioInfo.startsWith("MP3"))
            toPlayer()
            until(5_000, "title shown") { textExists("Tagged Title Two") }

            onUi { player.setTitleMode(TitleMode.FILENAME) }
            onUi { player.playFolder(SourceRef(local, "/Music/fixture/Album-A"), "/Music/fixture/Album-A/02 track.mp3") }
            until(15_000, "file-name title") { player.uiState.value.isPlaying && player.uiState.value.currentTitle == "02 track" }

            // No tags at all: the file name is used in both modes.
            onUi { player.setTitleMode(TitleMode.TAGS) }
            onUi { player.playFolder(SourceRef(local, "/Music/fixture/Album-B"), null) }
            until(15_000, "tag-less fallback") { player.uiState.value.isPlaying && player.uiState.value.currentTitle == "track" }
            Fx.log("audio info Album-B: ${player.uiState.value.audioInfo}")
            assertTrue(player.uiState.value.audioInfo.startsWith("FLAC"))

            // Embedded picture is the cover when the folder has no image.
            onUi { player.playFolder(SourceRef(local, "/Music/fixture/Album-C"), null) }
            until(15_000, "embedded art") { player.uiState.value.isPlaying && player.uiState.value.coverUri is ByteArray }
        } finally {
            onUi { player.setTitleMode(TitleMode.FILENAME); if (player.uiState.value.isPlaying) player.playPause() }
            prefs.saveTitleMode("FILENAME")
        }
    }
}
