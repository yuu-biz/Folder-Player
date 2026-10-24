package com.wing.folderplayer.data.waveform

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.TreeMap
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
 * windows spread over it, a short one from start to end (through the bundled FFmpeg decoder, see [openPcmReader]). The
 * result is kept on disk, keyed by the file and its size and modification time. Files on network sources are measured
 * only on Wi-Fi; their reads show in the network account like any other source read.
 */
class WaveformRepository private constructor(private val context: Context) {
    val settings = WaveformSettings(context)
    private val dir = File(context.cacheDir, CACHE_DIR)

    // One file at a time: the next track's analysis waits for the previous one to notice its cancellation. Watchers run on
    // IO instead: a measurement holds this thread, and a watcher must still reach the cache or stop that measurement.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val measurements = LatestJob<String, Waveform>(CoroutineScope(SupervisorJob() + dispatcher))

    private class TooLong : Exception()

    /**
     * The waveform of [ref]: null first, then the waveform, once (a slow source may show a coarse one first and the
     * full one after). Stays null when the setting is off, the file is too long, on mobile data for a network file, or
     * it cannot be decoded.
     *
     * A measurement, once started, runs to its end and keeps its result even when nobody collects any more; collecting
     * the same file again joins it. Asking for another file that needs measuring stops it.
     */
    fun waveform(ref: SourceRef): Flow<Waveform?> = flow {
        emit(null)
        val ext = SourcePath.extension(ref.name)
        if (!settings.enabled || ext !in MediaTypes.AUDIO) return@flow
        val cfg = SourceRegistry.get(ref.sourceId) ?: return@flow
        val fs = SourceRegistry.fileSystem(ref)
        val entry = try { fs.stat(ref.path) } catch (e: IOException) { null }
        val key = key(ref, entry?.size, entry?.lastModified)
        val file = File(dir, "$key.wf")
        readCache(file)?.let { emit(it); return@flow }
        if (cfg.isNetwork && !ThumbnailRepository.get(context).isOnWifi()) return@flow
        emitAll(measurements.watch(key) { coarse ->
            try {
                measureFile({ fs.openRandomAccess(ref.path) }, ext, settings.limitMs, network = cfg.isNetwork, coarse = coarse)
                    .also { writeCache(file, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooLong) {
                Log.i(TAG, "${ref.name}: longer than ${settings.limitMs} ms, not measured")
                null
            } catch (e: Exception) {
                Log.w(TAG, "${ref.name}: cannot measure: $e")
                null
            }
        })
    }.flowOn(Dispatchers.IO)

    /**
     * Measures the file [open] reads ([native] false: through MediaCodec, for comparison in tests). A local file is
     * decoded from start to end, as Namida does: one RMS level per bin of all of its audio. A long one is sampled
     * instead when that would take longer than [LOCAL_FULL_BUDGET_MS] (judged after [PROBE_MS] of decoding), and a long
     * [network] file always is: reading it whole from a NAS takes too long. [coarse] gets a first, coarser waveform
     * when sampling is slow.
     */
    internal suspend fun measureFile(
        open: () -> RandomAccessReader, ext: String, limitMs: Long, network: Boolean = false, native: Boolean = true,
        coarse: suspend (Waveform) -> Unit = {},
    ): Waveform {
        val started = SystemClock.elapsedRealtime()
        return openPcmReader(open, ext, native).use { r ->
            checkLimit(r.durationMs, limitMs)
            val long = r.durationMs > FULL_DECODE_MAX_MS
            val w = (if (long && !network) sequential(r, limitMs, LOCAL_FULL_BUDGET_MS) else null)
                ?: (if (long) sampled(r, coarse) else null)
                ?: run {
                    if (long) {
                        Log.i(TAG, "seeking reads this file from the start: decoding it once instead")
                        r.sampling = false
                        r.seek(0)
                    }
                    sequential(r, limitMs, budgetMs = 0)!!
                }
            Log.i(TAG, ".$ext ${r.javaClass.simpleName}: ${w.levels.size} bins of ${w.binMs} ms in " +
                "${SystemClock.elapsedRealtime() - started} ms, ${r.io.reads} reads, ${r.io.bytes / 1024} KB of ${r.io.size / 1024} KB")
            w
        }
    }

    /**
     * The whole file from its start. With a [budgetMs] (> 0): null when, after [PROBE_MS], the rest would take longer
     * than that (a slow phone, a very long file).
     */
    private suspend fun sequential(r: PcmReader, limitMs: Long, budgetMs: Long): Waveform? {
        val binMs = Waveform.binMsFor(r.durationMs)
        val buffer = FloatArray(16 * 1024)
        var acc: LevelAccumulator? = null
        val started = SystemClock.elapsedRealtime()
        var probed = budgetMs <= 0 || r.durationMs <= 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = r.read(buffer)
            if (n < 0) break
            // Created at the first samples: only then are the decoded rate and channel count known.
            val a = acc ?: LevelAccumulator(r.sampleRate, r.channels, binMs).also { acc = it }
            a.add(buffer, n)
            checkDecodedLimit(a, r.durationMs, limitMs)
            if (!probed && SystemClock.elapsedRealtime() - started >= PROBE_MS) {
                probed = true
                val elapsed = SystemClock.elapsedRealtime() - started
                val decodedMs = a.binCount.toLong() * binMs
                val total = if (decodedMs > 0) elapsed * r.durationMs / decodedMs else Long.MAX_VALUE
                if (total > budgetMs) {
                    Log.i(TAG, "decoding it whole would take about $total ms: sampled instead")
                    return null
                }
            }
        }
        val levels = acc?.levels(final = true) ?: throw IOException("no audio decoded")
        return Waveform(binMs, levels, r.durationMs, complete = true)
    }

    /**
     * One short window per bin of a long file, in a coarse-to-fine order, each measured where the seek lands, at or
     * before its bin (an MP3 seeks to one of the 100 points of its table of contents, a FLAC file to its seek points):
     * decoding from there up to the bin would read seconds of audio per window, more than the whole file over all
     * windows. A window that lands where an earlier one did is not read again; the bins between the measured ones are
     * interpolated, so no waveform shown has gaps. When that is slow (a NAS answers each seek after a round trip), a
     * coarse waveform is shown after [COARSE_AFTER_MS] if the rest would take as long again, and after [BUDGET_MS]
     * the windows not measured yet are left out.
     *
     * Null when seeking goes back to the start of the file (no index); the caller then decodes the file once.
     */
    private suspend fun sampled(r: PcmReader, coarse: suspend (Waveform) -> Unit): Waveform? {
        val durationMs = r.durationMs
        val windows = Waveform.windowsFor(durationMs)
        val binMs = ((durationMs + windows - 1) / windows).toInt()
        val levels = FloatArray(windows) { Float.NaN }
        r.sampling = true
        val buffer = FloatArray(4096)
        val started = SystemClock.elapsedRealtime()
        // Where seeks landed -> the latest time requested that landed there (any time between lands there too).
        val landings = TreeMap<Long, Long>()
        var seekNs = 0L
        var tried = 0
        var same = 0
        var shown = false
        for (i in Waveform.sampleOrder(windows)) {
            currentCoroutineContext().ensureActive()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (tried >= COARSE_WINDOWS && elapsed > BUDGET_MS) break
            if (!shown && tried >= COARSE_WINDOWS && elapsed > COARSE_AFTER_MS) {
                // Only worth it when the rest takes a while yet: otherwise the full waveform replaces it at once.
                val left = elapsed * (windows - tried - same) / tried
                if (left > COARSE_AFTER_MS) coarse(Waveform(binMs, Waveform.filled(levels), durationMs, complete = true))
                shown = true
            }
            // The window is aimed at the middle of its bin; a little is decoded first and dropped (an MP3 frame needs
            // the frames before it, AAC starts with priming samples).
            val target = (i.toLong() * binMs + binMs / 2 - WINDOW_MS / 2 - SKIP_MS).coerceAtLeast(0)
            if (landings.floorEntry(target)?.let { it.value >= target } == true) { same++; continue }
            tried++
            val t0 = System.nanoTime()
            r.seekNear(target)
            seekNs += System.nanoTime() - t0
            if (r.seekRestarts) return null
            var bin = i
            var skip = -1L
            var need = 0L
            var sum = 0.0
            var count = 0L
            while (true) {
                val n = r.read(buffer)
                if (n < 0) break
                if (skip < 0) {
                    val at = r.landedMs.takeIf { it in 0..target } ?: target
                    landings[at] = maxOf(landings[at] ?: at, target)
                    bin = ((at + SKIP_MS + WINDOW_MS / 2) / binMs).toInt().coerceIn(0, windows - 1)
                    if (!levels[bin].isNaN()) { same++; break }
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
            if (!levels[bin].isNaN()) continue
            // Nothing decoded: the window is past the real end of the file (its length was an estimate).
            levels[bin] = if (count > 0) sqrt(sum / count).toFloat().coerceIn(0f, 1f) else 0f
        }
        Log.i(TAG, "sampled ${levels.count { !it.isNaN() }} of $windows windows ($same landed on one measured already) " +
            "in ${SystemClock.elapsedRealtime() - started} ms (seeking ${seekNs / 1_000_000} ms)")
        return Waveform(binMs, Waveform.filled(levels), durationMs, complete = true)
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
        /** Files up to this length are decoded whole (a second or two at most); longer ones may be sampled. */
        private const val FULL_DECODE_MAX_MS = 120_000L
        /** A long local file is decoded whole when that takes no longer than this, judged after [PROBE_MS]. */
        private const val LOCAL_FULL_BUDGET_MS = 5_000L
        private const val PROBE_MS = 700L
        private const val WINDOW_MS = 250L
        private const val SKIP_MS = 50L
        /** Windows measured before anything is shown or the budget can end the sampling. */
        private const val COARSE_WINDOWS = 64
        /** A sampling still running after this shows a coarse waveform (only a slow source takes that long). */
        private const val COARSE_AFTER_MS = 1_500L
        /** Sampling stops after this; the windows left out are interpolated. */
        private const val BUDGET_MS = 8_000L
        private const val MAX_CACHED = 500

        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: WaveformRepository? = null
        fun get(context: Context): WaveformRepository =
            instance ?: synchronized(this) { instance ?: WaveformRepository(context.applicationContext).also { instance = it } }
    }
}
