package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.history.HistoryEntry
import com.wing.folderplayer.data.history.HistoryRepository
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The playback history of the source list: its rows say when and where, one is removed from its sheet, the rest cleared. */
@RunWith(AndroidJUnit4::class)
class HistoryUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val history get() = HistoryRepository.get(Fx.ctx)
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    // Compose 1.5 keeps a removed row in the unmerged test tree (a reused slot): a row that is gone is checked as not displayed.
    private fun displayed(tag: String) = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(); true }.getOrDefault(false)

    private val song = HistoryEntry(sourceId = local, path = "${Fx.FX}/Album-B/track.flac", name = "track.flac", title = "", durationMs = 61_000)
    private val cue = HistoryEntry(sourceId = local, path = "${Fx.FX}/Cue/image.cue", name = "image.cue")

    @Before fun seed() {
        onUi { history.clear(); history.record(song); history.record(cue) }
    }

    @After fun clear() {
        onUi { history.clear() }
    }

    @Test fun rowsSayWhenAndWhereAndAreRemovedOneByOneOrAllAtOnce() {
        toBrowser()
        onUi { browser.exitSource() }
        until(5_000, "source list") { exists("entry_history") }
        assertTrue(text("entry_history_count").contains("2"))
        click("entry_history")
        until(5_000, "history list") { exists("file_list") && exists("item_track.flac") && exists("item_image.cue") }
        assertEquals("newest first", listOf("image.cue", "track.flac"), browser.uiState.value.files.map { it.name })

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val played = history.items.value.first { it.name == "track.flac" }.playedAt
        assertEquals(format.format(Date(played)) + " · ${Fx.FX}/Album-B", text("info_track.flac"))
        assertTrue("a CUE sheet row has its time too: ${text("info_image.cue")}", text("info_image.cue").endsWith(" · ${Fx.FX}/Cue"))

        longClick("item_track.flac")
        click("action_history_remove")
        until(5_000, "row removed") { !displayed("item_track.flac") }
        assertEquals(listOf("image.cue"), history.items.value.map { it.name })

        click("btn_overflow")
        click("menu_history_clear")
        until(5_000, "confirmation") { exists("history_clear_dialog") }
        click("history_clear_confirm")
        until(5_000, "empty history") { exists("history_empty") }
        assertTrue(history.items.value.isEmpty())

        pressBack()
        until(5_000, "back on the source list") { exists("entry_history") }
        assertTrue(text("entry_history_count").contains("0"))
    }
}
