package com.wing.folderplayer.playback

import androidx.media3.common.PlaybackException
import com.wing.folderplayer.testutil.InMemoryFileSystem
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- retry policy ----

    @Test fun retryOnlyTransientErrors() {
        val p = RetryPolicy()
        assertTrue(p.isRetryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(p.isRetryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(p.isRetryable(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)) // auth failure
        assertFalse(p.isRetryable(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertFalse(p.isRetryable(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test fun retryDelaysGrowAndStopAfterFiveMinutes() {
        val p = RetryPolicy()
        val start = 0L
        assertEquals(1_000L, p.nextDelay(1, start, start))
        assertEquals(2_000L, p.nextDelay(2, start, start + 1_000))
        assertEquals(30_000L, p.nextDelay(10, start, start + 60_000)) // capped
        var now = start
        var attempt = 0
        while (true) {
            attempt++
            val d = p.nextDelay(attempt, start, now) ?: break
            now += d + 500 // each attempt fails again shortly after
        }
        assertTrue("gave up after ${now}ms", now <= 5 * 60 * 1000L)
        assertNull(p.nextDelay(attempt + 1, start, 5 * 60 * 1000L))
    }

    // ---- export never publishes partial data ----

    @Test fun exportCopiesExactBytes() = runBlocking {
        val fs = InMemoryFileSystem("n").apply { put("/a/song.flac", ByteArray(300_000) { (it % 251).toByte() }) }
        val out = tmp.root.resolve("e/song.part")
        ExportCore.download(fs, "/a/song.flac", 300_000, out, Long.MAX_VALUE)
        assertArrayEquals(fs.files["/a/song.flac"], out.readBytes())
    }

    @Test fun exportDeletesTruncatedDownload() = runBlocking {
        val fs = InMemoryFileSystem("n").apply {
            put("/a/song.flac", ByteArray(300_000))
            truncateAt["/a/song.flac"] = 100_000
        }
        val out = tmp.root.resolve("e/song.part")
        try {
            ExportCore.download(fs, "/a/song.flac", 300_000, out, Long.MAX_VALUE)
            fail("truncated download accepted")
        } catch (e: java.io.IOException) {
        }
        assertFalse(out.exists())
    }

    @Test fun exportDetectsSizeMismatchAndNoSpace() = runBlocking {
        val fs = InMemoryFileSystem("n").apply { put("/a/song.flac", ByteArray(1000)) }
        val out = tmp.root.resolve("e/x.part")
        try { ExportCore.download(fs, "/a/song.flac", 2000, out, Long.MAX_VALUE); fail() } catch (e: ExportCore.IncompleteException) {}
        assertFalse(out.exists())
        try { ExportCore.download(fs, "/a/song.flac", 1000, out, 1000); fail() } catch (e: ExportCore.NoSpaceException) {}
        assertFalse(out.exists())
    }

    @Test fun cancelledExportLeavesNoFile() = runBlocking {
        val fs = InMemoryFileSystem("n").apply { put("/a/song.flac", ByteArray(4_000_000)); readDelayMs = 50 }
        val out = tmp.root.resolve("e/cancel.part")
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { ExportCore.download(fs, "/a/song.flac", 4_000_000, out, Long.MAX_VALUE) }
        kotlinx.coroutines.delay(300)
        assertTrue("download in progress", out.exists())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse("cancelled download must not leave a (partial) file", out.exists())
    }

    // ---- WAV wrapper header ----

    @Test fun wavHeaderDescribesPcm() {
        val h = NativePcmDataSource.wavHeader(88_200, 2, 1_000_000)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(36 + 1_000_000, b.getInt(4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(88_200, b.getInt(24))
        assertEquals(88_200 * 4, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(h, 36, 4))
        assertEquals(1_000_000, b.getInt(40))
    }

    // ---- native AVIO adapter ----

    @Test fun nativeIoSeekSemantics() {
        val fs = InMemoryFileSystem("n").apply { put("/x.bin", ByteArray(100) { it.toByte() }) }
        val io = NativeIo(com.wing.folderplayer.data.source.ReopeningRandomAccessReader(fs, "/x.bin"))
        assertEquals(100L, io.seek(0, 0x10000))
        assertEquals(10L, io.seek(10, 0))
        val buf = ByteArray(5)
        assertEquals(5, io.read(buf, 5))
        assertEquals(10, buf[0].toInt())
        assertEquals(20L, io.seek(5, 1))
        assertEquals(90L, io.seek(-10, 2))
        assertEquals(-1L, io.seek(-1, 0))
    }
}
