package com.wing.folderplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistStoreTest {
    @get:Rule val tmpDir = TemporaryFolder()

    private fun song(name: String) = PlaylistItem(path = "/Music/$name.flac", title = name, sourceId = "src")

    @Test fun aQueueSavedAsAPlaylistKeepsItsOrderAfterARestart() {
        val queue = listOf(song("b"), PlaylistItem(path = "/Music/album.cue", title = "Track 2", sourceId = "src", cueStartMs = 240_000), song("a"), song("b"))
        val id = PlaylistStore(tmpDir.root).createPlaylist("Evening", queue)!!
        PlaylistStore.flush()

        val read = PlaylistStore(tmpDir.root).getPlaylist(id)!!
        assertEquals("Evening", read.name)
        assertEquals(queue, read.items)
    }

    @Test fun noMoreThanTenPlaylistsBesideDefault() {
        val store = PlaylistStore(tmpDir.root)
        // Made within the same millisecond, each list is still a list of its own.
        repeat(10) { assertNotNull("list $it", store.createPlaylist("list $it", listOf(song("s$it")))) }
        assertNull(store.createPlaylist("one too many", listOf(song("x"))))
        PlaylistStore.flush()

        val names = PlaylistStore(tmpDir.root).getAllPlaylists().map { it.name }
        assertEquals(listOf("Default") + (0 until 10).map { "list $it" }, names.sortedBy { if (it == "Default") "" else it })
    }
}
