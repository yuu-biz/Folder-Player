package com.wing.folderplayer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.browser.ListDensity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Lower list rows (Settings › Library) and the music-only toggle of a folder, on a folder made for them. */
@RunWith(AndroidJUnit4::class)
class ListDensityUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val dir = "/Music/fp-density"
    private val sd = "/sdcard$dir"
    private val tracks = (1..40).map { "track%02d.flac".format(it) }

    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun shell(cmd: String) = Fx.shell(cmd).also { Fx.log("shell $cmd -> ${it.trim()}") }

    private fun resetOptions() {
        SourcePreferences(Fx.ctx).saveListDensity(ListDensity.STANDARD.name)
        runCatching { onUi { browser.setListDensity(ListDensity.STANDARD); browser.setMusicOnly(false) } }
    }

    @Before fun makeFolder() {
        resetOptions()
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
        shell("rm -rf $sd")
        shell("mkdir -p $sd/Sub $sd/Pictures")
        for (t in tracks) shell("cp /sdcard/Music/fixture/Album-B/track.flac $sd/$t")
        shell("cp /sdcard/Music/fixture/Album-B/track.flac $sd/Sub/inner.flac")
        shell("cp /sdcard/Music/fixture/Cue/image.cue $sd/image.cue")
        shell("cp /sdcard/Music/fixture/Cue/cover.jpg $sd/cover.jpg")
        shell("cp /sdcard/Music/fixture/Album-A/Info.nfo $sd/Info.nfo")
        shell("touch $sd/notes.txt")
        shell("cp /sdcard/Music/fixture/Cue/cover.jpg $sd/Pictures/cover.jpg")
        shell("touch $sd/Pictures/readme.txt")
        shell("content call --uri content://media/external/file --method scan_volume --arg external_primary")
        until(20_000, "test folder visible to the app") { Fx.localFile(dir).list()?.size == tracks.size + 6 }
    }

    @After fun removeFolder() {
        resetOptions()
        shell("rm -rf $sd")
    }

    private fun open(path: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, path)) }
        until(15_000, "folder $path") { browser.uiState.value.currentFolder?.path == path && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list view") { exists("file_list") }
    }

    private fun chooseDensity(d: ListDensity) {
        toSettings()
        click("listdensity_${d.name}")
        assertEquals(d, browser.uiState.value.listDensity)
        toBrowser()
        until(5_000, "list") { exists("file_list") }
        compose.onNodeWithTag("file_list").performScrollToIndex(0)
        compose.waitForIdle()
    }

    private val rowMatcher = SemanticsMatcher("a file list row") { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("item_") == true }

    /** Rows of the file list inside its visible area (a row cut at the bottom edge may count, in every density alike). */
    private fun rowsOnScreen(): Int {
        val list = node("file_list").fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(rowMatcher, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            .count { it.height > 0f && it.top >= list.top && it.bottom <= list.bottom }
    }

    @Test fun denseRowsFitMoreThanStandardAndKeepTheirContent() {
        open(dir)
        val standard = rowsOnScreen()
        chooseDensity(ListDensity.DENSE)
        val dense = rowsOnScreen()
        Fx.log("rows on screen: STANDARD=$standard DENSE=$dense")
        assertTrue("DENSE ($dense rows) fits more than STANDARD ($standard)", dense > standard)

        chooseDensity(ListDensity.COMPACT)
        val compact = rowsOnScreen()
        Fx.log("rows on screen: COMPACT=$compact")
        assertTrue("COMPACT ($compact rows) fits more than STANDARD ($standard)", compact > standard)
        reveal("item_track01.flac")
        until(10_000, "format and length of a song row") { exists("info_track01.flac") && exists("duration_track01.flac") }
        assertTrue("format shown", text("info_track01.flac").startsWith("FLAC"))
        assertTrue("length shown", text("duration_track01.flac").contains(":"))
    }

    @Test fun musicOnlyHidesOtherFilesAndAFolderWithoutMusicOffersThemBack() {
        open(dir)
        until(5_000, "image row") { exists("item_cover.jpg") }
        click("btn_music_only")
        assertTrue(browser.uiState.value.musicOnly)
        val shown = browser.uiState.value.visibleFiles.map { it.name }.toSet()
        assertTrue("folders, songs and the CUE sheet stay: $shown", shown.containsAll(listOf("Sub", "Pictures", "image.cue") + tracks))
        assertTrue("image, nfo and text go: $shown", shown.none { it in listOf("cover.jpg", "Info.nfo", "notes.txt") })
        compose.onNodeWithTag("file_list").performScrollToIndex(0)
        until(5_000, "image row gone") { !exists("item_cover.jpg") }
        assertTrue(exists("item_Sub"))
        reveal("item_image.cue")
        assertTrue(exists("item_image.cue"))

        // Kept on the way into a folder that has only a picture and a text file.
        open("$dir/Pictures")
        assertTrue(browser.uiState.value.musicOnly)
        until(5_000, "music-only empty state") { exists("music_only_empty") }
        assertFalse(exists("filter_no_match"))
        click("music_only_show_all")
        until(5_000, "every file back") { exists("item_cover.jpg") && exists("item_readme.txt") }
        assertFalse(browser.uiState.value.musicOnly)
    }
}
