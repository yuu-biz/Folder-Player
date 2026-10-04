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
 * (per protocol, Wi-Fi only). Results are kept by source, path, size and modification time, in memory and on disk;
 * read errors are not kept, a file that cannot be parsed is kept as "unknown".
 */
class DurationRepository private constructor(private val context: Context) {
    private val thumbnails = ThumbnailRepository.get(context)
    private val gson = Gson()
    private val indexFile = File(context.filesDir, "duration-index.json")
    private val semaphore = Semaphore(3)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSave: Job? = null

    /** key → ms (0 = could not be read from this file). Least recently used first; bounded. */
    private val cache: LinkedHashMap<String, Long> by lazy { load() }

    private fun key(file: MusicFile) = "${file.sourceId}|${file.path}|${file.size}|${file.lastModified}"

    /** Length in ms, or null when not shown (not audio, switched off, Wi-Fi only, unreadable). */
    suspend fun duration(file: MusicFile): Long? = withContext(Dispatchers.IO) {
        if (file.isDirectory || !MediaTypes.isAudio(file.name)) return@withContext null
        val cfg = SourceRegistry.get(file.sourceId) ?: return@withContext null
        if (cfg.isNetwork) {
            val s = thumbnails.settings
            if (!s.thumbnailsEnabled(cfg.type) || (s.wifiOnly && !thumbnails.isOnWifi())) return@withContext null
        }
        val key = key(file)
        synchronized(cache) { cache[key] }?.let { return@withContext it.takeIf { ms -> ms > 0 } }
        val ms = try {
            semaphore.withPermit { runInterruptible { read(file) } }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "length of ${file.name}: ${e.javaClass.simpleName} ${e.message}")
            return@withContext null // not kept: a later look (reconnect, permission) may succeed
        }
        synchronized(cache) {
            cache[key] = ms
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        scheduleSave()
        ms.takeIf { it > 0 }
    }

    private fun read(file: MusicFile): Long {
        val ext = SourcePath.extension(file.name)
        SourceRegistry.fileSystem(file.ref).openRandomAccess(file.path).use { reader ->
            if (ext in MediaTypes.EXTRA_AUDIO) return readNative(reader, ext)
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(ReaderDataSource(reader))
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "length of ${file.name} not readable: ${e.javaClass.simpleName} ${e.message}")
                0L // not a parsable file
            } finally {
                runCatching { mmr.release() }
            }
        }
    }

    private fun readNative(reader: RandomAccessReader, ext: String): Long {
        if (!NativeDecoder.isAvailable()) return 0L
        val handle = try {
            NativeDecoder.nativeOpen(NativeIo(reader), ext, 96_000)
        } catch (e: NativeDecoderException) {
            return 0L
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

    /** MediaMetadataRetriever over any source (local, SAF, SMB, FTP, WebDAV) by random access. */
    private class ReaderDataSource(private val reader: RandomAccessReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (size == 0) 0 else reader.read(position, buffer, offset, size)
        override fun getSize(): Long = reader.size
        override fun close() = Unit
    }

    private fun load(): LinkedHashMap<String, Long> {
        val map = LinkedHashMap<String, Long>(256, 0.75f, true)
        runCatching {
            if (indexFile.exists()) map.putAll(gson.fromJson<Map<String, Long>>(indexFile.readText(), object : TypeToken<Map<String, Long>>() {}.type).orEmpty())
        }
        return map
    }

    // Written on an I/O thread and coalesced, like the folder-image index.
    private fun scheduleSave() {
        synchronized(saveLock) {
            pendingSave?.cancel()
            pendingSave = ioScope.launch {
                delay(1_000)
                val snapshot = synchronized(cache) { HashMap(cache) }
                runCatching {
                    val tmp = File(indexFile.path + ".tmp")
                    tmp.writeText(gson.toJson(snapshot))
                    tmp.renameTo(indexFile)
                }
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5_000
        private const val TAG = "DurationRepository"
        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: DurationRepository? = null
        fun get(context: Context): DurationRepository =
            instance ?: synchronized(this) { instance ?: DurationRepository(context.applicationContext).also { instance = it } }
    }
}
