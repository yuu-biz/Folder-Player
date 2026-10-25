package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.metadata.DurationRepository
import com.wing.folderplayer.data.metadata.SongList
import com.wing.folderplayer.data.source.SORT_LENGTH
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The songs list: every song whose length was read (here: a scan of Album-A), with its title, artist and album from the
 * tags, sorted by title, artist, album or length, filtered by any of them; a tap plays the list from that song, the
 * long-press sheet shows the song in its folder and Back returns to the list.
 */
@RunWith(AndroidJUnit4::class)
class SongsUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val album = "${Fx.FX}/Album-A"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    /** The Album-A songs of the list on screen, by title, in their order (other suites may have scanned more). */
    private fun albumTitles() = bs.visibleFiles.filter { it.path.startsWith("$album/") }.map { SongList.title(it) }

    @Before fun scanAlbum() {
        val repo = DurationRepository.get(Fx.ctx)
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, album))))
        until(30_000, "scan") { !repo.scan.value.running && repo.scan.value.finished > before }
        repo.dismissScanResult()
    }

    @After fun stop() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
            }
        }
        // The default order for the next run.
        runCatching { com.wing.folderplayer.data.prefs.SourcePreferences(Fx.ctx).saveSongsSort(SongList.TITLE, true) }
    }

    private fun openSongs() {
        toBrowser()
        onUi { browser.exitSource() }
        until(5_000, "source list") { exists("entry_songs") }
        assertTrue("count shown", bs.songCount >= 2)
        click("entry_songs")
        until(10_000, "songs list") { bs.showingSongs && !bs.isLoading && albumTitles().size == 2 }
    }

    private fun sortBy(field: String, ascending: Boolean) {
        if (bs.sortField != field) click("sortbtn_$field")
        if (bs.sortAscending != ascending) click("sortbtn_$field")
        until(5_000, "sorted by $field") { bs.sortField == field && bs.sortAscending == ascending }
    }

    @Test fun s01_titlesFromTheTagsSortedFilteredAndPlayedAsAList() {
        openSongs()
        sortBy(SongList.TITLE, true)
        until(5_000, "by title") { albumTitles() == listOf("Tagged Title One", "Tagged Title Two") }
        sortBy(SORT_LENGTH, true)
        until(5_000, "shortest first") { albumTitles() == listOf("Tagged Title Two", "Tagged Title One") }
        sortBy(SORT_LENGTH, false)
        until(5_000, "longest first") { albumTitles() == listOf("Tagged Title One", "Tagged Title Two") }
        sortBy(SongList.ALBUM, true)
        until(5_000, "album in track order") { albumTitles() == listOf("Tagged Title One", "Tagged Title Two") }

        // The filter matches the tags: artist and album words, in any order.
        click("btn_filter")
        compose.onNodeWithTag("filter_field").performTextReplacement("album a fixture")
        compose.waitForIdle()
        until(5_000, "filtered") { bs.visibleFiles.isNotEmpty() && bs.visibleFiles.all { it.tags?.album == "Album A" } }
        assertTrue("title shown, not the file name", textExists("Tagged Title One"))
        assertTrue("artist and album", text("info_02 track.mp3").let { it.contains("Fixture Artist") && it.contains("Album A") })
        val shown = bs.visibleFiles

        // A tap plays the list on screen from that song.
        val second = shown[1]
        click("item_${second.name}")
        until(15_000, "playing") { player.uiState.value.isPlaying && SourceUris.parse(player.uiState.value.currentMediaId)?.path == second.path }
        assertEquals("the list is the queue", shown.map { it.path }, player.uiState.value.activePlaylistItems.map { it.path })
    }

    @Test fun s02_showInFolderAndBackToTheList() {
        openSongs()
        // The row composed: the list narrowed to the song.
        click("btn_filter")
        compose.onNodeWithTag("filter_field").performTextReplacement("tagged title two")
        compose.waitForIdle()
        until(5_000, "filtered") { bs.visibleFiles.any { it.name == "02 track.mp3" } }
        longClick("item_02 track.mp3")
        click("action_show_in_folder")
        until(10_000, "the folder") { bs.currentFolder?.path == album && !bs.isLoading && !bs.showingSongs }
        pressBack()
        until(10_000, "the songs list again, with its filter") { bs.showingSongs && !bs.isLoading && bs.filter == "tagged title two" && albumTitles() == listOf("Tagged Title Two") }
        pressBack()
        until(5_000, "source list") { bs.isRoot }
    }
}
