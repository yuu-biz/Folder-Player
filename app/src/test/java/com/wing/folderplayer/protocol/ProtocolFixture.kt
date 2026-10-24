package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.source.FileOperations
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import java.io.File

/**
 * Endpoints of the Docker fixture servers (scripts/fixtures/servers.sh). Tests are skipped — never passed — when
 * the properties are absent; only runs where they were set exercise the real protocols (see docs/fork/VERIFICATION.md).
 */
object ProtocolFixture {
    fun prop(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

    fun require(vararg names: String) {
        for (n in names) Assume.assumeTrue("fixture property $n not set", prop(n) != null)
    }

    val fixtureDir: File get() = File(prop("fp.fixture.dir") ?: "../build/fixture")

    fun local(path: String): File = File(fixtureDir, path.removePrefix("/"))

    const val TRICKY = "/fixture/Album-A/01 曲 #1+%.flac"

    /**
     * Rename, move into `/rw/sub` and delete through [FileOperations] with an account that may write below `/rw` (the
     * servers create `/rw/sub`): a taken name (other case) is refused and the other file kept.
     */
    fun checkFileOperations(fs: SourceFileSystem) {
        val n = System.nanoTime()
        val a = "/rw/ops-$n.txt"
        val taken = "/rw/taken-$n.txt"
        fs.write(a, "A".toByteArray(), overwrite = false)
        fs.write(taken, "T".toByteArray(), overwrite = false)
        try {
            val renamed = FileOperations.rename(fs, fs.stat(a)!!, "ops-$n-b.txt")
            assertEquals("/rw/ops-$n-b.txt", renamed.path)
            assertNull(fs.stat(a))
            val refused = runCatching { FileOperations.rename(fs, renamed, "TAKEN-$n.txt") }.exceptionOrNull()
            assertTrue("taken: $refused", refused is FileOperations.Failure.AlreadyExists)
            assertEquals("T", fs.readText(taken))
            val moved = FileOperations.move(fs, renamed, "/rw/sub")
            assertEquals("/rw/sub/ops-$n-b.txt", moved.path)
            assertNull(fs.stat(renamed.path))
            assertEquals("A", fs.readText(moved.path))
            FileOperations.delete(fs, moved)
            assertNull(fs.stat(moved.path))
        } finally {
            for (p in listOf(a, taken, "/rw/ops-$n-b.txt", "/rw/sub/ops-$n-b.txt")) runCatching { fs.delete(p) }
        }
    }
}
