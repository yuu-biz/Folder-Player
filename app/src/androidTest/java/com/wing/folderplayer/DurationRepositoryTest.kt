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

    private fun waitScan(repo: DurationRepository, before: Int): DurationRepository.ScanProgress {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val p = repo.scan.value
            if (!p.running && p.finished > before) return p
            Thread.sleep(100)
        }
        error("scan did not end: ${repo.scan.value}")
    }

    /** The cache answers without reading; a file moved elsewhere on its source (same name, size, time) is found again. */
    @Test fun cachedIsMemoryOnlyAndFollowsAMovedFile() = runBlocking {
        val opens = AtomicInteger()
        val repo = repo(opens, 0) { real(it) }
        assertNull("not read yet", repo.cached(mp3))
        assertAbout(30_000, repo.duration(mp3))
        assertAbout(30_000, repo.cached(mp3))
        val moved = mp3.copy(path = "${Fx.FX}/Elsewhere/02 track.mp3")
        assertAbout(30_000, repo.cached(moved))
        assertNull("another file of the same name", repo.cached(mp3.copy(path = "${Fx.FX}/X/02 track.mp3", size = mp3.size + 1)))
        assertEquals("read once", 1, opens.get())
    }

    /** A scan reads each song once (a second scan reads nothing), keeps the lengths on disk, drops files no longer there. */
    @Test fun scanReadsOnceKeepsTheResultAndDropsWhatIsGone() = runBlocking {
        val album = SourceRef(local, "${Fx.FX}/Album-A")
        val opens = AtomicInteger()
        val ghost = MusicFile("ghost.mp3", "${album.path}/ghost.mp3", false, 10, 1L, local)
        val repo = DurationRepository(Fx.ctx, dir) { f ->
            opens.incrementAndGet()
            if (f.name == ghost.name) FailingReaderOfNothing else real(f)
        }
        assertNull(repo.duration(ghost)) // kept as unparsable: a file that is not in the folder
        val afterGhost = opens.get()
        assertNull(repo.duration(ghost))
        assertEquals("unparsable kept", afterGhost, opens.get())

        var before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(album)))
        val first = waitScan(repo, before)
        assertEquals("folder", 1, first.folders)
        assertEquals("two songs read", 2, first.read)
        val opensAfterFirst = opens.get()

        before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(album)))
        val second = waitScan(repo, before)
        assertEquals(2, second.songs)
        assertEquals("nothing read again", 0, second.read)
        assertEquals(opensAfterFirst, opens.get())

        // The ghost is not in the folder: its entry went, so it is looked at again.
        repo.duration(ghost)
        assertEquals(opensAfterFirst + 1, opens.get())

        // Written at the end of the scan: another instance knows the lengths without reading.
        val fresh = DurationRepository(Fx.ctx, dir) { error("must not read ${it.name}") }
        assertAbout(30_000, fresh.cached(mp3))
    }

    /** A scan of a folder (a bookmark's) lists and reads that folder and the folders below it, nothing beside or above it. */
    @Test fun aScanStaysInsideItsFolder() = runBlocking {
        val listed = java.util.Collections.synchronizedList(ArrayList<String>())
        val read = java.util.Collections.synchronizedList(ArrayList<String>())
        val repo = DurationRepository(Fx.ctx, dir, listFolder = { ref -> listed += ref.path; SourceRegistry.fileSystem(ref).list(ref.path) }) { f ->
            read += f.path
            real(f)
        }
        val parent = "${Fx.FX}/Parent"
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, parent))))
        val p = waitScan(repo, before)
        assertEquals("Parent and CD1 ($p)", listOf(parent, "$parent/CD1"), listed.toList())
        assertEquals(listOf("$parent/CD1/track.flac"), read.toList())
        assertEquals(listOf("$parent/CD1/track.flac"), repo.songs().map { it.path })
    }

    /** A scan reads a few files of a source at the same time, never more than the source allows. */
    @Test fun scanReadsSeveralFilesAtOnceButNoMoreThanAllowed() = runBlocking {
        val active = AtomicInteger()
        val most = AtomicInteger()
        val opens = AtomicInteger()
        val repo = DurationRepository(Fx.ctx, dir, scanParallel = { 3 }) { f ->
            val n = active.incrementAndGet()
            most.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            // The first ones take long, so that the readers are seen side by side.
            if (opens.incrementAndGet() <= 12) Thread.sleep(150)
            active.decrementAndGet()
            real(f)
        }
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, Fx.FX))))
        val p = waitScan(repo, before)
        assertTrue("songs read ($p)", p.read > 10)
        assertEquals("three readers at once", 3, most.get())
    }

    /** A source whose files cannot be read is left after a few failures, also when several readers fail together. */
    @Test fun aSourceThatFailsIsLeftAlsoWithReadersSideBySide() = runBlocking {
        val opens = AtomicInteger()
        val repo = DurationRepository(Fx.ctx, dir, scanParallel = { 4 }) { opens.incrementAndGet(); throw IOException("connection reset") }
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, Fx.FX))))
        val p = waitScan(repo, before)
        assertEquals("nothing read ($p)", 0, p.read)
        assertEquals("every song counted as not read", p.songs, p.notRead)
        // The fixture holds over 300 songs in over 20 folders: once left, the source is not listed further either.
        assertTrue("left after a few tries: ${opens.get()} opened, $p", opens.get() in 3..8 && p.songs < 40 && p.folders < 10)
    }

    /** Tags are read with the lengths and kept; a length kept without them (1.7.0) is read again by a scan for them. */
    @Test fun tagsAreKeptAndAnOlderIndexIsReadAgainForThem() = runBlocking {
        val f = mp3
        File(dir, "duration-index-v2.json").writeText("""{"version":2,"known":{"${f.sourceId}|${f.path}|${f.size}|${f.lastModified}":30000}}""")
        val opens = AtomicInteger()
        val repo = repo(opens, 0) { real(it) }
        assertAbout(30_000, repo.cached(f))
        val before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, "${Fx.FX}/Album-A"))))
        val p = waitScan(repo, before)
        assertEquals("both songs read: the flac for its length, the mp3 for its tags ($p)", 2, p.read)
        val song = repo.songs().first { it.path == f.path }
        assertEquals(com.wing.folderplayer.data.source.TrackTags("Tagged Title Two", "Fixture Artist", "Album A", 2), song.tags)
        assertAbout(30_000, song.durationMs)
        // On disk with the tags: a second scan and another instance read nothing.
        val again = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(SourceRef(local, "${Fx.FX}/Album-A"))))
        assertEquals(0, waitScan(repo, again).read)
        val fresh = DurationRepository(Fx.ctx, dir) { error("must not read ${it.name}") }
        assertEquals("Tagged Title One", fresh.songs().first { it.name.startsWith("01") }.tags?.title)
    }

    /** The browser listing a folder drops what is gone from it; a moved file is listed once, where it was seen last. */
    @Test fun aListedFolderDropsWhatIsGoneAndAMovedFileIsListedOnce() = runBlocking {
        val opens = AtomicInteger()
        val repo = repo(opens, 0) { real(it) }
        assertAbout(30_000, repo.duration(mp3))
        val moved = mp3.copy(path = "${Fx.FX}/Elsewhere/02 track.mp3")
        assertAbout(30_000, repo.cached(moved))
        assertEquals("one entry", listOf(moved.path), repo.songs().filter { it.name == mp3.name }.map { it.path })
        // Album-A listed without the mp3 (it is in the other folder now): its old entry goes.
        val album = SourceRef(local, "${Fx.FX}/Album-A")
        repo.onFolderListed(album, listOf(file("${Fx.FX}/Album-A/01 曲 #1+%.flac")))
        assertEquals(listOf(moved.path), repo.songs().filter { it.name == mp3.name }.map { it.path })
        // And the other folder listed without it: gone from the list.
        repo.onFolderListed(SourceRef(local, "${Fx.FX}/Elsewhere"), emptyList())
        assertTrue(repo.songs().none { it.name == mp3.name })
        assertEquals("read once", 1, opens.get())
    }

    /** A folder deleted below a scanned root (nobody lists it any more) or gone itself loses its songs; an unreadable one keeps them. */
    @Test fun aScanDropsTheSongsOfFoldersThatAreGoneButNotOfUnreadableOnes() = runBlocking {
        val parent = SourceRef(local, "${Fx.FX}/Parent")
        val cd1 = file("${Fx.FX}/Parent/CD1/track.flac")
        var hideCd1 = false
        var denied = false
        val repo = DurationRepository(Fx.ctx, dir, listFolder = { ref ->
            when {
                denied && ref.path.endsWith("/CD1") -> throw SourceException.PermissionDenied()
                ref.path.endsWith("/Gone") -> throw SourceException.NotFound()
                else -> SourceRegistry.fileSystem(ref).list(ref.path).filter { !(hideCd1 && it.name == "CD1") }
            }
        }) { f -> if (f.name == "ghost.flac") FailingReaderOfNothing else real(f) }
        assertTrue(repo.duration(cd1) != null)
        val ghost = MusicFile("ghost.flac", "${Fx.FX}/Gone/Deeper/ghost.flac", false, 10, 1L, local)
        assertNull(repo.duration(ghost)) // unparsable, but kept

        // CD1 cannot be read: what is known in it stays.
        denied = true
        var before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(parent, SourceRef(local, "${Fx.FX}/Gone"))))
        waitScan(repo, before)
        assertTrue("kept below an unreadable folder", repo.songs().any { it.path == cd1.path })

        // CD1 deleted: the walk of Parent does not see it; Gone answers "not found".
        denied = false
        hideCd1 = true
        before = repo.scan.value.finished
        assertTrue(repo.startScan(listOf(parent, SourceRef(local, "${Fx.FX}/Gone"))))
        waitScan(repo, before)
        assertTrue("dropped below a deleted folder", repo.songs().none { it.path == cd1.path })
        val opens = AtomicInteger()
        val fresh = DurationRepository(Fx.ctx, dir) { opens.incrementAndGet(); FailingReaderOfNothing }
        assertNull(fresh.duration(ghost))
        assertEquals("the ghost's entry went with its folder: read again", 1, opens.get())
    }

    private object FailingReaderOfNothing : RandomAccessReader {
        private val bytes = ByteArray(10) { 'x'.code.toByte() }
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
