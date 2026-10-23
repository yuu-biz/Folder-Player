package com.wing.folderplayer.data.waveform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativeDecoderException
import com.wing.folderplayer.playback.NativeIo
import com.wing.folderplayer.playback.NativePcmDataSource
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Decoded audio of one file as interleaved samples in -1..1, read in chunks; [seek] moves near a time. */
internal abstract class PcmReader : Closeable {
    /** -1 when the file does not say. */
    abstract val durationMs: Long
    /** Of the decoded audio (known once [read] has returned samples; HE-AAC decodes at twice the coded rate). */
    abstract val sampleRate: Int
    abstract val channels: Int

    /** Up to `out.size` samples; returns how many, or -1 at the end (also when the decoder stops giving any). */
    abstract fun read(out: FloatArray): Int

    /** Decoding continues near [ms] (at or before it, at the nearest point the format can start from). */
    abstract fun seek(ms: Long)

    /**
     * Decoding continues where the format can start at or before [ms] without reading up to it (an MP3 TOC entry, a
     * FLAC seek point); [landedMs] tells where after the next [read]. By default the same as [seek].
     */
    open fun seekNear(ms: Long) = seek(ms)

    /** Where decoding continued after [seekNear], -1 when not known (then take it as the requested time). */
    open val landedMs: Long get() = -1

    /**
     * Set when a [seek] had to go back to the start of the file (a container without an index): every later sample
     * then costs decoding everything before it, so the file is better read once from start to end.
     */
    var seekRestarts = false
        protected set

    /**
     * While sampling only a little is needed after each seek: a decoder that is handed every free input buffer at once
     * decodes far more than that before the next seek throws it away.
     */
    open var sampling = false

    /** Reads from the source so far (each one a round trip on a network source) and the bytes they returned. */
    abstract val io: CountingReader
}

/** Counts the reads made through it (each one a request on a network source). */
internal class CountingReader(private val inner: RandomAccessReader) : RandomAccessReader {
    var reads = 0L
        private set
    var bytes = 0L
        private set
    /** Reads are cut to this many bytes (the decoder asks for 64 KB; a window of an MP3 needs a few KB). */
    var maxRead = Int.MAX_VALUE
    override val size: Long get() = inner.size

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        reads++
        return inner.read(position, buffer, offset, minOf(len, maxRead)).also { if (it > 0) bytes += it }
    }

    override fun close() = inner.close()
}

/**
 * Every format through the bundled FFmpeg decoder, which reads 64 KB at a time and seeks in microseconds; MediaExtractor
 * + MediaCodec only when the native library is missing or does not take the file (or [native] is false, for
 * comparison). MediaCodec costs a round trip through the media service per buffer and a flush per seek (slow on some
 * phones), and MediaExtractor reads a few KB at a time, each read a request to a NAS.
 */
internal fun openPcmReader(open: () -> RandomAccessReader, ext: String, native: Boolean = true): PcmReader {
    if (native && NativeDecoder.isAvailable()) {
        try {
            return NativePcmReader.open(CountingReader(open()), ext)
        } catch (e: NativeDecoderException) {
            if (e.code == NativeDecoder.ERR_IO) throw e
            Log.i("Waveform", "native decoder declined .$ext ($e): MediaCodec")
        }
    }
    return CodecPcmReader(CountingReader(open()))
}

private class NativePcmReader(private val handle: Long, override val io: CountingReader) : PcmReader() {
    private val info = NativeDecoder.nativeInfo(handle)
    override val sampleRate = info[0].toInt()
    override val channels = info[1].toInt()
    override val durationMs = if (info[2] > 0) info[2] / 1000 else -1L
    private var bytes = ByteArray(0)

    override fun read(out: FloatArray): Int {
        val want = out.size * 2
        if (bytes.size < want) bytes = ByteArray(want)
        val n = NativeDecoder.nativeRead(handle, bytes, 0, want)
        if (n == -1) return -1
        if (n < 0) throw IOException("native decode failed ($n)")
        return pcm16ToFloats(bytes, n, out)
    }

    // Each window needs a few KB after its seek: a smaller read takes less time from a NAS (the round trip is the same).
    override var sampling = false
        set(v) { field = v; io.maxRead = if (v) SAMPLING_READ else Int.MAX_VALUE }

    override fun seek(ms: Long) = checkSeek(NativeDecoder.nativeSeekFrame(handle, ms * sampleRate / 1000))

    override fun seekNear(ms: Long) = checkSeek(NativeDecoder.nativeSeekNear(handle, ms * sampleRate / 1000))

    override val landedMs: Long get() = NativeDecoder.nativeLandedUs(handle).let { if (it < 0) -1 else it / 1000 }

