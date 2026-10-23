package com.wing.folderplayer.data.waveform

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
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
import java.security.MessageDigest
import kotlin.math.sqrt

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
 * Measures the loudness envelope of a whole audio file for the seek bar, in the background: a long file by decoding short
 * windows spread over it, a short one from start to end ([PcmReader]: MediaExtractor + MediaCodec, or the bundled FFmpeg
 * decoder for WMA / APE / DSD / ALAC). The result is kept on disk, keyed by the file and its size and modification time. Files on network sources are
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
            openPcmReader(fs, ref.path, ext).use { r -> measure(r, limitMs) { emit(it) } }
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

    /**
     * A long file whose reader can seek is sampled: one short window per bin, in a coarse-to-fine order, so the shape is
     * there after a second or two and only a fraction of the file is decoded (and read from a network source). Short
     * files, files of unknown length and files whose seeking turns out slow are decoded from start to end.
     */
    private suspend fun measure(r: PcmReader, limitMs: Long, progress: suspend (Waveform) -> Unit): Waveform {
        checkLimit(r.durationMs, limitMs)
        if (r.durationMs > FULL_DECODE_MAX_MS) {
            sampled(r, progress)?.let { return it }
            Log.i(TAG, "seeking is slow in this file: decoding it from the start")
            r.sampling = false
            r.seek(0)
        }
        return sequential(r, limitMs, progress)
    }

    private suspend fun sequential(r: PcmReader, limitMs: Long, progress: suspend (Waveform) -> Unit): Waveform {
        val binMs = Waveform.binMsFor(r.durationMs)
        val buffer = FloatArray(16 * 1024)
        var acc: LevelAccumulator? = null
        var lastProgress = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = r.read(buffer)
            if (n < 0) break
            // Created at the first samples: only then are the decoded rate and channel count known.
            val a = acc ?: LevelAccumulator(r.sampleRate, r.channels, binMs).also { acc = it }
            a.add(buffer, n)
            checkDecodedLimit(a, r.durationMs, limitMs)
            if (SystemClock.elapsedRealtime() - lastProgress >= PROGRESS_MS) {
                progress(Waveform(binMs, a.levels(), r.durationMs, complete = false))
                lastProgress = SystemClock.elapsedRealtime()
            }
        }
        val levels = acc?.levels(final = true) ?: throw IOException("no audio decoded")
        return Waveform(binMs, levels, r.durationMs, complete = true)
    }

    /**
     * Null when seeking is too slow to sample (a container without an index makes the decoder read from the start for
     * every window); the caller then decodes the file from the start.
     */
    private suspend fun sampled(r: PcmReader, progress: suspend (Waveform) -> Unit): Waveform? {
        val durationMs = r.durationMs
        val windows = Waveform.windowsFor(durationMs)
        val binMs = ((durationMs + windows - 1) / windows).toInt()
        val levels = FloatArray(windows) { Float.NaN }
        r.sampling = true
        val buffer = FloatArray(4096)
        val started = SystemClock.elapsedRealtime()
        var lastProgress = started
        var seekNs = 0L
        for ((done, i) in Waveform.sampleOrder(windows).withIndex()) {
            currentCoroutineContext().ensureActive()
            if (done == SLOW_CHECK_WINDOWS && SystemClock.elapsedRealtime() - started > SLOW_CHECK_WINDOWS * SLOW_WINDOW_MS) return null
            // The window sits in the middle of its bin; a little is decoded first and dropped (an MP3 frame needs the
            // frames before it, AAC starts with priming samples).
            val centre = i.toLong() * binMs + binMs / 2
            val t0 = System.nanoTime()
            r.seek((centre - WINDOW_MS / 2 - SKIP_MS).coerceAtLeast(0))
            seekNs += System.nanoTime() - t0
            var skip = -1L
            var need = 0L
            var sum = 0.0
            var count = 0L
            while (true) {
                val n = r.read(buffer)
                if (n < 0) break
                if (skip < 0) {
                    val perMs = r.sampleRate.toLong() * r.channels / 1000.0
                    skip = (perMs * SKIP_MS).toLong()
                    need = (perMs * WINDOW_MS).toLong()
                }
                for (k in 0 until n) {
                    if (skip > 0) { skip--; continue }
                    val v = buffer[k]
                    sum += v.toDouble() * v
                    count++
                }
                if (count >= need) break
            }
            if (count > 0) levels[i] = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
            if (SystemClock.elapsedRealtime() - lastProgress >= PROGRESS_MS) {
                progress(Waveform(binMs, levels.copyOf(), durationMs, complete = false))
                lastProgress = SystemClock.elapsedRealtime()
            }
        }
        Log.i(TAG, "sampled $windows windows in ${SystemClock.elapsedRealtime() - started} ms (seeking ${seekNs / 1_000_000} ms)")
        // A window past the real end of the file (its length was an estimate) or one the decoder gave nothing for.
        for (i in levels.indices) if (levels[i].isNaN()) levels[i] = 0f
        return Waveform(binMs, levels, durationMs, complete = true)
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
        private const val PROGRESS_MS = 300L
        /** Files up to this length are decoded whole (a few seconds at most); longer ones are sampled. */
        private const val FULL_DECODE_MAX_MS = 120_000L
        private const val WINDOW_MS = 250L
        private const val SKIP_MS = 50L
        /** Sampling is given up when the first windows took longer than this each (seeking reads from the start). */
        private const val SLOW_CHECK_WINDOWS = 8
        private const val SLOW_WINDOW_MS = 250L
        private const val MAX_CACHED = 500

        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: WaveformRepository? = null
        fun get(context: Context): WaveformRepository =
            instance ?: synchronized(this) { instance ?: WaveformRepository(context.applicationContext).also { instance = it } }
    }
}
