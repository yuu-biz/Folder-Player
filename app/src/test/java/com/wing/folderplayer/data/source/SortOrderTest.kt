package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The browser list and the queue of "play folder" / restore / next folder use the same sort. Equal keys are the rule
 * for "Created" (DATE_ADDED has a resolution of one second, an album is copied in one go), so their order must not
 * depend on the listing order of the file system, nor on the direction of the sort.
 */
class SortOrderTest {
    private fun f(name: String, size: Long = 0, modified: Long = 0, created: Long = 0) =
        MusicFile(name, "/a/$name", false, size, modified, "s", created)

    private fun names(l: List<MusicFile>) = l.map { it.name }

    /** One album copied at once: the same creation second, listed in an arbitrary (hash) order. */
    private val album = listOf(f("03 c.flac", created = 5_000), f("01 a.flac", created = 5_000), f("02 b.flac", created = 5_000))

    @Test fun equalCreatedTimesFollowTheNameWhateverTheListingOrder() {
        val expected = listOf("01 a.flac", "02 b.flac", "03 c.flac")
        assertEquals(expected, names(sortMusicFiles(album, "CREATED", true)))
        assertEquals(expected, names(sortMusicFiles(album.reversed(), "CREATED", true)))
        // Descending reverses the key only: the tracks of one album stay in track order.
        assertEquals(expected, names(sortMusicFiles(album, "CREATED", false)))
        assertEquals(expected, names(sortMusicFiles(album.reversed(), "CREATED", false)))
    }

    @Test fun descendingPutsTheNewestFirstAndKeepsTiesByName() {
        val l = album + f("00 old.flac", created = 1_000) + f("99 new.flac", created = 9_000)
        assertEquals(listOf("99 new.flac", "01 a.flac", "02 b.flac", "03 c.flac", "00 old.flac"), names(sortMusicFiles(l, "CREATED", false)))
        assertEquals(listOf("00 old.flac", "01 a.flac", "02 b.flac", "03 c.flac", "99 new.flac"), names(sortMusicFiles(l, "CREATED", true)))
    }

    @Test fun createdFallsBackToModifiedWhereTheSourceHasNoCreationTime() {
        val l = listOf(f("b", modified = 2_000), f("a", modified = 3_000), f("c", modified = 1_000, created = 9_000))
        assertEquals(listOf("b", "a", "c"), names(sortMusicFiles(l, "CREATED", true)))
    }

    @Test fun otherFieldsAlsoBreakTiesByName() {
        val l = listOf(f("z", size = 10), f("a", size = 10), f("m", size = 5))
        assertEquals(listOf("m", "a", "z"), names(sortMusicFiles(l, "SIZE", true)))
        assertEquals(listOf("a", "z", "m"), names(sortMusicFiles(l, "SIZE", false)))
        val d = listOf(f("z", modified = 7), f("a", modified = 7))
        assertEquals(listOf("a", "z"), names(sortMusicFiles(d, "DATE", false)))
    }

    @Test fun nameSortIsCaseInsensitiveAndReversible() {
        val l = listOf(f("b.mp3"), f("A.mp3"), f("c.mp3"))
        assertEquals(listOf("A.mp3", "b.mp3", "c.mp3"), names(sortMusicFiles(l, "NAME", true)))
        assertEquals(listOf("c.mp3", "b.mp3", "A.mp3"), names(sortMusicFiles(l, "NAME", false)))
        assertEquals("unknown field = name", names(sortMusicFiles(l, null, true)), names(sortMusicFiles(l, "NAME", true)))
    }
}