    private fun checkSeek(rc: Int) {
        if (rc < 0) throw IOException("native seek failed ($rc)")
        if (rc == 1) seekRestarts = true
    }

    override fun close() {
        NativeDecoder.nativeClose(handle)
        runCatching { io.close() }
    }

    companion object {
        const val SAMPLING_READ = 32 * 1024

        /** Throws [NativeDecoderException] (reader closed) for a file this build cannot decode. */
        fun open(reader: CountingReader, ext: String): NativePcmReader {
            val handle = try {
                NativeDecoder.nativeOpenForAnalysis(NativeIo(reader), ext, NativePcmDataSource.MAX_OUTPUT_RATE)
            } catch (e: Throwable) {
                runCatching { reader.close() }
                throw e
            }
            return NativePcmReader(handle, reader)
        }
    }
}

/**
 * MediaExtractor + MediaCodec in synchronous mode. Every free input buffer is filled and every ready output taken
 * without waiting; it waits briefly only when neither side moved (waiting on each call held decoding to a few times
 * real time).
 */
private class CodecPcmReader(override val io: CountingReader) : PcmReader() {
    private val reader: RandomAccessReader get() = io
    private val extractor = MediaExtractor()
    private val format: MediaFormat
    private val codec: MediaCodec
    override val durationMs: Long
    override var sampleRate: Int
    override var channels: Int
    private var isFloat = false
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var producedOutput = false
    private var inputDone = false
    private var outputDone = false
    private var inFlight = 0
    private var pendingIndex = -1
    private var pendingShorts: ShortBuffer? = null
    private var pendingFloats: FloatBuffer? = null

    init {
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    if (size == 0) 0 else reader.read(position, buffer, offset, size)
                override fun getSize(): Long = reader.size
                override fun close() {}
            })
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            format = extractor.getTrackFormat(track)
            durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1L
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
        } catch (t: Throwable) {
            runCatching { extractor.release() }
            runCatching { reader.close() }
            throw t
        }
    }

    override fun read(out: FloatArray): Int {
        if (!started) { codec.start(); started = true }
        var idle = 0
        while (true) {
            val n = takePending(out)
            if (n > 0) return n
            if (outputDone) return -1
            var fed = false
            while (!inputDone && !(sampling && inFlight >= SAMPLING_IN_FLIGHT && idle == 0)) { // a decoder that wants more first gets it
                val i = codec.dequeueInputBuffer(0)
                if (i < 0) break
                val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                    inFlight++
                }
                fed = true
            }
            val o = codec.dequeueOutputBuffer(info, if (fed) 0 else WAIT_US)
            when {
                o >= 0 -> {
                    idle = 0
                    if (inFlight > 0) inFlight--
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val b = codec.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        b.order(ByteOrder.nativeOrder())
                        if (isFloat) pendingFloats = b.asFloatBuffer() else pendingShorts = b.asShortBuffer()
                        pendingIndex = o
                        producedOutput = true
                    } else {
                        codec.releaseOutputBuffer(o, false)
                    }
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    producedOutput = true
                }
                !fed && ++idle > MAX_IDLE_POLLS -> return -1 // the decoder gives nothing more: keep what it gave
            }
        }
    }

    private fun takePending(out: FloatArray): Int {
        var n = 0
        pendingShorts?.let { s -> while (n < out.size && s.hasRemaining()) out[n++] = s.get() / 32768f }
        pendingFloats?.let { f -> while (n < out.size && f.hasRemaining()) out[n++] = f.get() }
        if (pendingIndex >= 0 && pendingShorts?.hasRemaining() != true && pendingFloats?.hasRemaining() != true) releasePending()
        return n
    }

    private fun releasePending() {
        if (pendingIndex >= 0) codec.releaseOutputBuffer(pendingIndex, false)
        pendingIndex = -1
        pendingShorts = null
        pendingFloats = null
    }

    override fun seek(ms: Long) {
        // An output buffer is given back before the flush (its index is no longer valid after it).
        releasePending()
        extractor.seekTo(ms * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (started) {
            if (producedOutput) {
                codec.flush()
            } else {
                // Flushed before its first output a decoder may need its codec-specific data again: start it afresh.
                codec.stop()
                codec.configure(format, null, null, 0)
                started = false
            }
        }
        inputDone = false
        outputDone = false
        inFlight = 0
    }

    override fun close() {
        runCatching { releasePending() }
        runCatching { if (started) codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { reader.close() }
    }

    private companion object {
        const val WAIT_US = 5_000L
        /** Input buffers handed to the decoder ahead of its output while sampling. */
        const val SAMPLING_IN_FLIGHT = 2
        /** About a second without any output: the decoder is done (some never flag the end). */
        const val MAX_IDLE_POLLS = 200
    }
}
