package com.wing.folderplayer

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.favorites.FavoriteItem
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The favourites list has the sort buttons of a folder plus "Added"; the choice is kept. Entries added without the file's
 * times (as by an app before 1.6.0) get them from the source when the list is sorted by date.
 */
@RunWith(AndroidJUnit4::class)
class FavoritesSortUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private val player: com.wing.folderplayer.ui.player.PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[com.wing.folderplayer.ui.player.PlayerViewModel::class.java] }
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun names() = bs.files.map { it.name }

    @Test fun s01_sortedByAddedNameAndDate() {
        // Added in an order that is neither by name nor by date. The pushed fixture keeps the build's file times: track.flac,
        // then track-with-embedded-art.mp3, then long.flac.
        val songs = listOf("Long/long.flac", "Album-B/track.flac", "Album-C/track-with-embedded-art.mp3")
        onUi {
            browser.favoritesRepository.items.value.forEach { browser.favoritesRepository.remove(it.ref!!, it.type == "FOLDER") }
            // Added in this order, without the file's times (lastModified 0): the times are read when sorting by date.
            for (s in songs) browser.favoritesRepository.add(MusicFile(s.substringAfterLast('/'), "${Fx.FX}/$s", false, 0, 0, local))
        }
        toBrowser()
        onUi { browser.showFavorites(com.wing.folderplayer.data.favorites.FavoritesKind.SONGS) }
        until(10_000, "favourites with the sort buttons") { bs.showingFavorites && exists("sortbtn_ADDED") && bs.files.size == 3 }
        if (bs.sortField != "ADDED" || !bs.sortAscending) {
            click("sortbtn_ADDED")
            if (!bs.sortAscending) click("sortbtn_ADDED")
        }
        until(5_000, "added order") { names() == listOf("long.flac", "track.flac", "track-with-embedded-art.mp3") }

        click("sortbtn_NAME")
        until(5_000, "by name") { names() == listOf("long.flac", "track-with-embedded-art.mp3", "track.flac") }

        click("sortbtn_DATE")
        until(15_000, "by the files' modification times, read from the source") {
            names() == listOf("track.flac", "track-with-embedded-art.mp3", "long.flac")
        }
        assertTrue("times stored", browser.favoritesRepository.items.value.all { it.modified > 0 })

        click("sortbtn_ADDED")
        click("sortbtn_ADDED")
        until(5_000, "newest added first") { names() == listOf("track-with-embedded-art.mp3", "track.flac", "long.flac") }
        val prefs = com.wing.folderplayer.data.prefs.SourcePreferences(Fx.ctx).getFavoritesSort(com.wing.folderplayer.data.favorites.FavoritesKind.SONGS)
        assertEquals("kept", "ADDED" to false, prefs.field to prefs.ascending)
    }

    /** The heart of the full player puts the track in the favourite songs (with its size and times) and takes it out. */
    @Test fun s02_heartOfThePlayerAddsAndRemovesTheSong() {
        val track = SourceRef(local, "${Fx.FX}/Album-B/track.flac")
        onUi { browser.favoritesRepository.items.value.forEach { browser.favoritesRepository.remove(it.ref!!, it.type == "FOLDER") } }
        toBrowser()
        onUi { player.playCustomList(listOf(MusicFile("track.flac", track.path, false, 0, 0, local)), 0) }
        until(15_000, "playing") { player.uiState.value.currentMediaId?.contains("Album-B") == true }
        toPlayer()
        until(5_000, "heart") { exists("btn_favorite_song") && !player.currentIsFavorite.value }
        click("btn_favorite_song")
        until(5_000, "a favourite song") { player.currentIsFavorite.value }
        val item = browser.favoritesRepository.items.value.single()
        assertEquals(FavoriteItem.TYPE_SONG, item.type)
        assertEquals(track.path, item.path)
        assertTrue("size and time read", item.size > 0 && item.modified > 0)

        click("btn_collapse_player")
        onUi { browser.exitSource() }
        click("entry_favorite_songs")
        until(5_000, "in the songs list") { exists("item_track.flac") }
        onUi { player.pauseLocal() }
        toPlayer()
        click("btn_favorite_song")
        until(5_000, "taken out") { !player.currentIsFavorite.value && browser.favoritesRepository.items.value.isEmpty() }
        click("btn_collapse_player")
        until(5_000, "the songs list follows") { !exists("item_track.flac") }
    }
}
