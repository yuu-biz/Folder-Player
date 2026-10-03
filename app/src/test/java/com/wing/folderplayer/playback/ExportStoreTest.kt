package com.wing.folderplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Auto-save destination rules: a track counts as already saved only through its own identity (source + path + size +
 * mtime) and a still existing output; an unrelated file with the same name (and even the same size) is never taken for
 * it and never overwritten; colliding names get a free " (n)" name.
 */
class ExportStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun part(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    @Test fun uniqueNamePicksTheNextFreeName() {
        assertEquals("track.flac", ExportCore.uniqueName("track.flac", emptySet()))
        assertEquals("track (2).flac", ExportCore.uniqueName("track.flac", setOf("track.flac")))
        assertEquals("track (3).flac", ExportCore.uniqueName("track.flac", setOf("Track.FLAC", "track (2).flac")))
        assertEquals("README (2)", ExportCore.uniqueName("README", setOf("readme")))
    }

    @Test fun identityIncludesSourceAndVersionOfTheFile() {
        val a = ExportCore.identity("fpsrc://smb/A/CD1/t.flac", 10, 1)
        assertNotEquals(a, ExportCore.identity("fpsrc://ftp/A/CD1/t.flac", 10, 1))
        assertNotEquals(a, ExportCore.identity("fpsrc://smb/B/CD1/t.flac", 10, 1))
        assertNotEquals(a, ExportCore.identity("fpsrc://smb/A/CD1/t.flac", 10, 2))
    }

    @Test fun anUnrelatedSameNameSameSizeFileIsNeitherADuplicateNorOverwritten() {
        val store = FileExportStore(tmp.newFolder("music"))
        val userFile = File(tmp.root, "music/Music/FolderPlayer/CD1/track.flac").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100) { 7 }) }
        val index = mutableMapOf<String, String>()
        val id = ExportCore.identity("fpsrc://smb/A/CD1/track.flac", 100, 1)
        assertFalse("same name and size, but not our export", ExportCore.alreadyExported(index, id, 100, store))

        val ours = ByteArray(100) { 1 }
        val saved = ExportCore.publish(part(ours), "Music/FolderPlayer/CD1", "track.flac", store)
        index[id] = saved
        assertTrue(saved.endsWith("track (2).flac"))
        assertArrayEquals("user file untouched", ByteArray(100) { 7 }, userFile.readBytes())
        assertArrayEquals(ours, File(saved).readBytes())
        assertTrue(ExportCore.alreadyExported(index, id, 100, store))
    }

    @Test fun twoSourcesWithTheSameFolderAndFileNameGetSeparateFiles() {
        val store = FileExportStore(tmp.newFolder("music"))
        val a = ExportCore.publish(part(ByteArray(10) { 1 }), "Music/FolderPlayer/CD1", "01.flac", store)
        val b = ExportCore.publish(part(ByteArray(10) { 2 }), "Music/FolderPlayer/CD1", "01.flac", store)
        assertNotEquals(a, b)
        assertArrayEquals(ByteArray(10) { 1 }, File(a).readBytes())
        assertArrayEquals(ByteArray(10) { 2 }, File(b).readBytes())
    }

    @Test fun aDeletedOutputIsExportedAgain() {
        val store = FileExportStore(tmp.newFolder("music"))
        val id = ExportCore.identity("fpsrc://smb/A/t.flac", 5, 1)
        val saved = ExportCore.publish(part(ByteArray(5)), "Music/FolderPlayer/A", "t.flac", store)
        val index = mutableMapOf(id to saved)
        assertTrue(ExportCore.alreadyExported(index, id, 5, store))
        File(saved).delete()
        assertFalse(ExportCore.alreadyExported(index, id, 5, store))
    }

    @Test fun writeRefusesToReplaceAnExistingFile() {
        val store = FileExportStore(tmp.newFolder("music"))
        val existing = File(tmp.root, "music/Music/X/a.flac").apply { parentFile!!.mkdirs(); writeText("keep") }
        val r = runCatching { store.write(part(ByteArray(3)), "Music/X", "a.flac") }
        assertTrue("must not replace: ${r.getOrNull()}", r.isFailure)
        assertEquals("keep", existing.readText())
    }
}
