package com.wing.folderplayer.data.search

import com.wing.folderplayer.data.source.MusicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The folder on screen: music-only keeps folders, songs and CUE sheets; the name filter applies on top of it. */
class FolderEntriesTest {
    private fun dir(name: String) = MusicFile(name, "/Music/$name", true, 0, 0, "s")
    private fun file(name: String) = MusicFile(name, "/Music/$name", false, 0, 0, "s")

    private val files = listOf(
        dir("Live 1999"),
        dir("Scans.jpg"),
        file("01 Intro.flac"),
        file("02 Live.MP3"),
        file("03 live.dsf"),
        file("album.cue"),
        file("Live.cue"),
        file("cover.jpg"),
        file("live poster.png"),
        file("booklet.pdf"),
        file("notes.txt"),
        file("Info.nfo"),
        file("01 Intro.lrc"),
        file("live.zip"),
        file("README"),
    )

    private fun names(filter: String, musicOnly: Boolean) = folderEntries(files, filter, musicOnly).map { it.name }

    @Test fun musicOnlyKeepsFoldersSongsAndCueSheets() {
        assertEquals(
            listOf("Live 1999", "Scans.jpg", "01 Intro.flac", "02 Live.MP3", "03 live.dsf", "album.cue", "Live.cue"),
            names("", musicOnly = true),
        )
    }

    @Test fun musicOnlyAndTheFilterBothApply() {
        assertEquals(listOf("Live 1999", "02 Live.MP3", "03 live.dsf", "Live.cue"), names("live", musicOnly = true))
    }

    @Test fun offIsTheNameFilterAlone() {
        assertSame(files, folderEntries(files, "", musicOnly = false))
        assertEquals(NameFilter.apply(files, "live").map { it.name }, names("live", musicOnly = false))
        assertEquals(listOf("Live 1999", "02 Live.MP3", "03 live.dsf", "Live.cue", "live poster.png", "live.zip"), names("live", musicOnly = false))
    }
}
