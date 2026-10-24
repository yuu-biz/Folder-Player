package com.wing.folderplayer

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.SORT_LENGTH
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sorting by length: the songs by their length (read for the order when not known yet), the folders first by name; an
 * explicit scan of a folder and the folders below it, with its progress, result and Cancel / close.
 */
@RunWith(AndroidJUnit4::class)
class LengthSortUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun songs() = bs.files.filter { !it.isDirectory && MediaTypes.isAudio(it.name) }.map { it.name }

    private fun open(path: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, path)) }
        until(10_000, path) { bs.currentFolder?.path == path && !bs.isLoading }
    }

    /** Back to the name order (the order is kept per folder and other suites expect it). */
    private fun byName() {
        if (bs.sortField != "NAME") click("sortbtn_NAME")
        if (!bs.sortAscending) click("sortbtn_NAME")
        until(5_000, "by name") { bs.sortField == "NAME" && bs.sortAscending }
    }

    @Test fun l01_songsByLengthBothWays() {
        open("$fx/Album-A") // 01 … .flac 60 s, 02 track.mp3 30 s
        try {
            click("sortbtn_$SORT_LENGTH")
            until(15_000, "shortest first (${songs()})") { bs.sortField == SORT_LENGTH && bs.sortAscending && songs() == listOf("02 track.mp3", "01 曲 #1+%.flac") }
            until(5_000, "no unknown lengths left") { !exists("length_fill_status") }
            click("sortbtn_$SORT_LENGTH")
            until(5_000, "longest first") { !bs.sortAscending && songs() == listOf("01 曲 #1+%.flac", "02 track.mp3") }
            // Other files (no length) come after the songs.
            val names = bs.files.map { it.name }
            assertTrue("songs before pictures ($names)", names.indexOf("02 track.mp3") < names.indexOf("cover.jpg"))
        } finally {
            byName()
        }
        // The fixture folder: its folders stay first, by name, when sorted by length.
        open(fx)
        try {
            click("sortbtn_$SORT_LENGTH")
            until(10_000, "sorted") { bs.sortField == SORT_LENGTH }
            val folders = bs.files.filter { it.isDirectory }.map { it.name }
            assertTrue("folders by name ($folders)", folders == folders.sortedBy { it.lowercase() })
            assertTrue("folders first", bs.files.takeWhile { it.isDirectory }.size == folders.size)
        } finally {
            byName()
        }
    }

    @Test fun l02_scanOfAFolderAndBelowShowsProgressAndResult() {
        open(fx)
        click("btn_overflow")
        click("menu_scan_lengths")
        until(5_000, "scan shown") { exists("length_scan_status") }
        until(120_000, "scan done") { !browser.lengthScan.value.running && browser.lengthScan.value.showResult }
        val p = browser.lengthScan.value
        assertTrue("folders and songs counted ($p)", p.folders > 5 && p.songs > 10)
        assertTrue("result text", exists("length_scan_dismiss"))
        click("length_scan_dismiss")
        until(5_000, "result closed") { !exists("length_scan_status") }

        // Everything below is known now: a folder sorted by length reads nothing more.
        open("$fx/Album-B")
        try {
            click("sortbtn_$SORT_LENGTH")
            until(5_000, "sorted") { bs.sortField == SORT_LENGTH && bs.files.any { it.durationMs != null } }
            assertTrue("nothing to read", bs.lengthFill == null)
        } finally {
            byName()
        }
    }
}
