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
 * Back is the previous place, as in a browser: from a folder opened from the favourites list or from a bookmark, and from
 * the folders entered after it, step by step back to that folder and then to the list. The arrow-up button of the top bar
 * goes up one folder, and Back after it returns to where the user was.
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
    private fun at(p: String, what: String) = until(10_000, what) { path() == p && !bs.isLoading }

    @Test fun a60_backFromAFavouriteGoesStepByStepToTheFavouriteAndThenTheFavouritesList() {
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
            click("item_Music"); at("/Music", "Music")
            click("item_fixture"); at(fx, "fixture")
            click("item_Album-A"); at("$fx/Album-A", "Album-A")

            // Back: the previous folder, then the favourite itself, then the favourites list.
            pressBack(); at(fx, "back to fixture")
            pressBack(); at("/Music", "back to the favourite")
            pressBack()
            until(5_000, "favourites again") { bs.showingFavorites && shown("item_Album-B") }

            // The other favourite; arrow-up goes to the folder above, and Back returns to the favourite.
            click("item_Album-B"); at("$fx/Album-B", "Album-B")
            click("btn_browser_up"); at(fx, "one up")
            pressBack(); at("$fx/Album-B", "back to the favourite after up")
            pressBack()
            until(5_000, "favourites after up") { bs.showingFavorites && shown("item_Music") }
            // From the favourites list Back goes to the source list.
            pressBack()
            until(5_000, "source list") { bs.isRoot && exists("source_list") }
        } finally {
            had.filter { !it.second }.forEach { (f, _) -> onUi { browser.toggleFavorite(f) } }
        }
    }

    @Test fun a61_backFromABookmarkGoesToTheBookmarkedFolderThenTheSourceList() {
        toBrowser()
        val fixture = SourceRef(local, fx)
        val albumA = SourceRef(local, "$fx/Album-A")
        onUi { browser.bookmarksRepository.add(fixture, "fixture"); browser.bookmarksRepository.add(albumA, "Album-A") }
        try {
            // A folder entered below a bookmarked folder: Back is the bookmarked folder, then the source list.
            onUi { browser.exitSource() }
            until(5_000, "bookmarks listed") { shown("bookmark_fixture") && shown("bookmark_Album-A") }
            click("bookmark_fixture"); at(fx, "fixture")
            click("item_Album-B"); at("$fx/Album-B", "Album-B")
            pressBack(); at(fx, "back to the bookmarked folder")
            pressBack()
            until(5_000, "source list") { bs.isRoot && shown("bookmark_fixture") }

            // Arrow-up twice, then Back returns step by step: the folder, the bookmark, the source list.
            click("bookmark_Album-A"); at("$fx/Album-A", "Album-A")
            click("btn_browser_up"); at(fx, "one up")
            click("btn_browser_up"); at("/Music", "two up")
            pressBack(); at(fx, "back after two ups")
            pressBack(); at("$fx/Album-A", "back to the bookmark")
            pressBack()
            until(5_000, "source list again") { bs.isRoot && shown("bookmark_Album-A") }

            // A source opened from the list goes back level by level.
            click("source_Internal Storage"); at("/", "source root")
            click("item_Music"); at("/Music", "Music")
            pressBack(); at("/", "source root again")
            assertFalse("no arrow-up at the source root", shown("btn_browser_up"))
            assertTrue(shown("btn_browser_back"))
            pressBack()
            until(5_000, "source list from the root") { bs.isRoot && exists("source_list") }
        } finally {
            onUi { browser.bookmarksRepository.remove(fixture); browser.bookmarksRepository.remove(albumA) }
        }
    }
}
