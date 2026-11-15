package com.wing.folderplayer.data.history

import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The playback history: one row per file, the one played last first, at most 300, kept in its own file. */
class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun song(path: String, sourceId: String = "nas-1") = HistoryEntry(sourceId = sourceId, path = path, name = path.substringAfterLast('/'))

    @Test fun playingAgainMovesTheSongToTheTopWithTheNewTime() {
        var list = History.record(emptyList(), song("/A/1.flac"), now = 10)
        list = History.record(list, song("/A/2.flac"), now = 20)
        list = History.record(list, song("/A/1.flac", sourceId = "nas-2"), now = 30)
        list = History.record(list, song("/A/1.flac"), now = 40)
        assertEquals(listOf("nas-1" to "/A/1.flac", "nas-2" to "/A/1.flac", "nas-1" to "/A/2.flac"), list.map { it.key })
        assertEquals(listOf(40L, 30L, 20L), list.map { it.playedAt })
    }

    @Test fun theOldestGoesPastTheLimit() {
        var list = emptyList<HistoryEntry>()
        for (i in 1..History.MAX_ENTRIES + 1) list = History.record(list, song("/A/$i.flac"), now = i.toLong())
        assertEquals(History.MAX_ENTRIES, list.size)
        assertEquals("/A/${History.MAX_ENTRIES + 1}.flac", list.first().path)
        assertEquals("the first one played is gone", "/A/2.flac", list.last().path)
    }

    @Test fun removeTakesOutOneFileAndClearTakesAll() {
        val list = listOf(song("/A/1.flac"), song("/A/1.flac", sourceId = "nas-2"), song("/A/2.flac"))
        assertEquals(listOf("nas-2" to "/A/1.flac", "nas-1" to "/A/2.flac"), History.remove(list, SourceRef("nas-1", "/A/1.flac")).map { it.key })
        assertTrue(History.clear().isEmpty())
    }

    @Test fun aCueTrackIsRecordedAsItsSheetAndASongAsItself() {
        val audio = SourceRef("nas-1", "/Live/image.flac")
        val track = History.entryOf(SourceUris.cueTrackId(audio, 183_000), "/Live/image.cue", "Track 3", "Band", null, durationMs = 240_000, size = 9)!!
        assertEquals("nas-1" to "/Live/image.cue", track.key)
        assertEquals("image.cue", track.name)
        assertEquals("a track title does not name the sheet", "", track.title)
        val other = History.entryOf(SourceUris.cueTrackId(audio, 0), "/Live/image.cue", "Track 1", null, null, 0, 0)!!
        assertEquals("every track of a sheet is the same entry", 1, History.record(History.record(emptyList(), track, 1), other, 2).size)

        val songRef = SourceRef("nas-1", "/A/01 曲 #1+%.flac")
        val song = History.entryOf(songRef.toUriString(), null, " Title ", "Artist", "Album", 200_000, 5)!!
        assertEquals("nas-1" to "/A/01 曲 #1+%.flac", song.key)
        assertEquals(listOf("Title", "Artist", "Album"), listOf(song.title, song.artist, song.album))
        assertNull("a file of another app is not recorded", History.entryOf("content://media/external/audio/1", null, "x", null, null, 0, 0))
    }

    @Test fun theFileKeepsTheListAndAnUnreadableFileIsKeptAside() {
        val file = File(tmp.root, "history/history.json")
        var now = 1_000L
        val repo = HistoryRepository(file) { now }
        repo.record(song("/A/1.flac").copy(title = "One", durationMs = 61_000)); now = 2_000
        repo.record(song("/Cue/image.cue"))
        val reread = HistoryRepository(file) { now }.items.value
        assertEquals(listOf("/Cue/image.cue" to 2_000L, "/A/1.flac" to 1_000L), reread.map { it.path to it.playedAt })
        assertEquals("One", reread[1].title)
        assertEquals(61_000L, reread[1].durationMs)
        repo.remove(SourceRef("nas-1", "/Cue/image.cue"))
        assertEquals(listOf("/A/1.flac"), HistoryRepository(file) { now }.items.value.map { it.path })

        file.writeText("{ not json")
        val broken = HistoryRepository(file) { now }
        assertTrue(broken.items.value.isEmpty())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("history.json.corrupt-") && it.readText() == "{ not json" })
        broken.record(song("/A/2.flac"))
        assertEquals(listOf("/A/2.flac"), HistoryRepository(file) { now }.items.value.map { it.path })
    }
}
