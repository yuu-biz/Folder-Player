package com.wing.folderplayer.data.metadata

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SORT_LENGTH
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.TrackTags
import org.junit.Assert.assertEquals
import org.junit.Test

class SongListTest {
    private fun song(path: String, ms: Long, tags: TrackTags? = null, modified: Long = 1) =
        MusicFile(path.substringAfterLast('/'), path, false, 100, modified, "s", durationMs = ms, tags = tags)

    private val a1 = song("/A/01 x.flac", 60_000, TrackTags("Opening", "Zed", "Alpha", 1), modified = 3)
    private val a2 = song("/A/02 y.flac", 30_000, TrackTags("Middle", "Zed", "Alpha", 2), modified = 1)
    private val b1 = song("/B/b.mp3", 90_000, TrackTags("Again", "Amy", "Beta", 0), modified = 2)
    private val plain = song("/C/untagged song.mp3", 45_000)
    private val all = listOf(plain, a2, b1, a1)

    private fun names(l: List<MusicFile>) = l.map { SongList.title(it) }

    @Test fun titleIsTheTagOrTheFileName() {
        assertEquals("Opening", SongList.title(a1))
        assertEquals("untagged song", SongList.title(plain))
        assertEquals("blank title tag", "x", SongList.title(song("/x.mp3", 1, TrackTags("", "Zed", "", 0))))
    }

    @Test fun byTitleBothWays() {
        assertEquals(listOf("Again", "Middle", "Opening", "untagged song"), names(SongList.sort(all, SongList.TITLE, true)))
        assertEquals(listOf("untagged song", "Opening", "Middle", "Again"), names(SongList.sort(all, SongList.TITLE, false)))
    }

    @Test fun byArtistThenAlbumAndTrackNumberWithoutArtistLast() {
        assertEquals(listOf("Again", "Opening", "Middle", "untagged song"), names(SongList.sort(all, SongList.ARTIST, true)))
        // Reversed: the artists turn round, an album still plays in track order, a song without artist stays last.
        assertEquals(listOf("Opening", "Middle", "Again", "untagged song"), names(SongList.sort(all, SongList.ARTIST, false)))
    }

    @Test fun byAlbumInTrackOrder() {
        assertEquals(listOf("Opening", "Middle", "Again", "untagged song"), names(SongList.sort(all, SongList.ALBUM, true)))
        assertEquals(listOf("Again", "Opening", "Middle", "untagged song"), names(SongList.sort(all, SongList.ALBUM, false)))
    }

    @Test fun byLengthAndDate() {
        assertEquals(listOf("Middle", "untagged song", "Opening", "Again"), names(SongList.sort(all, SORT_LENGTH, true)))
        assertEquals(listOf("Again", "Opening", "untagged song", "Middle"), names(SongList.sort(all, SORT_LENGTH, false)))
        assertEquals(listOf("Opening", "Again", "Middle", "untagged song"), names(SongList.sort(all, SongList.DATE, false)))
    }

    @Test fun filterLooksAtTitleArtistAlbumAndFileName() {
        assertEquals(listOf("Opening", "Middle"), names(SongList.filter(listOf(a1, a2, b1, plain), "zed")))
        assertEquals(listOf("Again"), names(SongList.filter(listOf(a1, a2, b1, plain), "beta again")))
        assertEquals("file name", listOf("untagged song"), names(SongList.filter(listOf(a1, a2, b1, plain), "UNTAGGED")))
        assertEquals("every word", emptyList<String>(), names(SongList.filter(listOf(a1, a2, b1, plain), "zed beta")))
        assertEquals(4, SongList.filter(listOf(a1, a2, b1, plain), "  ").size)
    }

    @Test fun inFoldersKeepsTheFoldersAndTheFoldersBelowThem() {
        val deeper = song("/A/CD2/z.flac", 1)
        val sibling = song("/AB/w.flac", 1) // starts like /A, is not below it
        val other = song("/A/v.flac", 1).copy(sourceId = "t")
        val songs = listOf(a1, a2, deeper, sibling, b1, other)
        assertEquals(listOf(a1, a2, deeper), SongList.inFolders(songs, listOf(SourceRef("s", "/A"))))
        assertEquals(listOf(deeper), SongList.inFolders(songs, listOf(SourceRef("s", "/A/CD2"))))
        assertEquals("the top folder: the whole source", listOf(a1, a2, deeper, sibling, b1), SongList.inFolders(songs, listOf(SourceRef("s", "/"))))
        assertEquals("no folder: every song", songs, SongList.inFolders(songs, emptyList()))
        assertEquals(emptyList<MusicFile>(), SongList.inFolders(songs, listOf(SourceRef("s", "/Nothing"))))
        // 1.8.0: several folders, also of different sources; a song below two of them (/A and /A/CD2) is listed once.
        assertEquals(listOf(a1, a2, deeper, b1, other), SongList.inFolders(songs, listOf(SourceRef("s", "/A"), SourceRef("s", "/A/CD2"), b1.ref.parent!!, SourceRef("t", "/"))))
    }
}
