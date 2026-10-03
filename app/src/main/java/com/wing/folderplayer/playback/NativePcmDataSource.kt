package com.wing.folderplayer.playback

import android.net.Uri
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import java.io.IOException

/** Byte source for the native demuxer (AVIOContext callbacks). Called from the decoding thread only. */
@Keep
class NativeIo(private val reader: RandomAccessReader) {
    private var position = 0L

    @Keep
    fun read(buffer: ByteArray, length: Int): Int {
        val n = reader.read(position, buffer, 0, length)
        if (n > 0) position += n
        return n
    }

    /** whence: 0=SET 1=CUR 2=END, 0x10000=AVSEEK_SIZE. Returns the new position, the size, or -1. */
    @Keep
    fun seek(offset: Long, whence: Int): Long {
        if (whence == 0x10000) return reader.size
        val target = when (whence) {
            0 -> offset
            1 -> position + offset
            2 -> reader.size + offset
            else -> return -1
        }
        if (target < 0) return -1
        position = target
        return position
    }
}

class NativeDecoderException(val code: Int, message: String) : IOException(message) {
    val unsupported get() = code == NativeDecoder.ERR_UNSUPPORTED
}

/**
 * JNI bridge to libfpnative (FFmpeg libavformat/libavcodec/libswresample, built from source by
 * native/build-ffmpeg.sh). If the library is missing or fails to load, [isAvailable] is false and these formats are
 * NOT reported as supported.
 */
object NativeDecoder {
    const val ERR_UNSUPPORTED = -2
    const val ERR_CORRUPT = -3
    const val ERR_IO = -4

    @Volatile private var loadError: Throwable? = null

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("fpnative")
            nativeVersion().isNotEmpty()
        } catch (t: Throwable) {
            loadError = t
            android.util.Log.e("NativeDecoder", "libfpnative not available: ${t.message}")
            false
        }
    }

    fun isAvailable(): Boolean = loaded
    fun loadFailure(): Throwable? = loadError.also { loaded }
    fun version(): String = if (loaded) nativeVersion() else ""

    @JvmStatic external fun nativeVersion(): String
    /** Opens and probes; throws [NativeDecoderException] (code ERR_UNSUPPORTED for e.g. DST-compressed DFF). */
    @JvmStatic external fun nativeOpen(io: NativeIo, formatHint: String, maxOutputRate: Int): Long
    /** [outSampleRate, channels, durationUs, totalFrames(-1 unknown), isAlacOrDecodable(1/0)] */
    @JvmStatic external fun nativeInfo(handle: Long): LongArray
    @JvmStatic external fun nativeCodecName(handle: Long): String
    /** Seeks to an exact output frame (decode-and-discard after the container seek). */
    @JvmStatic external fun nativeSeekFrame(handle: Long, frame: Long): Int
    /** Interleaved signed 16-bit PCM. Returns bytes written, -1 at end of stream, or a negative error code < -1. */
    @JvmStatic external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeClose(handle: Long)
}

/**
 * Presents a WMA/APE/DSF/DFF (and ALAC-in-M4A) file as a 16-bit PCM WAV stream so Media3's WavExtractor plays and
 * seeks it: byte offset → output frame → exact native seek. DSD is converted to PCM (no DoP/native DSD). A .m4a that
 * does not contain ALAC is passed through unchanged to the regular MP4 path.
 */
@OptIn(UnstableApi::class)
class NativePcmDataSource : BaseDataSource(true) {
    private var reader: RandomAccessReader? = null
    private var handle = 0L
    private var passthrough: SourceDataSource? = null
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var end = 0L
    private var blockAlign = 4
    private var skipInFrame = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ref = SourceUris.parse(dataSpec.uri.toString()) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val ext = SourcePath.extension(ref.name)
        transferInitializing(dataSpec)
        try {
            val r = SourceRegistry.fileSystem(ref).openRandomAccess(ref.path)
            reader = r
            handle = NativeDecoder.nativeOpen(NativeIo(r), ext, MAX_OUTPUT_RATE)
        } catch (e: NativeDecoderException) {
            closeNative()
            if (ext == "m4a" && e.unsupported) return openPassthrough(dataSpec)
            throw DataSourceException(e, if (e.unsupported) PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED else PlaybackException.ERROR_CODE_DECODING_FAILED)
        } catch (e: IOException) {
            closeNative()
            throw SourceErrors.toDataSourceException(e)
        }
        val info = NativeDecoder.nativeInfo(handle)
        val rate = info[0].toInt()
        val channels = info[1].toInt()
        val totalFrames = if (info[3] > 0) info[3] else info[2] * rate / 1_000_000
        blockAlign = channels * 2
        val dataSize = minOf(totalFrames * blockAlign, MAX_WAV_DATA)
        header = wavHeader(rate, channels, dataSize)
        end = header.size + dataSize
        position = dataSpec.position
        if (position > end) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        if (position > header.size) {
            val pcmOffset = position - header.size
            val frame = pcmOffset / blockAlign
            skipInFrame = (pcmOffset % blockAlign).toInt()
            val rc = NativeDecoder.nativeSeekFrame(handle, frame)
            if (rc < 0) throw DataSourceException(NativeDecoderException(rc, "seek failed"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        opened = true
        transferStarted(dataSpec)
        val remaining = end - position
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
    }

    private fun openPassthrough(dataSpec: DataSpec): Long {
        val p = SourceDataSource()
        passthrough = p
        return p.open(dataSpec)
    }

    private val scratch = ByteArray(8)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        passthrough?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        if (position < header.size) {
            val n = minOf(length.toLong(), header.size - position).toInt()
            System.arraycopy(header, position.toInt(), buffer, offset, n)
            position += n
            bytesTransferred(n)
            return n
        }
        while (skipInFrame > 0) {
            val n = NativeDecoder.nativeRead(handle, scratch, 0, skipInFrame)
            if (n < 0) return finishOrThrow(n)
            skipInFrame -= n
        }
        val want = minOf(length.toLong(), end - position).toInt()
        val n = NativeDecoder.nativeRead(handle, buffer, offset, want)
        if (n < 0) return finishOrThrow(n)
        position += n
        bytesTransferred(n)
        return n
    }

    private fun finishOrThrow(code: Int): Int {
        if (code == -1) {
            position = end
            return C.RESULT_END_OF_INPUT
        }
        val code2 = if (code == NativeDecoder.ERR_IO) PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED else PlaybackException.ERROR_CODE_DECODING_FAILED
        throw DataSourceException(NativeDecoderException(code, "native decode failed ($code)"), code2)
    }

    override fun getUri(): Uri? = uri

    private fun closeNative() {
        if (handle != 0L) { NativeDecoder.nativeClose(handle); handle = 0L }
        runCatching { reader?.close() }
        reader = null
    }

    override fun close() {
        uri = null
        try {
            passthrough?.close()
            passthrough = null
            closeNative()
        } finally {
            if (opened) { opened = false; transferEnded() }
        }
    }

    companion object {
        /** DSD64 → 88.2 kHz; DSD128+ is decimated further so AudioTrack can play it everywhere. */
        const val MAX_OUTPUT_RATE = 96_000
        private const val MAX_WAV_DATA = 0xFFFFFFFFL - 36

        fun wavHeader(rate: Int, channels: Int, dataSize: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataSize).toInt())
            b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
                .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            b.put("data".toByteArray()).putInt(dataSize.toInt())
            return b.array()
        }
    }
}
