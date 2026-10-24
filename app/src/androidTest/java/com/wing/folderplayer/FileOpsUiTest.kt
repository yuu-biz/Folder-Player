package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rename, move and delete of a file from the long-press sheet and from the viewer, on a LOCAL source in the app's own files
 * directory (the app may change those files; see ViewerUiTest for why the fixture is not used). A file in shared storage
 * that the app did not create is refused with the hint to use a SAF folder.
 */
@RunWith(AndroidJUnit4::class)
class FileOpsUiTest : UiTestBase() {
    private lateinit var local: String
    private lateinit var dir: File
    private val album = "/Album"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    /** A fresh folder for every test: two images, two text files, an empty subfolder. */
    @Before fun folderInOwnFiles() {
        SourceRegistry.init(Fx.ctx)
        val root = File(Fx.ctx.getExternalFilesDir(null), "fileops")
        root.deleteRecursively()
        dir = File(root, "Album").apply { mkdirs() }
        File(dir, "Scans").mkdirs()
        for ((src, name) in listOf("cover.jpg" to "a.jpg", "folder.png" to "b.png")) Fx.localFile("${Fx.FX}/Album-A/$src").copyTo(File(dir, name))
        File(dir, "notes.txt").writeText("liner notes")
        File(dir, "readme.txt").writeText("read me")
        local = (SourceRegistry.savedSources().firstOrNull { it.name == "fileops-test" }
            ?: SourceConfig(name = "fileops-test", type = SourceType.LOCAL, url = root.absolutePath).also { SourceRegistry.upsert(it, null) }).id
    }

    private fun open(source: String, path: String, item: String) {
        toBrowser()
        // The app may have listed the folder from the last run while @Before rebuilt it: read it again, not from the cache.
        onUi { browser.loadFolder(SourceRef(source, path)); browser.refresh() }
        until(10_000, path) { bs.currentFolder?.path == path && !bs.isLoading && exists("item_$item") }
    }

    private fun names() = dir.list()!!.sorted()

    /** On screen. A row taken out of a lazy list can stay in the test tree, unplaced, so "gone" is "not displayed". */
    private fun shown(tag: String) = runCatching { node(tag).assertIsDisplayed(); true }.getOrDefault(false)

    @Test fun f01_renameFromTheLongPressSheet() {
        open(local, album, "notes.txt")
        longClick("item_notes.txt")
        click("action_rename")
        until(5_000, "name dialog") { exists("name_field") }
        compose.onNodeWithTag("name_field").performTextReplacement("liner notes.txt")
        click("name_confirm")
        until(10_000, "renamed in the list") { exists("item_liner notes.txt") && !shown("item_notes.txt") && bs.fileOp == null }
        assertEquals("liner notes", File(dir, "liner notes.txt").readText())
        assertFalse(File(dir, "notes.txt").exists())
    }

    @Test fun f02_moveIntoAFolderPickedInTheDialog() {
        open(local, album, "a.jpg")
        longClick("item_a.jpg")
        click("action_move")
        until(10_000, "folders listed") { exists("file_move_folder_Scans") }
        assertEquals("starts in the file's folder", "fileops-test · $album", text("file_move_path"))
        click("file_move_folder_Scans")
        until(10_000, "in Scans") { text("file_move_path").endsWith("$album/Scans") }
        click("file_op_confirm")
        until(10_000, "moved") { File(dir, "Scans/a.jpg").exists() && bs.fileOp == null }
        assertFalse(File(dir, "a.jpg").exists())
        until(10_000, "gone from the list") { bs.files.none { it.name == "a.jpg" } && !shown("item_a.jpg") }
    }

    @Test fun f03_deleteFromTheViewerGoesOnToTheNextImage() {
        open(local, album, "a.jpg")
        click("item_a.jpg")
        until(10_000, "viewer") { exists("viewer_image") && text("viewer_title") == "a.jpg" }
        click("viewer_menu")
        click("viewer_delete")
        until(5_000, "confirmation inside the viewer") { exists("file_delete_dialog") }
        click("file_op_confirm")
        until(10_000, "next image") { text("viewer_title") == "b.png" && bs.viewer?.files?.size == 1 }
        assertFalse(File(dir, "a.jpg").exists())

        // The last image: the viewer closes.
        click("viewer_menu")
        click("viewer_delete")
        click("file_op_confirm")
        until(10_000, "viewer closed") { bs.viewer == null && !exists("file_viewer") }
        assertEquals(listOf("Scans", "notes.txt", "readme.txt"), names())
    }

    @Test fun f04_aTakenNameIsRefusedAndNothingIsOverwritten() {
        open(local, album, "notes.txt")
        longClick("item_notes.txt")
        click("action_rename")
        until(5_000, "name dialog") { exists("name_field") }
        compose.onNodeWithTag("name_field").performTextReplacement("README.txt")
        click("name_confirm")
        until(10_000, "refused") { bs.fileOp == null && textExists(str(R.string.file_name_taken, "README.txt")) }
        assertEquals("liner notes", File(dir, "notes.txt").readText())
        assertEquals("read me", File(dir, "readme.txt").readText())
        assertFalse(names().contains("README.txt"))
    }

    @Test fun f05_aRenameInTheViewerKeepsShowingTheFile() {
        open(local, album, "readme.txt")
        click("item_readme.txt")
        until(10_000, "text") { exists("viewer_text") }
        click("viewer_menu")
        click("viewer_rename")
        until(5_000, "name dialog in the viewer") { exists("name_field") }
        compose.onNodeWithTag("name_field").performTextReplacement("read me first.txt")
        click("name_confirm")
        until(10_000, "renamed in the viewer") { text("viewer_title") == "read me first.txt" }
        assertTrue(File(dir, "read me first.txt").exists())
        click("viewer_close")
        until(10_000, "renamed in the list") { exists("item_read me first.txt") }
    }

    /** The fixture was pushed by adb: on Android 10+ the app may not change it through the file system. */
    @Test fun f06_aSharedStorageFileTheAppDidNotCreateIsRefusedWithTheSafHint() {
        val original = Fx.localFile("${Fx.FX}/Album-B/arbitrary-name.png")
        val renamed = File(original.parentFile, "renamed.png")
        try {
            open(SourceRegistry.LOCAL_INTERNAL_ID, "${Fx.FX}/Album-B", "arbitrary-name.png")
            longClick("item_arbitrary-name.png")
            click("action_rename")
            until(5_000, "name dialog") { exists("name_field") }
            compose.onNodeWithTag("name_field").performTextReplacement("renamed.png")
            click("name_confirm")
            until(10_000, "hint shown") { textExists(str(R.string.file_op_local_refused)) }
            assertTrue(original.exists())
            assertFalse(renamed.exists())
        } finally {
            if (renamed.exists() && !original.exists()) renamed.renameTo(original)
        }
    }
}
