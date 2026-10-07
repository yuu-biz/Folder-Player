package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bookmarks: folders of this device, kept in their own file, in the order they were added. */
class BookmarksTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private val file get() = File(tmp.root, "fav/bookmarks.json")
    private fun repo() = BookmarksRepository(file) { now }

    private val root = SourceRef("nas-1", "/")
    private val album = SourceRef("nas-1", "/Library/Jazz/Album #1+%")

    @Test fun addRemoveToggleAndOrder() {
        val r = repo()
        r.add(album, "Album #1+%"); now++; r.add(root, "NAS"); r.add(album, "again")
        assertEquals("one entry per folder, in the order added", listOf("Album #1+%", "NAS"), r.items.value.map { it.name })
        assertTrue(r.isBookmarked(album) && r.isBookmarked(root))
        assertFalse(r.isBookmarked(SourceRef("nas-2", "/")))
        r.toggle(album, "x")
        assertFalse(r.isBookmarked(album))
        r.toggle(album, "Album")
        assertEquals(listOf("NAS", "Album"), r.items.value.map { it.name })
        r.remove(root); r.remove(root)
        assertEquals(listOf("Album"), r.items.value.map { it.name })
    }

    @Test fun pathsAreNormalisedSoTheSameFolderIsOneBookmark() {
        val r = repo()
        r.add(SourceRef("s", "/a/b/"), "b"); r.add(SourceRef("s", "/a//b"), "b2"); r.add(SourceRef("s", "/a/./b"), "b3")
        assertEquals(1, r.items.value.size)
        assertTrue(r.isBookmarked(SourceRef("s", "/a/b")))
        assertEquals("/a/b", r.items.value.single().path)
        // A path that climbs out of the source is not a place: ignored, not an error.
        r.add(SourceRef("s", "/../x"), "bad")
        assertEquals(1, r.items.value.size)
    }

    @Test fun survivesARestartAndHoldsNoCredentials() {
        repo().apply { add(root, "NAS"); add(album, "Album") }
        val again = repo()
        assertEquals(listOf("/" , "/Library/Jazz/Album #1+%"), again.items.value.map { it.path })
        assertEquals("nas-1", again.items.value[0].sourceId)
        assertFalse(file.readText().contains("password", ignoreCase = true))
        assertFalse("no temporary file is left behind", File(file.path + ".tmp").exists())
        assertEquals(listOf("id", "name", "path", "sourceId", "timestamp"),
            com.google.gson.JsonParser.parseString(file.readText()).asJsonObject["items"].asJsonArray[0].asJsonObject.keySet().sorted())
    }

    @Test fun bookmarksAreNotFavourites() {
        val fav = FavoritesRepository(File(tmp.root, "fav/fav.json")) { now }
        repo().add(album, "Album")
        assertTrue(fav.items.value.isEmpty())
        assertFalse(File(tmp.root, "fav/fav.json").exists())
    }

    @Test fun corruptFileIsKeptAside() {
        file.parentFile!!.mkdirs(); file.writeText("garbage")
        val r = repo()
        assertTrue(r.items.value.isEmpty())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("bookmarks.json.corrupt-") })
        r.add(root, "NAS")
        assertEquals(1, repo().items.value.size)
    }

    @Test fun entriesThisVersionCannotReadAreBackedUpBeforeTheFileIsRewritten() {
        val text = """{"version":1,"items":[
          {"id":"1","sourceId":"nas-1","path":"/ok","name":"ok","timestamp":1},
          {"id":"2","sourceId":"","path":"relative","name":"future","timestamp":2}]}"""
        file.parentFile!!.mkdirs(); file.writeText(text)
        val r = repo()
        assertEquals(listOf("/ok"), r.items.value.map { it.path })
        r.add(root, "NAS")
        val backups = file.parentFile!!.listFiles()!!.filter { it.name.startsWith("bookmarks.json.unreadable-") }
        assertEquals(1, backups.size)
        assertEquals(text, backups.single().readText())
    }

    @Test fun aNewerFileVersionIsNotOverwrittenSilently() {
        val text = """{"version":9,"items":[{"id":"1","sourceId":"nas-1","path":"/ok","name":"ok","timestamp":1}]}"""
        file.parentFile!!.mkdirs(); file.writeText(text)
        assertTrue(repo().items.value.isEmpty())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("bookmarks.json.corrupt-") && it.readText() == text })
    }
}
