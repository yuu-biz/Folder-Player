package com.wing.folderplayer.data.metadata

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativeDecoderException
import com.wing.folderplayer.playback.NativeIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** "m:ss", or "h:mm:ss" from one hour on. */
object DurationFormat {
    fun format(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Track lengths for the browser list, read from the file's headers (MediaMetadataRetriever, or the FFmpeg decoder for
 * WMA/APE/DSF/DFF) only for rows on screen. Local and SAF always; network sources follow the thumbnail switches
 * (per protocol, Wi-Fi only). Results are kept by source, path, size and modification time, in memory and on disk.
 *
 * Only two outcomes are kept: a length, and "this file cannot be parsed" (looked at again after [UNKNOWN_RETRY_MS] or
 * on refresh, [forgetUnknown]). A failure to read (connection, authentication, permission, cancellation) is never
 * kept, so the same file gets its length once it can be read.
 */
class DurationRepository internal constructor(
    context: Context,
    /** Where the index is kept (tests use their own directory). */
    dir: File = context.filesDir,
    /** Opens a file for reading (tests wrap it to simulate failures). */
    private val openReader: (MusicFile) -> RandomAccessReader = { f -> SourceRegistry.fileSystem(f.ref).openRandomAccess(f.path) },
) {
    private val thumbnails = ThumbnailRepository.get(context)
    private val gson = Gson()
    private val indexFile = File(dir, "duration-index-v2.json")
    /** dev2 kept every failure as 0 ("unknown"): its known lengths are taken over, its unknowns are not. */
    private val legacyIndexFile = File(dir, "duration-index.json")
    private val semaphore = Semaphore(3)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSave: Job? = null

    private class Index(val known: LinkedHashMap<String, Long>, val unknown: LinkedHashMap<String, Long>)
    private data class IndexFile(val version: Int = 2, val known: Map<String, Long>? = null, val unknown: Map<String, Long>? = null)

    /** known: key to ms; unknown: key to when it was found unparsable. Least recently used first; bounded. */
    private val index: Index by lazy { load() }

    private fun key(file: MusicFile) = "${file.sourceId}|${file.path}|${file.size}|${file.lastModified}"

    /** Length in ms, or null when not shown (not audio, switched off, Wi-Fi only, unreadable now or unparsable). */
    suspend fun duration(file: MusicFile): Long? = withContext(Dispatchers.IO) {
        if (file.isDirectory || !MediaTypes.isAudio(file.name)) return@withContext null
        val cfg = SourceRegistry.get(file.sourceId) ?: return@withContext null
        if (cfg.isNetwork) {
            val s = thumbnails.settings
            if (!s.thumbnailsEnabled(cfg.type) || (s.wifiOnly && !thumbnails.isOnWifi())) return@withContext null
        }
        val key = key(file)
        val idx = index
        synchronized(idx) {
            idx.known[key]?.let { return@withContext it }
            idx.unknown[key]?.let { at -> if (System.currentTimeMillis() - at < UNKNOWN_RETRY_MS) return@withContext null }
        }
        val ms = try {
            semaphore.withPermit { runInterruptible { read(file) } }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.javaClass.simpleName} ${e.message}")
            return@withContext null
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.message}")
            return@withContext null
        }
        ensureActive() // a cancelled look keeps nothing
        synchronized(idx) {
            if (ms > 0) { idx.known[key] = ms; idx.unknown.remove(key) } else idx.unknown[key] = System.currentTimeMillis()
            while (idx.known.size > MAX_ENTRIES) idx.known.remove(idx.known.keys.first())
            while (idx.unknown.size > MAX_ENTRIES) idx.unknown.remove(idx.unknown.keys.first())
        }
        scheduleSave()
        ms.takeIf { it > 0 }
    }

    /** Files found unparsable are looked at again (browser refresh). */
    fun forgetUnknown() {
        val idx = index
        synchronized(idx) {
            if (idx.unknown.isEmpty()) return
            idx.unknown.clear()
        }
        scheduleSave()
    }

    /** Length in ms, 0 if the file was read but cannot be parsed; throws [IOException] if it could not be read. */
    private fun read(file: MusicFile): Long {
        val ext = SourcePath.extension(file.name)
        openReader(file).use { reader ->
            if (ext in MediaTypes.EXTRA_AUDIO) return readNative(reader, ext)
            val source = ReaderDataSource(reader)
            val mmr = MediaMetadataRetriever()
            try {
                val ms = try {
                    mmr.setDataSource(source)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (e: RuntimeException) {
                    // The retriever reports a failed read the same way as a file it cannot parse.
                    source.failure?.let { throw it }
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("interrupted")
                    android.util.Log.w(TAG, "length of ${file.name} not parsable: ${e.javaClass.simpleName} ${e.message}")
                    return 0L
                }
                source.failure?.let { throw it }
                return ms?.takeIf { it > 0 } ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        }
    }

    private fun readNative(reader: RandomAccessReader, ext: String): Long {
        if (!NativeDecoder.isAvailable()) return 0L
        val io = NativeIo(reader)
        val handle = try {
            NativeDecoder.nativeOpen(io, ext, 96_000)
        } catch (e: NativeDecoderException) {
            // ERR_IO: reading failed (the cause is what the reader threw); it says nothing about the file.
            if (e.code == NativeDecoder.ERR_IO || io.lastError != null) throw (io.lastError as? IOException) ?: e
            return 0L // unsupported or corrupt
        }
        try {
            val info = NativeDecoder.nativeInfo(handle) // [rate, channels, durationUs, totalFrames, ...]
            return when {
                info[2] > 0 -> info[2] / 1000
                info[3] > 0 && info[0] > 0 -> info[3] * 1000 / info[0]
                else -> 0L
            }
        } finally {
            NativeDecoder.nativeClose(handle)
        }
    }

    /** MediaMetadataRetriever over any source (local, SAF, SMB, FTP, WebDAV) by random access; keeps a read failure. */
    private class ReaderDataSource(private val reader: RandomAccessReader) : MediaDataSource() {
        @Volatile var failure: IOException? = null
            private set
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            return try {
                reader.read(position, buffer, offset, size)
            } catch (e: IOException) {
                failure = e; -1
            } catch (e: RuntimeException) {
                failure = IOException(e); -1
            }
        }
        override fun getSize(): Long = reader.size
        override fun close() = Unit
    }

    private fun load(): Index {
        val known = LinkedHashMap<String, Long>(256, 0.75f, true)
        val unknown = LinkedHashMap<String, Long>(64, 0.75f, true)
        runCatching {
            if (indexFile.exists()) {
                val f = gson.fromJson(indexFile.readText(), IndexFile::class.java)
                f.known?.let { known.putAll(it) }
                f.unknown?.let { unknown.putAll(it) }
            } else if (legacyIndexFile.exists()) {
                val old = gson.fromJson<Map<String, Long>>(legacyIndexFile.readText(), object : TypeToken<Map<String, Long>>() {}.type).orEmpty()
                old.forEach { (k, v) -> if (v > 0) known[k] = v }
            }
            Unit
        }
        return Index(known, unknown)
    }

    // Written on an I/O thread and coalesced, like the folder-image index.
    private fun scheduleSave() {
        synchronized(saveLock) {
            pendingSave?.cancel()
            pendingSave = ioScope.launch {
                delay(1_000)
                val idx = index
                val snapshot = synchronized(idx) { IndexFile(known = HashMap(idx.known), unknown = HashMap(idx.unknown)) }
                runCatching {
                    val tmp = File(indexFile.path + ".tmp")
                    tmp.writeText(gson.toJson(snapshot))
                    if (tmp.renameTo(indexFile)) legacyIndexFile.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5_000
        /** A file found unparsable is looked at again after this long (or on refresh). */
        private const val UNKNOWN_RETRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val TAG = "DurationRepository"
        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: DurationRepository? = null
        fun get(context: Context): DurationRepository =
            instance ?: synchronized(this) { instance ?: DurationRepository(context.applicationContext).also { instance = it } }
    }
}
