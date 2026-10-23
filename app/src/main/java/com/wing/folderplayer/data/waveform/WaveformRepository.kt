package com.wing.folderplayer.data.waveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativeDecoderException
import com.wing.folderplayer.playback.NativeIo
import com.wing.folderplayer.playback.NativePcmDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.security.MessageDigest

/** Waveform on the seek bar: on by default, files longer than [maxMinutes] (0 = no limit) are not measured. */
class WaveformSettings(context: Context) {
    private val prefs = context.getSharedPreferences("waveform_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit { putBoolean("enabled", v) }; revision.value++ }

    var maxMinutes: Int
        get() = prefs.getInt("max_minutes", DEFAULT_MAX_MINUTES).takeIf { it in MAX_MINUTES } ?: DEFAULT_MAX_MINUTES
        set(v) { prefs.edit { putInt("max_minutes", v) }; revision.value++ }

    val limitMs: Long get() = maxMinutes * 60_000L

    companion object {
        val MAX_MINUTES = listOf(30, 60, 120, 180, 0)
        const val DEFAULT_MAX_MINUTES = 120
        private val revision = MutableStateFlow(0)
        /** Changes whenever a waveform setting changes, so the player can measure (or hide) the current track again. */
        val changes: StateFlow<Int> = revision.asStateFlow()
    }
}

/**
 * Measures the loudness envelope of a whole audio file for the seek bar. The file is decoded once in the background
 * (Media3's own formats through MediaExtractor + MediaCodec, WMA / APE / DSD / ALAC through the bundled FFmpeg decoder)
 * and the result is kept on disk, keyed by the file and its size and modification time. Files on network sources are
 * measured only on Wi-Fi; their reads show in the network account like any other source read.
 */
class WaveformRepository private constructor(private val context: Context) {
    val settings = WaveformSettings(context)
    private val dir = File(context.cacheDir, CACHE_DIR)

    // One file at a time: the next track's analysis waits for the previous one to notice its cancellation.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private class TooLong : Exception()

