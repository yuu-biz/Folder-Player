package com.wing.folderplayer

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceInput
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativePcmDataSource
import com.wing.folderplayer.playback.RetryPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * I/O failures while FFmpeg opens or reads a network file keep their meaning: a dropped connection is a retryable
 * network error (not a "decoding failed"), an authentication error stays a non-retryable permission error.
 */
@RunWith(AndroidJUnit4::class)
class NativeIoErrorTest {
    private lateinit var wma: ByteArray
    @Volatile private var failAt = 0L
    @Volatile private var failure: () -> IOException = { java.net.SocketException("connection reset") }
    private val cfg = SourceConfig(id = "flaky-net", name = "flaky", type = SourceType.SMB, host = "flaky.invalid", share = "s", username = "u")

    private inner class FlakyFs : SourceFileSystem {
        override val config = cfg
        override val capabilities = emptySet<com.wing.folderplayer.data.source.SourceCapability>()
        override fun list(path: String) = listOf(MusicFile("sample.wma", "/sample.wma", false, wma.size.toLong(), 0, cfg.id))
        override fun stat(path: String) = list("/").first()
        override fun openRead(path: String, offset: Long, length: Long): SourceInput = throw failure()
        override fun openRandomAccess(path: String) = object : RandomAccessReader {
            override val size = wma.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position + len > failAt) throw failure()
                if (position >= size) return -1
                val n = minOf(len.toLong(), size - position).toInt()
                System.arraycopy(wma, position.toInt(), buffer, offset, n)
                return n
            }
            override fun close() {}
        }
        override fun close() {}
    }

    @Before fun setUp() {
        assumeTrue("native decoder present", NativeDecoder.isAvailable())
        wma = InstrumentationRegistry.getInstrumentation().context.assets.open("Formats/sample.wma").use { it.readBytes() }
        SourceRegistry.init(Fx.ctx)
        SourceRegistry.upsert(cfg, "p")
        SourceRegistry.fileSystemFactory = { c -> if (c.id == cfg.id) FlakyFs() else null }
    }

    @After fun tearDown() {
        SourceRegistry.fileSystemFactory = null
        SourceRegistry.remove(cfg.id)
    }

    private val uri: Uri get() = Uri.parse(SourceRef(cfg.id, "/sample.wma").toUriString())

    private fun openError(): DataSourceException {
        val ds = NativePcmDataSource()
        try {
            ds.open(DataSpec(uri))
            throw AssertionError("open succeeded")
        } catch (e: DataSourceException) {
            return e
        } finally { ds.close() }
    }

    private fun readError(): DataSourceException {
        val ds = NativePcmDataSource()
        try {
            ds.open(DataSpec(uri))
            val buf = ByteArray(64 * 1024)
            while (true) { if (ds.read(buf, 0, buf.size) == androidx.media3.common.C.RESULT_END_OF_INPUT) throw AssertionError("read to the end") }
        } catch (e: DataSourceException) {
            return e
        } finally { ds.close() }
    }

    @Test fun connectionLostWhileOpeningIsARetryableNetworkError() {
        failAt = 0
        val e = openError()
        Fx.log("open + network failure: reason=${e.reason} ${e.cause}")
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
        assertTrue(RetryPolicy().isRetryable(e.reason))
    }

    @Test fun authenticationFailureWhileOpeningIsNotRetried() {
        failAt = 0
        failure = { SourceException.AuthFailed() }
        val e = openError()
        Fx.log("open + auth failure: reason=${e.reason} ${e.cause}")
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, e.reason)
        assertFalse(RetryPolicy().isRetryable(e.reason))
    }

    @Test fun connectionLostWhileReadingIsARetryableNetworkError() {
        failAt = wma.size * 6L / 10
        val e = readError()
        Fx.log("read + network failure: reason=${e.reason} ${e.cause}")
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
    }

    @Test fun authenticationFailureWhileReadingIsNotRetried() {
        failAt = wma.size * 6L / 10
        failure = { SourceException.AuthFailed() }
        val e = readError()
        Fx.log("read + auth failure: reason=${e.reason} ${e.cause}")
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, e.reason)
    }
}
