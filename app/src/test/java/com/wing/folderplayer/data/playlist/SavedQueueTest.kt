package com.wing.folderplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService

class SavedQueueTest {
    @get:Rule val tmpDir = TemporaryFolder()

    private fun saved(index: Int, id: String) = SavedQueue("default", index, id, 61_000, shuffle = false)

    @Test fun startsAtTheSavedPositionOfASongThatIsInTheQueueTwice() {
        val ids = listOf("a", "b", "a", "c")
        assertEquals(2, saved(2, "a").startIn(ids))
        assertEquals(0, saved(0, "a").startIn(ids))
    }

    @Test fun entriesLeftOutMoveTheSongToTheNearestOccurrence() {
        // Two entries before it could not be built (a removed source): the song is two places earlier.
        assertEquals(3, saved(5, "x").startIn(listOf("a", "b", "c", "x", "d")))
        assertEquals(3, saved(4, "x").startIn(listOf("x", "b", "c", "x")))
    }

    @Test fun aSongNoLongerInTheQueueGivesNoStart() {
        assertNull(saved(1, "gone").startIn(listOf("a", "b")))
        assertNull(saved(0, "a").startIn(emptyList()))
    }

    // ---- the store behind it ----

    private fun item(name: String) = PlaylistItem(path = "/Music/$name.flac", title = name, sourceId = "local-internal")

    @Test fun aSavedListIsReadBackAtOnceAndReachesTheDiskInOrder() {
        val store = PlaylistStore(tmpDir.root)
        store.savePlaylist(Playlist("default", "Default", listOf(item("a"))))
        store.savePlaylist(Playlist("default", "Default", listOf(item("b"), item("c"))))
        assertEquals(listOf("b", "c"), store.getPlaylist("default")!!.items.map { it.title })
        PlaylistStore.flush()
        assertEquals(listOf("b", "c"), PlaylistStore(tmpDir.root).getPlaylist("default")!!.items.map { it.title })
        assertEquals(listOf("default.fpl", "metadata.json"), tmpDir.root.list()!!.sorted())
    }

    /** Keeps the store's writer busy until the latch returned is released: writes made meanwhile are pending. */
    private fun holdWriter(): CountDownLatch {
        val writer = PlaylistStore::class.java.getDeclaredField("writer").apply { isAccessible = true }.get(null) as ExecutorService
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { started.countDown(); release.await() }
        started.await()
        return release
    }

    @Test fun aDeletedListStaysDeletedWhileItsFileIsStillOnDisk() {
        val store = PlaylistStore(tmpDir.root)
        val id = store.createPlaylist("Mine")!!
        PlaylistStore.flush()
        val release = holdWriter()
        store.deletePlaylist(id)
        try {
            assertNull(store.getPlaylist(id))
        } finally {
            release.countDown()
        }
        PlaylistStore.flush()
        assertNull(store.getPlaylist(id))
        assertEquals(false, File(tmpDir.root, "$id.fpl").exists())
        assertEquals(listOf("Default"), PlaylistStore(tmpDir.root).getAllPlaylists().map { it.name })
    }

    @Test fun aNewStoreReadsTheChangesStillBeingWritten() {
        val store = PlaylistStore(tmpDir.root)
        val release = holdWriter()
        store.createPlaylist("Mine")
        var second: PlaylistStore? = null
        val reader = Thread { second = PlaylistStore(tmpDir.root) }.apply { start() }
        Thread.sleep(200)
        release.countDown()
        reader.join(5_000)
        assertEquals(setOf("Default", "Mine"), second!!.getAllPlaylists().map { it.name }.toSet())
    }
}
