package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bookmarks on the real UI: added from a folder's actions sheet and from inside the folder, listed on the source list,
 * opened from there, removed, and still there after a restart. Methods run in order, one process each (run-each).
 */
@RunWith(AndroidJUnit4::class)
class BookmarksUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = "/Music/fixture"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun open(path: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, path)) }
        until(15_000, "folder $path") { browser.uiState.value.currentFolder?.path == path && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    private fun bookmarkPaths() = browser.uiState.value.bookmarks.map { it.path }

    /** On screen. A row taken out of a lazy list can stay in the test tree, unplaced, so "gone" is "not displayed". */
    private fun shown(tag: String) = runCatching { node(tag).assertIsDisplayed(); true }.getOrDefault(false)

    @Test fun a40_addFromSheetAndFromInsideTheFolderOpenAndRemove() {
        onUi { browser.uiState.value.bookmarks.forEach { browser.removeBookmark(it) } }
        until(5_000, "no bookmarks") { browser.uiState.value.bookmarks.isEmpty() }
        val favouritesBefore = browser.uiState.value.favorites.size

        // From the actions sheet of a folder (long press); a file has no such row.
        open(fx)
        longClick("item_Album-B")
        assertTrue(exists("action_bookmark"))
        click("action_bookmark")
        until(5_000, "first bookmark") { bookmarkPaths() == listOf("$fx/Album-B") }

        open("$fx/Album-A")
        longClick("item_02 track.mp3")
        assertFalse("a file cannot be bookmarked", exists("action_bookmark"))
        onUi { browser.closePlaylistDialog() }
        until(5_000, "sheet closed") { !exists("action_favorite") }

        // From inside the folder: the overflow menu of the browser.
        click("btn_overflow")
        click("menu_bookmark")
        until(5_000, "second bookmark") { bookmarkPaths() == listOf("$fx/Album-B", "$fx/Album-A") }
        assertEquals("bookmarks are not favourites", favouritesBefore, browser.uiState.value.favorites.size)

        // The menu now offers removal for this folder, and the sheet of the other folder too.
        click("btn_overflow")
        assertTrue(textExists(str(R.string.browser_remove_bookmark)))
        pressBack()

        // Source list: a section above the sources; a bookmark opens its folder.
        onUi { browser.exitSource() }
        until(5_000, "source list") { shown("bookmarks_header") }
        assertTrue(shown("bookmark_Album-B") && shown("bookmark_Album-A") && shown("entry_favorites"))
        click("bookmark_Album-B")
        until(15_000, "bookmark opens its folder") { browser.uiState.value.currentFolder?.path == "$fx/Album-B" && !browser.uiState.value.isLoading }

        // Remove one with a long press on the source list; the other stays.
        onUi { browser.exitSource() }
        until(5_000, "source list again") { shown("bookmark_Album-A") }
        longClick("bookmark_Album-A")
        click("bookmark_remove")
        until(5_000, "removed") { bookmarkPaths() == listOf("$fx/Album-B") }
        until(5_000, "row of the removed bookmark gone") { !shown("bookmark_Album-A") }
        assertTrue(shown("bookmark_Album-B"))
    }

    @Test fun b40_bookmarkIsStillThereAfterRestartAndTheLastOneRemovesTheSection() {
        until(5_000, "bookmarks loaded") { browser.uiState.value.bookmarks.isNotEmpty() }
        assertEquals(listOf("$fx/Album-B"), bookmarkPaths())
        toBrowser()
        onUi { browser.exitSource() }
        until(5_000, "source list") { shown("bookmark_Album-B") }
        longClick("bookmark_Album-B")
        click("bookmark_remove")
        until(5_000, "no bookmark left") { browser.uiState.value.bookmarks.isEmpty() }
        until(5_000, "row gone") { !shown("bookmark_Album-B") }
        until(5_000, "section header gone") { !shown("bookmarks_header") }
        assertTrue(exists("entry_favorites") && exists("source_list"))
    }
}
