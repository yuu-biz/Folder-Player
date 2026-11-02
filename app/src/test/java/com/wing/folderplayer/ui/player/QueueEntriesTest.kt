package com.wing.folderplayer.ui.player

import com.wing.folderplayer.data.playlist.PlaylistItem
import com.wing.folderplayer.data.playlist.moved
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEntriesTest {
    private fun song(name: String) = PlaylistItem(path = "/Music/$name.flac", title = name, sourceId = "src")
    private fun cueTrack(start: Long) = PlaylistItem(path = "/Music/album.cue", sourceId = "src", cueStartMs = start)
    private fun idOf(item: PlaylistItem): String =
        item.cueStartMs?.let { SourceUris.cueTrackId(SourceRef("src", "/Music/album.flac"), it) } ?: item.ref!!.toUriString()

    @Test fun aTapStartsAtTheEntryTappedAlsoForACueTrackOrASongListedTwice() {
        // The entries of a CUE sheet are the sheet; the queue items are tracks of its audio file.
        val cue = listOf(cueTrack(0), cueTrack(240_000), cueTrack(480_000))
        val all = QueueEntries.align(cue, cue.map(::idOf))!!
        assertEquals(2, all.startFor(2))
        val twice = listOf(song("a"), song("b"), song("a"))
        assertEquals(2, QueueEntries.align(twice, twice.map(::idOf))!!.startFor(2))
    }

    @Test fun aTapOnAnEntryThatCannotPlayStartsAtTheNextOne() {
        val entries = QueueEntries(intArrayOf(1, 3), entryCount = 4)
        assertEquals(0, entries.startFor(0))
        assertEquals(1, entries.startFor(2))
        assertNull(QueueEntries(intArrayOf(0), entryCount = 2).startFor(1))
    }

    @Test fun aQueueWithEntriesLeftOutStillFollowsItsList() {
        // "x" could not be built (a CUE sheet on a server that is down): the queue is a, b, c.
        val list = listOf(song("a"), song("x"), song("b"), song("c"))
        val entries = QueueEntries.align(list, listOf("a", "b", "c").map { idOf(song(it)) })!!
        assertNull(entries.queueIndexOf(1))
        assertEquals(1, entries.queueIndexOf(2))
        // Play next after "a" goes right after it in the list, "Add to queue" at the end of the list: "x" is kept.
        assertEquals(1, entries.entryIndexForInsert(1))
        assertEquals(4, entries.entryIndexForInsert(3))
    }

    @Test fun aQueueWithSongsTheListDoesNotHoldIsNotTheList() {
        val list = listOf(song("a"), song("b"))
        assertNull(QueueEntries.align(list, listOf("a", "z").map { idOf(song(it)) }))
        assertNull(QueueEntries.align(list, listOf("b", "a").map { idOf(song(it)) }))
    }

    @Test fun aMoveInTheListMovesTheQueueToTheSameOrder() {
        val list = listOf("a", "x", "b", "c", "y")
        val queued = setOf("a", "b", "c")
        val entries = QueueEntries(list.indices.filter { list[it] in queued }.toIntArray(), list.size)
        for (from in list.indices) for (to in list.indices) {
            val expected = list.moved(from, to)!!.filter { it in queued }
            val move = entries.queueMove(from, to)
            if (list[from] !in queued) { assertNull("$from->$to", move); continue }
            val queue = list.filter { it in queued }
            assertEquals("$from->$to", expected, queue.moved(move!!.first, move.second))
        }
    }
}
