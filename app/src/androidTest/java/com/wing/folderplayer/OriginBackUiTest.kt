package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A folder opened from the favourites list or from a bookmark: Back returns to where it was opened from, however deep the
 * user went, and the arrow-up button in the top bar goes up one folder (Back still leads to the same place afterwards).
 */
@RunWith(AndroidJUnit4::class)
class OriginBackUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun path() = bs.currentFolder?.path
    private fun shown(tag: String) = runCatching { node(tag).assertIsDisplayed(); true }.getOrDefault(false)
    private fun folder(path: String) = MusicFile(SourcePath.name(path), path, true, 0, 0, local)

    @Test fun a60_backFromAFavouriteFolderReturnsToTheFavouritesListHoweverDeepTheUserWent() {
        toBrowser()
        val deep = folder("/Music")
        val other = folder("$fx/Album-B")
        val had = listOf(deep, other).map { it to onUi { browser.isFavorite(it) } }
        had.filter { !it.second }.forEach { (f, _) -> onUi { browser.toggleFavorite(f) } }
        try {
            onUi { browser.exitSource() }
            until(5_000, "source list") { exists("entry_favorites") }
            click("entry_favorites")
            until(5_000, "favourites") { bs.showingFavorites && shown("item_Music") && shown("item_Album-B") }

            // Into the first favourite and two folders deeper.
            click("item_Music")
            until(10_000, "Music") { path() == "/Music" && !bs.isLoading }
            click("item_fixture")
            until(10_000, "fixture") { path() == fx && !bs.isLoading }
            click("item_Album-A")
            until(10_000, "Album-A") { path() == "$fx/Album-A" && !bs.isLoading }

            // One Back: the favourites list, not the folder above.
            pressBack()
            until(5_000, "favourites again") { bs.showingFavorites && shown("item_Album-B") }
            // The other favourite, then up one folder with the top-bar button; Back still leads to the favourites.
            click("item_Album-B")
            until(10_000, "Album-B") { path() == "$fx/Album-B" && !bs.isLoading }
            click("btn_browser_up")
            until(10_000, "one up") { path() == fx && !bs.isLoading }
            pressBack()
            until(5_000, "favourites after up") { bs.showingFavorites && shown("item_Music") }
            // From the favourites list Back goes to the source list, as before.
            pressBack()
            until(5_000, "source list") { bs.isRoot && exists("source_list") }
        } finally {
            had.filter { !it.second }.forEach { (f, _) -> onUi { browser.toggleFavorite(f) } }
        }
    }

    @Test fun a61_backFromABookmarkedFolderReturnsToTheSourceListAndUpGoesUpOneFolder() {
        toBrowser()
        val ref = SourceRef(local, "$fx/Album-A")
        onUi { browser.bookmarksRepository.add(ref, "Album-A") }
        try {
            onUi { browser.exitSource() }
            until(5_000, "bookmark listed") { shown("bookmark_Album-A") }
            click("bookmark_Album-A")
            until(10_000, "Album-A") { path() == "$fx/Album-A" && !bs.isLoading }
            click("btn_browser_up")
            until(10_000, "one up") { path() == fx && !bs.isLoading }
            click("btn_browser_up")
            until(10_000, "two up") { path() == "/Music" && !bs.isLoading }
            // Back: the source list with the bookmark, not the folder above.
            pressBack()
            until(5_000, "source list") { bs.isRoot && shown("bookmark_Album-A") }
            // A source opened from the list goes up level by level as before.
            click("source_Internal Storage")
            until(10_000, "source root") { path() == "/" && !bs.isLoading }
            click("item_Music")
            until(10_000, "Music") { path() == "/Music" && !bs.isLoading }
            pressBack()
            until(5_000, "source root again") { path() == "/" }
            assertFalse("no up button at the source root", shown("btn_browser_up"))
            assertTrue(shown("btn_browser_back"))
        } finally {
            onUi { browser.bookmarksRepository.remove(ref) }
        }
    }
}
