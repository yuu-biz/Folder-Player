package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bookmarks: folders of this device, kept in their own file, new ones at the end; renamed and moved by the user. */
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

    @Test fun renameKeepsThePlaceAndIsStored() {
        val r = repo()
        r.add(album, "Album #1+%"); r.add(root, "NAS")
        val id = r.items.value[0].id
        r.rename(id, "  Jazz  ")
        r.rename(id, "   ")
        r.rename("no-such-id", "x")
        assertEquals("trimmed; a blank name is ignored", listOf("Jazz", "NAS"), r.items.value.map { it.name })
        assertEquals("kept in the file", listOf("Jazz", "NAS"), repo().items.value.map { it.name })
        assertTrue(repo().isBookmarked(album))
    }

    /** a, hidden (its source was removed), b, c. */
    private fun repoWithAHiddenOne(): BookmarksRepository = repo().apply {
        add(SourceRef("s", "/a"), "a"); add(SourceRef("removed-source", "/"), "hidden"); add(SourceRef("s", "/b"), "b"); add(SourceRef("s", "/c"), "c")
    }
    private fun BookmarksRepository.names() = items.value.map { it.name }
    private fun BookmarksRepository.id(name: String) = items.value.first { it.name == name }.id
    private fun BookmarksRepository.shownIds() = items.value.filter { it.sourceId != "removed-source" }.map { it.id }

    @Test fun reorderPutsTheShownInTheGivenOrderAndHiddenOnesKeepTheirSlot() {
        val r = repoWithAHiddenOne()
        r.reorder(listOf(r.id("c"), r.id("a"), r.id("b")))
        assertEquals(listOf("c", "hidden", "a", "b"), r.names())
        r.reorder(listOf(r.id("b"), "no-such-id", r.id("c"), r.id("b")))
        assertEquals("unknown and repeated ids are ignored; a left out of the list keeps its slot",
            listOf("b", "hidden", "a", "c"), r.names())
        assertEquals("kept in the file", r.names(), repo().names())
    }

    @Test fun reorderWritesNothingWhenTheOrderDoesNotChange() {
        val r = repoWithAHiddenOne()
        file.delete()
        r.reorder(r.shownIds())
        r.reorder(emptyList())
        r.reorder(listOf("no-such-id"))
        assertFalse(file.exists())
        assertEquals(listOf("a", "hidden", "b", "c"), r.names())
    }

    @Test fun upAndDownSwapWithTheNextShownBookmarkAndStopAtTheEnds() {
        val r = repoWithAHiddenOne()
        fun move(name: String, delta: Int) = r.reorder(BookmarksRepository.swapped(r.shownIds(), r.id(name), delta))
        move("a", -1)
        assertEquals("first stays first", listOf("a", "hidden", "b", "c"), r.names())
        move("a", +1)
        assertEquals("passes the hidden one: a moves below b on screen", listOf("b", "hidden", "a", "c"), r.names())
        move("c", -1)
        assertEquals(listOf("b", "hidden", "c", "a"), r.names())
        move("a", +1)
        assertEquals("last stays last", listOf("b", "hidden", "c", "a"), r.names())
        assertEquals("unknown id: unchanged", listOf("x", "y"), BookmarksRepository.swapped(listOf("x", "y"), "z", 1))
        assertEquals("kept in the file", r.names(), repo().names())
    }
}
