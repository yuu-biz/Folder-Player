package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.MusicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The favourites list sorted by when an entry was added or, as a folder, by the files' own name, times and size. */
class FavoritesSortTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(name: String, added: Long, modified: Long = 0, size: Long = 0, folder: Boolean = false) = FavoriteItem(
        type = if (folder) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG,
        sourceId = "nas", path = "/$name", name = name, size = size, timestamp = added, modified = modified,
    )

    private val items = listOf(
        item("b.flac", added = 30, modified = 100, size = 5),
        item("Album", added = 20, modified = 300, folder = true),
        item("a.flac", added = 10, modified = 200, size = 9),
        item("c.flac", added = 30, modified = 50, size = 1),
    )

    private fun names(field: String, ascending: Boolean = true) = FavoritesSort.sort(items, field, ascending).map { it.name }

    @Test fun addedKeepsFoldersAndSongsTogetherAndTiesInTheOrderOfTheFile() {
        assertEquals(listOf("a.flac", "Album", "b.flac", "c.flac"), names(FavoritesSort.ADDED))
        assertEquals(listOf("c.flac", "b.flac", "Album", "a.flac"), names(FavoritesSort.ADDED, ascending = false))
    }

    @Test fun theBrowserFieldsPutFoldersFirstAndSortByTheFilesOwnValues() {
        assertEquals(listOf("Album", "a.flac", "b.flac", "c.flac"), names("NAME"))
        assertEquals(listOf("Album", "c.flac", "b.flac", "a.flac"), names("DATE"))
        assertEquals(listOf("Album", "a.flac", "b.flac", "c.flac"), names("DATE", ascending = false))
        assertEquals(listOf("Album", "c.flac", "b.flac", "a.flac"), names("SIZE"))
    }

    @Test fun theListShowsTheFilesTimesNotWhenTheyWereAdded() {
        val f = FavoritesSort.asFile(item("a.flac", added = 10, modified = 200, size = 9))
        assertEquals(200L, f.lastModified)
        assertEquals(9L, f.size)
    }

    @Test fun addKeepsTheFileTimesAndUpdateMetadataFillsOldEntries() {
        var now = 1_000L
        val repo = FavoritesRepository(File(tmp.root, "fav.json")) { now }
        repo.add(MusicFile("new.flac", "/new.flac", false, 7, lastModified = 500, sourceId = "nas", createdAt = 400))
        val added = repo.items.value.single()
        assertEquals(listOf(1_000L, 500L, 400L), listOf(added.timestamp, added.modified, added.created))

        val old = added.copy(modified = 0, created = 0, size = 0)
        File(tmp.root, "old.json").writeText(FavoritesCodec.encode(FavoritesData(items = listOf(old))))
        val repo2 = FavoritesRepository(File(tmp.root, "old.json")) { now }
        repo2.updateMetadata(mapOf(old.key to MusicFile("new.flac", "/new.flac", false, 8, lastModified = 600, sourceId = "nas", createdAt = 300)))
        val filled = FavoritesRepository(File(tmp.root, "old.json")) { now }.items.value.single()
        assertEquals("kept in the file", listOf(8L, 600L, 300L, 1_000L), listOf(filled.size, filled.modified, filled.created, filled.timestamp))
    }

    @Test fun favouriteSongsByLengthWithTheKnownLengths() {
        val items = listOf(item("long.flac", added = 1), item("short.flac", added = 2), item("new.flac", added = 3))
        val lengths = mapOf("long.flac" to 300_000L, "short.flac" to 10_000L)
        val sorted = FavoritesSort.sort(items, com.wing.folderplayer.data.source.SORT_LENGTH, true) { lengths[it.name] }
        assertEquals(listOf("short.flac", "long.flac", "new.flac"), sorted.map { it.name })
        assertTrue(FavoritesSort.needsFileTimes(com.wing.folderplayer.data.source.SORT_LENGTH))
    }
}
