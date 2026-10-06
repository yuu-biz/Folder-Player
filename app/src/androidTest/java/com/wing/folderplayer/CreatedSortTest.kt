package com.wing.folderplayer

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sort by creation date: a local folder (files made one after the other, then given the opposite modification times) and
 * an SMB share tell the creation time; a WebDAV source does not, and then the creation order is the modification order.
 */
@RunWith(AndroidJUnit4::class)
class CreatedSortTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val dir = "/sdcard/Music/fixture/CreatedSort"
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val bs get() = browser.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    @Before fun reset() {
        SourcePreferences(Fx.ctx).saveDefaultViewMode("LIST")
        SourcePreferences(Fx.ctx).saveDefaultSort("NAME", true)
    }

    @After fun cleanup() {
        Fx.shell("rm -rf $dir")
    }

    private fun open(ref: SourceRef) {
        toBrowser()
        onUi { browser.loadFolder(ref) }
        until(15_000, "folder ${ref.path}") { bs.currentFolder == ref && !bs.isLoading }
        if (bs.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    /** File names of the listed songs in the order they are shown (by their rows' places). */
    private fun shownOrder(names: List<String>): List<String> =
        names.filter { exists("item_$it") }.sortedBy { node("item_$it").fetchSemanticsNode().boundsInRoot.top }

    private fun sortBy(field: String, expectAscending: Boolean) {
        click("sortbtn_$field")
        until(5_000, "sorted by $field") { bs.sortField == field && bs.sortAscending == expectAscending }
        compose.waitForIdle()
    }

    @Test fun a_localCreationOrderDiffersFromModificationOrder() {
        // (The shell here runs one plain command per call: no quotes, no ";", no spaces in paths.)
        Fx.shell("rm -rf $dir")
        Fx.shell("mkdir -p $dir")
        val names = listOf("c1.flac", "c2.flac", "c3.flac")
        for (n in names) {
            Fx.shell("cp /sdcard/Music/fixture/Album-B/track.flac $dir/$n")
            Thread.sleep(1_200)
        }
        // The opposite modification order: c1 newest, c3 oldest.
        Fx.shell("touch -t 203101030000 $dir/c1.flac")
        Fx.shell("touch -t 203101020000 $dir/c2.flac")
        Fx.shell("touch -t 203101010000 $dir/c3.flac")
        Fx.shell("content call --uri content://media/external/file --method scan_volume --arg external_primary")
        Thread.sleep(1_500)
        val ref = SourceRef(local, "/Music/fixture/CreatedSort")
        val listed = SourceRegistry.fileSystem(ref).list(ref.path).filter { !it.isDirectory }.associateBy { it.name }
        Fx.log("created/modified: " + names.joinToString { "${it}=${listed[it]?.createdAt}/${listed[it]?.lastModified}" })
        names.forEach { assertTrue("$it has a creation time", (listed[it]?.createdAt ?: 0) > 0) }
        // The platform must report the real creation time, not the modification time again.
        assumeTrue("this device reports the modification time as creation time",
            names.any { listed[it]!!.createdAt != listed[it]!!.lastModified })

        open(ref)
        until(10_000, "rows") { names.all { exists("item_$it") } }
        sortBy("DATE", true)
        assertEquals("modified, oldest first", listOf("c3.flac", "c2.flac", "c1.flac"), shownOrder(names))
        sortBy("CREATED", true)
        assertEquals("created, oldest first", listOf("c1.flac", "c2.flac", "c3.flac"), shownOrder(names))
        sortBy("CREATED", false)
        assertEquals("created, newest first", listOf("c3.flac", "c2.flac", "c1.flac"), shownOrder(names))
        // The chosen order is kept for the folder (and "Created" is a valid stored value).
        assertEquals("CREATED", SourcePreferences(Fx.ctx).getDirectorySort(ref)?.field)
        // The list shows the creation date beside the format while sorted by it.
        assertTrue(text("info_c1.flac").contains("FLAC") && text("info_c1.flac").contains("-"))
    }

    @Test fun b_smbTellsTheCreationTime() {
        Fx.require("smb_host")
        val smb = SourceConfig(name = "smb-created", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", path = "/fixture", username = "alice")
        SourceRegistry.upsert(smb, "alicepass")
        try {
            val ref = SourceRef(smb.id, "/Album-A")
            val files = SourceRegistry.fileSystem(ref).list(ref.path).filter { !it.isDirectory }
            assertTrue("files listed", files.isNotEmpty())
            files.forEach { assertTrue("${it.name}: creation time ${it.createdAt}", it.createdAt > 0) }
            open(ref)
            sortBy("CREATED", true)
            val expected = files.filter { exists("item_${it.name}") }.sortedBy { it.createdOrModified }.map { it.name }
            val shown = shownOrder(expected)
            // Same creation time = any order among them: compare the times, not the names.
            val byName = files.associateBy { it.name }
            assertEquals(expected.map { byName[it]!!.createdOrModified }, shown.map { byName[it]!!.createdOrModified })
        } finally {
            SourceRegistry.remove(smb.id)
        }
    }

    @Test fun c_webDavHasNoCreationTimeAndFallsBackToModified() {
        Fx.require("webdav_url")
        val dav = SourceConfig(name = "dav-created", type = SourceType.WEBDAV, url = Fx.arg("webdav_url")!!, username = "alice")
        SourceRegistry.upsert(dav, "pa:ss/1")
        try {
            val ref = SourceRef(dav.id, "/fixture/Album-A")
            val files = SourceRegistry.fileSystem(ref).list(ref.path).filter { !it.isDirectory }
            assertTrue("files listed", files.isNotEmpty())
            files.forEach { assertEquals("${it.name}: no creation time from WebDAV", 0L, it.createdAt) }
            open(ref)
            sortBy("CREATED", true)
            val shown = shownOrder(files.map { it.name })
            val byName: Map<String, MusicFile> = files.associateBy { it.name }
            assertEquals("falls back to the modification time",
                files.sortedBy { it.lastModified }.map { it.lastModified }, shown.map { byName[it]!!.lastModified })
        } finally {
            SourceRegistry.remove(dav.id)
        }
    }
}
