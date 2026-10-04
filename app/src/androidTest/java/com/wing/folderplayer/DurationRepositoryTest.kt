package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.metadata.DurationRepository
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Track lengths: a failure to read (connection, authentication, permission, cancellation) is not remembered, so the
 * same file (same path, size, mtime) gets its length once it can be read again; only a file that cannot be parsed is
 * kept as "unknown".
 */
@RunWith(AndroidJUnit4::class)
class DurationRepositoryTest {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private lateinit var dir: File

    @Before fun setUp() {
        SourceRegistry.init(Fx.ctx)
        dir = File(Fx.ctx.cacheDir, "duration-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun file(path: String): MusicFile {
        val st = SourceRegistry.fileSystem(SourceRef(local, path)).stat(path) ?: error("fixture missing: $path")
        return MusicFile(path.substringAfterLast('/'), path, false, st.size, st.lastModified, local)
    }

    private fun real(f: MusicFile): RandomAccessReader = SourceRegistry.fileSystem(f.ref).openRandomAccess(f.path)

    /** Reader whose reads fail with [error]. */
    private class FailingReader(private val inner: RandomAccessReader, private val error: () -> IOException) : RandomAccessReader {
        override val size: Long get() = inner.size
        override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = throw error()
        override fun close() = inner.close()
    }

    /** First [failures] opens give a reader made by [failing]; later opens read the real file. */
    private fun repo(opens: AtomicInteger, failures: Int, failing: (MusicFile) -> RandomAccessReader) =
        DurationRepository(Fx.ctx, dir) { f -> if (opens.incrementAndGet() <= failures) failing(f) else real(f) }

    private val mp3 get() = file("${Fx.FX}/Album-A/02 track.mp3")   // 30 s, MediaMetadataRetriever
    private val wma get() = file("${Fx.FX}/Formats/sample.wma")     // 20 s, FFmpeg

    private fun assertAbout(expectedMs: Long, actual: Long?) {
        assertTrue("length $actual, expected about $expectedMs", actual != null && kotlin.math.abs(actual - expectedMs) < 1_000)
    }

    @Test fun lostConnectionWhileParsingIsNotKept() = runBlocking {
        val opens = AtomicInteger()
        val repo = repo(opens, 1) { f -> FailingReader(real(f)) { IOException("connection reset") } }
        assertNull("read failed: no length", repo.duration(mp3))
        assertAbout(30_000, repo.duration(mp3))
        assertEquals("looked again", 2, opens.get())
    }

    @Test fun lostConnectionInTheNativeDecoderIsNotKept() = runBlocking {
        val opens = AtomicInteger()
        val repo = repo(opens, 1) { f -> FailingReader(real(f)) { IOException("connection reset") } }
        assertNull(repo.duration(wma))
        assertAbout(20_000, repo.duration(wma))
        assertEquals(2, opens.get())
    }

    @Test fun authenticationAndPermissionFailuresAreNotKept() = runBlocking {
        val opens = AtomicInteger()
        val repo = DurationRepository(Fx.ctx, dir) { f ->
            when (opens.incrementAndGet()) {
                1 -> throw SourceException.AuthFailed()
                2 -> FailingReader(real(f)) { SourceException.PermissionDenied() }
                else -> real(f)
            }
        }
        assertNull(repo.duration(mp3))
        assertNull(repo.duration(mp3))
        assertAbout(30_000, repo.duration(mp3))
    }

    @Test fun cancelledReadIsNotKept() = runBlocking {
        val opens = AtomicInteger()
        val repo = repo(opens, 1) { f ->
            object : RandomAccessReader {
                val inner = real(f)
                override val size: Long get() = inner.size
                override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int =
                    try { Thread.sleep(30_000); -1 } catch (e: InterruptedException) { throw InterruptedIOException("interrupted") }
                override fun close() = inner.close()
            }
        }
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { repo.duration(mp3) }
        delay(1_000)
        job.cancel()
        job.join()
        assertAbout(30_000, repo.duration(mp3))
        assertEquals(2, opens.get())
    }

    @Test fun unparsableFileIsKeptAsUnknownUntilRefresh() = runBlocking {
        val bytes = ByteArray(4096) { 'x'.code.toByte() } // read fine, but not audio
        val f = MusicFile("fake.mp3", "${Fx.FX}/none/fake.mp3", false, bytes.size.toLong(), 1L, local)
        val opens = AtomicInteger()
        val repo = DurationRepository(Fx.ctx, dir) {
            opens.incrementAndGet()
            object : RandomAccessReader {
                override val size: Long get() = bytes.size.toLong()
                override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(len.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun close() = Unit
            }
        }
        assertNull(repo.duration(f))
        assertNull(repo.duration(f))
        assertEquals("unparsable is kept: not read twice", 1, opens.get())
        repo.forgetUnknown()
        assertNull(repo.duration(f))
        assertEquals("looked at again after refresh", 2, opens.get())
    }

    @Test fun unknownLengthsOfAnOlderIndexAreLookedAtAgain() = runBlocking {
        // dev2 kept "0" (unknown) for every failure, transient ones included.
        val f = mp3
        File(dir, "duration-index.json").writeText("""{"${f.sourceId}|${f.path}|${f.size}|${f.lastModified}":0}""")
        val opens = AtomicInteger()
        val repo = repo(opens, 0) { real(it) }
        assertAbout(30_000, repo.duration(f))
        assertEquals("read again", 1, opens.get())
    }
}