    /**
     * The waveform of [ref]: null first, then partial results while the file is measured, then the whole one. Stays
     * null when the setting is off, the file is too long, on mobile data for a network file, or it cannot be decoded.
     */
    fun waveform(ref: SourceRef): Flow<Waveform?> = flow {
        emit(null)
        val ext = SourcePath.extension(ref.name)
        if (!settings.enabled || ext !in MediaTypes.AUDIO) return@flow
        val cfg = SourceRegistry.get(ref.sourceId) ?: return@flow
        val fs = SourceRegistry.fileSystem(ref)
        val entry = try { fs.stat(ref.path) } catch (e: IOException) { null }
        val file = File(dir, key(ref, entry?.size, entry?.lastModified) + ".wf")
        readCache(file)?.let { emit(it); return@flow }
        if (cfg.isNetwork && !ThumbnailRepository.get(context).isOnWifi()) return@flow
        val limitMs = settings.limitMs
        val started = SystemClock.elapsedRealtime()
        val result = try {
            val native = if (ext in MediaTypes.EXTRA_AUDIO || ext == "m4a") analyseNative(ref, fs, ext, limitMs) { emit(it) } else null
            native ?: analyseMediaCodec(ref, fs, limitMs) { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooLong) {
            Log.i(TAG, "${ref.name}: longer than $limitMs ms, not measured")
            null
        } catch (e: Exception) {
            Log.w(TAG, "${ref.name}: cannot measure: $e")
            null
        } ?: return@flow
        Log.i(TAG, "${ref.name}: ${result.levels.size} bins of ${result.binMs} ms in ${SystemClock.elapsedRealtime() - started} ms")
        emit(result)
        writeCache(file, result)
    }.flowOn(dispatcher)

    /** WMA / APE / DSF / DFF / ALAC. Null when the native decoder is missing or a .m4a holds AAC (MediaCodec path). */
    private suspend fun analyseNative(ref: SourceRef, fs: SourceFileSystem, ext: String, limitMs: Long, progress: suspend (Waveform) -> Unit): Waveform? {
        if (!NativeDecoder.isAvailable()) return null
        val reader = fs.openRandomAccess(ref.path)
        var handle = 0L
        try {
            handle = try {
                NativeDecoder.nativeOpen(NativeIo(reader), ext, NativePcmDataSource.MAX_OUTPUT_RATE)
            } catch (e: NativeDecoderException) {
                if (ext == "m4a" && e.unsupported) return null
                throw e
            }
            val info = NativeDecoder.nativeInfo(handle)
            val durationMs = if (info[2] > 0) info[2] / 1000 else -1L
            checkLimit(durationMs, limitMs)
            val acc = LevelAccumulator(info[0].toInt(), info[1].toInt(), Waveform.binMsFor(durationMs))
            val buffer = ByteArray(64 * 1024)
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = NativeDecoder.nativeRead(handle, buffer, 0, buffer.size)
                if (n == -1) break
                if (n < 0) throw IOException("native decode failed ($n)")
                acc.addPcm16(buffer, 0, n)
                checkDecodedLimit(acc, durationMs, limitMs)
                if (SystemClock.elapsedRealtime() - lastProgress >= PROGRESS_MS) {
                    progress(Waveform(acc.binMs, acc.levels(), durationMs, complete = false))
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            return Waveform(acc.binMs, acc.levels(final = true), durationMs, complete = true)
        } finally {
            if (handle != 0L) NativeDecoder.nativeClose(handle)
            runCatching { reader.close() }
        }
    }

    private suspend fun analyseMediaCodec(ref: SourceRef, fs: SourceFileSystem, limitMs: Long, progress: suspend (Waveform) -> Unit): Waveform {
        val reader = fs.openRandomAccess(ref.path)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
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
            val format = extractor.getTrackFormat(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1L
            checkLimit(durationMs, limitMs)
            val binMs = Waveform.binMsFor(durationMs)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var acc: LevelAccumulator? = null
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if ((acc?.binCount ?: 0) == 0) acc = LevelAccumulator(rate, channels, binMs)
                } else if (o >= 0) {
                    idle = 0
                    val out = c.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        val a = acc ?: LevelAccumulator(rate, channels, binMs).also { acc = it }
                        if (isFloat) a.addFloat(out.asFloatBuffer()) else a.addPcm16(out.asShortBuffer())
                        checkDecodedLimit(a, durationMs, limitMs)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && ++idle > MAX_IDLE_POLLS) {
                    break // the decoder never signalled the end: keep what it gave
                }
                val a = acc
                if (a != null && SystemClock.elapsedRealtime() - lastProgress >= PROGRESS_MS) {
                    progress(Waveform(a.binMs, a.levels(), durationMs, complete = false))
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            val levels = acc?.levels(final = true) ?: throw IOException("no audio decoded")
            return Waveform(binMs, levels, durationMs, complete = true)
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
            runCatching { reader.close() }
        }
    }

    private fun checkLimit(durationMs: Long, limitMs: Long) {
        if (limitMs > 0 && durationMs > limitMs) throw TooLong()
    }

    /** Files whose duration is not known up front stop once the decoded part passes the limit. */
    private fun checkDecodedLimit(acc: LevelAccumulator, durationMs: Long, limitMs: Long) {
        if (durationMs <= 0 && limitMs > 0 && acc.binCount.toLong() * acc.binMs > limitMs) throw TooLong()
    }

    private fun readCache(file: File): Waveform? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { Waveform.read(it) }.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    private fun writeCache(file: File, waveform: Waveform) {
        try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { waveform.write(it) }
            if (!tmp.renameTo(file)) tmp.delete()
            val files = dir.listFiles { f -> f.name.endsWith(".wf") } ?: return
            if (files.size > MAX_CACHED) files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED).forEach { it.delete() }
        } catch (e: IOException) {
            Log.w(TAG, "cannot keep waveform: $e")
        }
    }

    private fun key(ref: SourceRef, size: Long?, modified: Long?): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${ref.toUriString()}|$size|$modified".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "Waveform"
        const val CACHE_DIR = "waveforms"
        private const val PROGRESS_MS = 400L
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_POLLS = 300
        private const val MAX_CACHED = 500

        @Volatile private var instance: WaveformRepository? = null
        fun get(context: Context): WaveformRepository =
            instance ?: synchronized(this) { instance ?: WaveformRepository(context.applicationContext).also { instance = it } }
    }
}
