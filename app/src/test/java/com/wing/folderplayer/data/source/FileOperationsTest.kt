package com.wing.folderplayer.data.source

import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rename, move and delete of one file: names that every source can hold, no file overwritten, folders left alone. */
class FileOperationsTest {
    private val fs = InMemoryFileSystem("nas").apply {
        put("/Album/cover.jpg", "cover")
        put("/Album/Cover.png", "other cover")
        put("/Album/notes.txt", "notes")
        put("/Other/notes.txt", "other notes")
        mkdirs("/Empty")
    }

    private fun file(path: String) = fs.stat(path)!!

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try { block() } catch (e: Throwable) { if (e is T) return e; throw e }
        fail("expected ${T::class.simpleName}"); throw AssertionError()
    }

    @Test fun validNames() {
        assertEquals("trimmed", "a b.flac", FileOperations.validName("  a b.flac "))
        assertEquals("01 曲名.mp3", FileOperations.validName("01 曲名.mp3"))
        for (bad in listOf("", "  ", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\tb", "x".repeat(256))) {
            assertNull("refused: '$bad'", FileOperations.validName(bad))
        }
    }

    @Test fun renameInPlace() {
        val renamed = FileOperations.rename(fs, file("/Album/notes.txt"), " liner notes.txt ")
        assertEquals("/Album/liner notes.txt", renamed.path)
        assertEquals("liner notes.txt", renamed.name)
        assertNull(fs.stat("/Album/notes.txt"))
        assertArrayEquals("notes".toByteArray(), fs.files["/Album/liner notes.txt"])
    }

    @Test fun renameToTheSameNameDoesNothing() {
        val f = file("/Album/notes.txt")
        assertSame(f, FileOperations.rename(fs, f, "notes.txt"))
        assertEquals(0, fs.writes)
    }

    @Test fun aTakenNameIsRefusedWhateverItsCase() {
        // SMB, SAF on an SD card and FAT would overwrite Cover.png: the case is ignored.
        val e = assertFails<FileOperations.Failure.AlreadyExists> { FileOperations.rename(fs, file("/Album/cover.jpg"), "COVER.PNG") }
        assertEquals("COVER.PNG", e.name)
        assertArrayEquals("other cover".toByteArray(), fs.files["/Album/Cover.png"])
        assertArrayEquals("cover".toByteArray(), fs.files["/Album/cover.jpg"])
    }

    @Test fun onlyTheCaseOfItsOwnNameChanges() {
        val renamed = FileOperations.rename(fs, file("/Album/cover.jpg"), "Cover.jpg")
        assertEquals("/Album/Cover.jpg", renamed.path)
        assertNull(fs.files["/Album/cover.jpg"])
    }

    @Test fun anInvalidNameIsRefused() {
        assertFails<FileOperations.Failure.InvalidName> { FileOperations.rename(fs, file("/Album/notes.txt"), "../notes.txt") }
        assertArrayEquals("notes".toByteArray(), fs.files["/Album/notes.txt"])
    }

    @Test fun moveToAnotherFolder() {
        val moved = FileOperations.move(fs, file("/Album/cover.jpg"), "/Empty/")
        assertEquals("/Empty/cover.jpg", moved.path)
        assertNull(fs.stat("/Album/cover.jpg"))
        assertArrayEquals("cover".toByteArray(), fs.files["/Empty/cover.jpg"])
    }

    @Test fun moveToTheSameFolderDoesNothing() {
        val f = file("/Album/cover.jpg")
        assertSame(f, FileOperations.move(fs, f, "/Album"))
    }

    @Test fun moveOverAFileOfTheSameNameIsRefused() {
        assertFails<FileOperations.Failure.AlreadyExists> { FileOperations.move(fs, file("/Album/notes.txt"), "/Other") }
        assertArrayEquals("other notes".toByteArray(), fs.files["/Other/notes.txt"])
        assertArrayEquals("notes".toByteArray(), fs.files["/Album/notes.txt"])
    }

    @Test fun delete() {
        FileOperations.delete(fs, file("/Album/notes.txt"))
        assertNull(fs.stat("/Album/notes.txt"))
    }

    @Test fun aSourceThatSaysNoIsReported() {
        val refusing = object : SourceFileSystem by fs {
            override fun rename(from: String, to: String) = false
            override fun delete(path: String) = false
        }
        assertFails<FileOperations.Failure.Refused> { FileOperations.rename(refusing, file("/Album/notes.txt"), "x.txt") }
        assertFails<FileOperations.Failure.Refused> { FileOperations.move(refusing, file("/Album/notes.txt"), "/Empty") }
        assertFails<FileOperations.Failure.Refused> { FileOperations.delete(refusing, file("/Album/notes.txt")) }
        assertTrue(fs.files.containsKey("/Album/notes.txt"))
    }

    @Test fun foldersAreLeftAlone() {
        val folder = file("/Album")
        assertFails<FileOperations.Failure.NotAFile> { FileOperations.rename(fs, folder, "Album 2") }
        assertFails<FileOperations.Failure.NotAFile> { FileOperations.move(fs, folder, "/Empty") }
        assertFails<FileOperations.Failure.NotAFile> { FileOperations.delete(fs, folder) }
        assertTrue(fs.files.containsKey("/Album/cover.jpg"))
    }
}
