package com.wing.folderplayer

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The file viewer of the browser: a text file and the images beside the songs open in it (Album-A of the fixture holds
 * `Info.nfo`, `cover.jpg` and `folder.png`); images swipe to the next image of the folder; Back and the close button
 * return to the folder, which stays as it was; the long-press sheet of a viewable file offers "Open" and not the playlist.
 */
@RunWith(AndroidJUnit4::class)
class ViewerUiTest : UiTestBase() {
    private lateinit var local: String
    private val albumA = "/Album-A"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    /**
     * On Android 11+ an `.nfo` that another app (adb) wrote to shared storage is neither listed nor readable through the
     * File API (docs/fork/ANDROID_STORAGE.md), so the fixture's Album-A is rebuilt in the app's own files directory, a
     * LOCAL source: the two images copied from the fixture, `Info.nfo` with the fixture's text.
     */
    @Before fun albumInOwnFiles() {
        SourceRegistry.init(Fx.ctx)
        val root = File(Fx.ctx.getExternalFilesDir(null), "viewer")
        val dir = File(root, "Album-A").apply { mkdirs() }
        for (name in listOf("cover.jpg", "folder.png")) Fx.localFile("${Fx.FX}/Album-A/$name").copyTo(File(dir, name), overwrite = true)
        File(dir, "Info.nfo").writeText(INFO_NFO)
        local = (SourceRegistry.savedSources().firstOrNull { it.name == "viewer-test" }
            ?: SourceConfig(name = "viewer-test", type = SourceType.LOCAL, url = root.absolutePath).also { SourceRegistry.upsert(it, null) }).id
    }

    private fun openAlbumA() {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, albumA)) }
        until(10_000, "Album-A") { bs.currentFolder?.path == albumA && !bs.isLoading && exists("item_Info.nfo") }
    }

    /** System Back as the device sends it: to the viewer's own window. */
    private fun deviceBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
    }

    private fun closed() = !exists("file_viewer") && bs.viewer == null

    @Test fun a80_textFileOpensAndClosesBackToTheFolder() {
        openAlbumA()
        click("item_Info.nfo")
        until(10_000, "text shown") { exists("viewer_text") && textExists("  <title>Album A (NFO)</title>") }
        assertEquals("Info.nfo", text("viewer_title"))
        assertEquals(str(R.string.viewer_encoding_auto, "UTF-8"), text("viewer_subtitle"))
        click("viewer_close")
        until(5_000, "closed") { closed() }
        assertEquals(albumA, bs.currentFolder?.path)

        // Back closes the viewer, not the folder.
        click("item_Info.nfo")
        until(10_000, "text again") { exists("viewer_text") }
        deviceBack()
        until(5_000, "closed by Back") { closed() }
        assertEquals(albumA, bs.currentFolder?.path)
    }

    @Test fun a81_imagesSwipeToTheNextImageOfTheFolder() {
        openAlbumA()
        click("item_cover.jpg")
        until(10_000, "image shown") { exists("viewer_image") }
        assertEquals("cover.jpg", text("viewer_title"))
        assertEquals(str(R.string.viewer_image_count, 1, 2), text("viewer_subtitle"))
        node("viewer_pager").performTouchInput { swipeLeft() }
        until(10_000, "second image") { text("viewer_title") == "folder.png" }
        assertEquals(str(R.string.viewer_image_count, 2, 2), text("viewer_subtitle"))
        assertFalse(exists("viewer_error"))
        deviceBack()
        until(5_000, "closed") { closed() }
    }

    @Test fun a82_longPressOffersOpenForViewableFiles() {
        openAlbumA()
        longClick("item_cover.jpg")
        until(5_000, "sheet") { exists("action_open") && exists("action_open_external") }
        assertFalse(exists("action_add_playlist"))
        click("action_open")
        until(10_000, "viewer from the sheet") { exists("viewer_image") }
        click("viewer_close")
        until(5_000, "closed") { closed() }
    }

    private companion object {
        val INFO_NFO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <album>
              <title>Album A (NFO)</title>
              <artist>Fixture Artist</artist>
              <review>NFO review text for Album A.</review>
            </album>
        """.trimIndent() + "\n"
    }
}
