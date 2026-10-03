package com.wing.folderplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceInput
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import java.io.IOException

/** Maps source failures to Media3 error codes so the service can tell retryable network errors from auth/not-found. */
@OptIn(UnstableApi::class)
object SourceErrors {
    fun toDataSourceException(e: IOException): DataSourceException = when (e) {
        is DataSourceException -> e
        is SourceException.NotFound -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        is SourceException.AuthFailed, is SourceException.PermissionDenied ->
            DataSourceException(e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        is SourceException.Unreachable, is SourceException.PrematureEof, is java.net.SocketException, is java.net.SocketTimeoutException ->
            DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        is SourceException.TlsFailure -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        is java.io.EOFException -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        // Not retryable: the server cannot start a transfer at an offset (FTP without REST).
        is SourceException.SeekUnsupported -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        else -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }
}

/** Reads `fpsrc://` URIs through the source's [com.wing.folderplayer.data.source.SourceFileSystem]. */
@OptIn(UnstableApi::class)
class SourceDataSource : BaseDataSource(/* isNetwork= */ true) {
    private var input: SourceInput? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ref = SourceUris.parse(dataSpec.uri.toString())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        transferInitializing(dataSpec)
        try {
            val fs = SourceRegistry.fileSystem(ref)
            val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.length
            val stream = fs.openRead(ref.path, dataSpec.position, requested)
            input = stream
            bytesRemaining = if (stream.length < 0) C.LENGTH_UNSET.toLong() else stream.length
        } catch (e: java.io.EOFException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } catch (e: IOException) {
            throw SourceErrors.toDataSourceException(e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            input!!.read(buffer, offset, toRead)
        } catch (e: IOException) {
            throw SourceErrors.toDataSourceException(e)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            input?.close()
        } catch (e: IOException) {
            // Closing a half-read FTP/HTTP stream may fail; the data was not needed anymore.
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * Routes `fpsrc://` to [SourceDataSource] (or the native PCM decoder for WMA/APE/DSF/DFF/ALAC) and everything else
 * (file://, content://, http(s):// from older state) to Media3's [DefaultDataSource].
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val context: Context, private val fallbackFactory: DataSource.Factory) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val raw = dataSpec.uri.toString()
        val ds: DataSource = if (SourceUris.isSourceUri(raw)) {
            val ext = SourceUris.parse(raw)?.name?.let { com.wing.folderplayer.data.source.SourcePath.extension(it) }
            if (ext != null && NativeDecoderRouting.handles(ext)) NativeDecoderRouting.create(context) else SourceDataSource()
        } else fallbackFactory.createDataSource()
        listeners.forEach { ds.addTransferListener(it) }
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() {
        try { current?.close() } finally { current = null }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        private val fallback = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = RoutingDataSource(context.applicationContext, fallback)
    }
}

/** Hook for the FFmpeg/DSD decoder; see [NativePcmDataSource]. */
object NativeDecoderRouting {
    fun handles(ext: String): Boolean = (ext in MediaTypes.EXTRA_AUDIO || ext == "m4a") && NativeDecoder.isAvailable()
    @OptIn(UnstableApi::class)
    fun create(context: Context): DataSource = NativePcmDataSource()
}
