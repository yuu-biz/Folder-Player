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
import androidx.compose.ui.test.assertIsSelected
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
 * long-press sheet shows the song in its folder and Back returns to the list. The list can be narrowed to the songs of
 * one folder (and below it), from the list itself, a folder's menu or a bookmark's menu; that is kept.
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

    private val copyDir = "${Fx.FX}/SongsFolderCopy"

    @Before fun scanAlbum() {
        com.wing.folderplayer.data.prefs.SourcePreferences(Fx.ctx).saveSongsFolder(null)
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
        // The default order and every folder for the next run.
        runCatching { com.wing.folderplayer.data.prefs.SourcePreferences(Fx.ctx).saveSongsSort(SongList.TITLE, true) }
        runCatching { onUi { browser.setSongsFolder(null); browser.bookmarksRepository.remove(SourceRef(local, copyDir)) } }
        runCatching { Fx.shell("rm -r /storage/emulated/0$copyDir") }
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

        // The filter matches the tags: words of the artist and the titles, in any order (Album C has the same artist).
        click("btn_filter")
        compose.onNodeWithTag("filter_field").performTextReplacement("tagged fixture")
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

    /**
     * The whole fixture scanned: hundreds of songs over many folders. Playing from the list starts without listing every
     * folder first; the queue is the whole list and the next song plays.
     */
    @Test fun s03_aListOfManyFoldersPlaysAtOnceAndGoesOn() {
        val repo = DurationRepository.get(Fx.ctx)
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, Fx.FX))))
        until(180_000, "scan of the fixture") { !repo.scan.value.running && repo.scan.value.finished > before }
        repo.dismissScanResult()
        openSongs()
        sortBy(SongList.TITLE, true)
        val list = bs.visibleFiles
        val folders = list.mapTo(HashSet()) { it.path.substringBeforeLast('/') }.size
        assertTrue("many folders ($folders) and songs (${list.size})", folders > 12 && list.size > 100)
        // A row whose name no other row has (many folders hold a "track.flac").
        val names = list.groupingBy { it.name }.eachCount()
        // It and the song after it plain FLAC or MP3 (the fixture also has broken and unsupported files).
        fun plain(f: com.wing.folderplayer.data.source.MusicFile) = (f.name.endsWith(".flac") || f.name.endsWith(".mp3")) && "corrupt" !in f.path.lowercase()
        val at = (0 until list.size - 1).first { names[list[it].name] == 1 && plain(list[it]) && plain(list[it + 1]) }
        val song = list[at]
        val started = System.currentTimeMillis()
        click("item_${song.name}")
        until(15_000, "playing ${song.name}") { player.uiState.value.isPlaying && SourceUris.parse(player.uiState.value.currentMediaId)?.path == song.path }
        Fx.log("songs list of ${list.size} songs in $folders folders: playing after ${System.currentTimeMillis() - started} ms")
        until(5_000, "queue") { player.uiState.value.activePlaylistItems.size == list.size }
        val first = player.uiState.value.currentMediaId
        onUi { player.next() }
        try {
            until(15_000, "the next song") { player.uiState.value.isPlaying && player.uiState.value.currentMediaId != first }
        } catch (e: AssertionError) {
            val s = player.uiState.value
            throw AssertionError("playing ${s.isPlaying} buffering ${s.isBuffering} title ${s.currentTitle} id ${s.currentMediaId} error ${s.playbackError} expected ${list[at + 1].path}", e)
        }
        val next = SourceUris.parse(player.uiState.value.currentMediaId)?.path
        assertEquals("next in the list", list[at + 1].path, next)
    }

    private fun scan(path: String) {
        val repo = DurationRepository.get(Fx.ctx)
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, path))))
        until(30_000, "scan of $path") { !repo.scan.value.running && repo.scan.value.finished > before }
        repo.dismissScanResult()
    }

    private fun allBelow(path: String) = bs.visibleFiles.isNotEmpty() && bs.visibleFiles.all { it.sourceId == local && it.path.startsWith("$path/") }

    /** A folder's menu narrows the list to its songs; that stays (also on the source list) until "All folders" is picked. */
    @Test fun s04_narrowedToAFolderFromItsMenuAndKept() {
        val parent = "${Fx.FX}/Parent"
        scan(parent) // a song outside Album-A
        openSongs()
        until(5_000, "every folder") { bs.songsFolder == null && bs.visibleFiles.any { it.path.startsWith("$parent/") } }

        onUi { browser.loadFolder(SourceRef(local, album)) }
        until(10_000, "Album-A") { bs.currentFolder?.path == album && !bs.isLoading }
        click("btn_overflow")
        click("menu_songs_in_folder")
        until(10_000, "the songs of Album-A only") { bs.showingSongs && !bs.isLoading && allBelow(album) && albumTitles().size == 2 }
        assertTrue("the picker names the folder: ${text("songs_folder_label")}", text("songs_folder_label").contains("Album-A"))

        // Kept: the source list counts the songs of the folder and names it; the list opens narrowed.
        onUi { browser.exitSource() }
        until(5_000, "source list") { exists("entry_songs") && bs.songCount == 2 }
        assertTrue("entry names the folder: ${text("entry_songs_count")}", text("entry_songs_count").let { it.startsWith(Fx.ctx.resources.getQuantityString(R.plurals.browser_songs_count, 2, 2)) && it.endsWith("Album-A") })
        click("entry_songs")
        until(10_000, "still narrowed") { bs.showingSongs && !bs.isLoading && allBelow(album) }

        // Every folder again, from the picker.
        click("songs_folder")
        click("songs_folder_all")
        until(10_000, "every folder") { bs.songsFolder == null && !bs.isLoading && bs.visibleFiles.any { it.path.startsWith("$parent/") } && albumTitles().size == 2 }
        assertTrue(text("songs_folder_label") == Fx.ctx.getString(R.string.songs_folder_all))
    }

    /**
     * A bookmark's menu opens the list of its folder; a folder none of whose songs were read says so and reads them from
     * there; the picker offers the bookmark, checked.
     */
    @Test fun s05_aBookmarkedFolderNotReadYetIsReadFromTheList() {
        // A fresh copy: a file the index does not know (new time).
        Fx.shell("mkdir -p /storage/emulated/0$copyDir")
        Fx.shell("cp /storage/emulated/0${Fx.FX}/Parent/CD1/track.flac /storage/emulated/0$copyDir/copied.flac")
        until(5_000, "copied") { Fx.localFile("$copyDir/copied.flac").length() > 0 }
        toBrowser()
        onUi { browser.bookmarksRepository.add(SourceRef(local, copyDir), "SongsFolderCopy"); browser.exitSource() }
        until(5_000, "bookmark") { exists("bookmark_SongsFolderCopy") }
        longClick("bookmark_SongsFolderCopy")
        click("bookmark_songs")
        until(10_000, "nothing read there") { bs.showingSongs && !bs.isLoading && bs.songsFolder?.path == copyDir && exists("songs_empty") }
        assertTrue("the picker names the bookmark", text("songs_folder_label") == "SongsFolderCopy")
        click("songs_scan_folder")
        until(30_000, "read from the list") { bs.visibleFiles.map { it.path } == listOf("$copyDir/copied.flac") && !DurationRepository.get(Fx.ctx).scan.value.running }

        click("songs_folder")
        until(5_000, "menu") { exists("songs_folder_bookmark_SongsFolderCopy") }
        compose.onNodeWithTag("songs_folder_bookmark_SongsFolderCopy").assertIsSelected()
        pressBack()
    }
}
