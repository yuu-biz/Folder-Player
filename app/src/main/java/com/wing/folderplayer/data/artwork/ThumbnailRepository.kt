package com.wing.folderplayer.data.artwork

import androidx.core.content.edit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Thumbnail settings. Local ON, network OFF, network thumbnails Wi-Fi only ON by default. */
class ArtworkSettings(context: Context) {
    private val prefs = context.getSharedPreferences("artwork_prefs", Context.MODE_PRIVATE)

    fun thumbnailsEnabled(type: SourceType): Boolean = prefs.getBoolean("thumbs_${type.name}", type == SourceType.LOCAL || type == SourceType.SAF)
    fun setThumbnailsEnabled(type: SourceType, on: Boolean) = prefs.edit { putBoolean("thumbs_${type.name}", on); putInt("revision", revision + 1) }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) { prefs.edit { putBoolean("wifi_only", v); putInt("revision", revision + 1) } }
    val revision: Int get() = prefs.getInt("revision", 0)
    fun bumpRevision() = prefs.edit { putInt("revision", revision + 1) }
}

object ImageUris {
    /** Display/cache URI of an image entry: the source URI plus a version derived from size and mtime. */
    fun of(ref: SourceRef, entry: MusicFile): String = SourceUris.toUri(ref) + "?v=${entry.size}-${entry.lastModified}"
}

/**
 * Folder image lookup with de-duplicated, bounded concurrency. Positive results are kept on disk (keyed by folder
 * and image size/mtime); "no image" is kept only in memory for a short time; I/O and permission errors are never
 * cached, so a later permission grant or reconnect re-resolves.
 */
class ThumbnailRepository private constructor(private val context: Context) {
    val settings = ArtworkSettings(context)
    private val gson = Gson()
    private val indexFile = File(context.filesDir, "artwork-index.json")
    private val semaphore = Semaphore(4)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ArtworkResult>>()
    private val negative = ConcurrentHashMap<String, Long>()
    private val positive: MutableMap<String, IndexEntry> by lazy { loadIndex() }

    private data class IndexEntry(val image: String = "", val name: String = "", val size: Long = 0, val mtime: Long = 0)

    private fun loadIndex(): MutableMap<String, IndexEntry> = try {
        if (indexFile.exists()) ConcurrentHashMap(gson.fromJson<Map<String, IndexEntry>>(indexFile.readText(), object : TypeToken<Map<String, IndexEntry>>() {}.type).orEmpty())
        else ConcurrentHashMap()
    } catch (e: Exception) {
        ConcurrentHashMap()
    }

    // Folder thumbnails are resolved from the UI's coroutines, so the index is written on an I/O thread, and coalesced:
    // scrolling through many folders writes the file once instead of once per image found.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSave: Job? = null

    private fun scheduleSave() {
        synchronized(saveLock) {
            pendingSave?.cancel()
            pendingSave = ioScope.launch { delay(SAVE_DELAY_MS); saveIndex() }
        }
    }

    @Synchronized
    private fun saveIndex() {
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(gson.toJson(HashMap(positive)))
            tmp.renameTo(indexFile)
        }
    }

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Grid/list folder thumbnail: honours the per-protocol switch and Wi-Fi restriction. */
    suspend fun folderThumbnail(folder: SourceRef): ArtworkResult {
        val cfg = SourceRegistry.get(folder.sourceId) ?: return ArtworkResult.Failed(ArtworkResult.Kind.OTHER, "unknown source")
        if (!settings.thumbnailsEnabled(cfg.type)) return ArtworkResult.Disabled
        if (cfg.isNetwork && settings.wifiOnly && !isOnWifi()) return ArtworkResult.WifiRequired
        return resolve(folder, null)
    }

    /** Player / notification cover: always resolved, regardless of the thumbnail switches. */
    suspend fun playbackCover(folder: SourceRef, known: List<MusicFile>?): ArtworkResult = resolve(folder, known)

    private fun key(folder: SourceRef): String {
        val rev = SourceRegistry.get(folder.sourceId)?.revision ?: 0
        return folder.toUriString() + "|r$rev"
    }

    suspend fun resolve(folder: SourceRef, known: List<MusicFile>?): ArtworkResult {
        val key = key(folder)
        positive[key]?.let { e ->
            val image = SourceUris.parse(e.image)
            // With a fresh listing we can tell that the cached image was replaced or removed.
            val stillValid = known == null || known.any { it.name == e.name && it.size == e.size && it.lastModified == e.mtime } ||
                (image != null && image.parent?.path != folder.path)
            if (image != null && stillValid) return ArtworkResult.Found(image, MusicFile(e.name, image.path, false, e.size, e.mtime, folder.sourceId))
            positive.remove(key)
        }
        negative[key]?.let { at ->
            val fresh = System.currentTimeMillis() - at < NEGATIVE_TTL_MS
            val listingHasImages = known?.let { ArtworkRules.candidates(it).isNotEmpty() } ?: false
            if (fresh && !listingHasImages) return ArtworkResult.None
            negative.remove(key)
        }
        val mine = CompletableDeferred<ArtworkResult>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            return try {
                existing.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The request we joined was cancelled (its item left the screen); retry unless we are cancelled too.
                kotlin.coroutines.coroutineContext.ensureActive()
                resolve(folder, known)
            }
        }
        try {
            val raw = semaphore.withPermit {
                runInterruptible(Dispatchers.IO) { resolver.resolve(folder, known) }
            }
            // Shared storage hides images the app may not read, so "nothing found" is really a permission problem.
            val result = if (raw == ArtworkResult.None && localImagesRestricted(folder.sourceId))
                ArtworkResult.Failed(ArtworkResult.Kind.PERMISSION, "image access not granted (READ_MEDIA_IMAGES)") else raw
            when (result) {
                is ArtworkResult.Found -> {
                    positive[key] = IndexEntry(result.image.toUriString(), result.entry.name, result.entry.size, result.entry.lastModified)
                    scheduleSave()
                }
                ArtworkResult.None -> negative[key] = System.currentTimeMillis()
                else -> Unit
            }
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private val resolver = ArtworkResolver(
        lister = { ref -> SourceRegistry.fileSystem(ref).list(ref.path) },
        validate = { entry -> validateImage(SourceRef(entry.sourceId, entry.path)) },
    )

    private fun localImagesRestricted(sourceId: String): Boolean {
        if (SourceRegistry.get(sourceId)?.type != SourceType.LOCAL) return false
        return com.wing.folderplayer.utils.PermissionDiagnostics.report(context).images != com.wing.folderplayer.utils.PermissionDiagnostics.Access.GRANTED
    }

    /** Reads (≤ [MAX_IMAGE_BYTES]) and fully decodes a small sample; false means the image itself is broken. */
    private fun validateImage(ref: SourceRef): Boolean {
        val bytes = try {
            SourceRegistry.fileSystem(ref).readBytes(ref.path, MAX_IMAGE_BYTES)
        } catch (e: com.wing.folderplayer.data.source.SourceException.TooLarge) {
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 64) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        bmp.recycle()
        return true
    }

    fun invalidate(folder: SourceRef) {
        val prefix = folder.toUriString() + "|"
        positive.keys.removeIf { it.startsWith(prefix) }
        negative.keys.removeIf { it.startsWith(prefix) }
        scheduleSave()
    }

    fun invalidateSource(sourceId: String) {
        val prefix = SourceUris.toUri(sourceId, "/").removeSuffix("/")
        positive.keys.removeIf { it.startsWith(prefix) }
        negative.keys.removeIf { it.startsWith(prefix) }
        scheduleSave()
    }

    /** Forget every "no image" result (after a permission grant or settings change). */
    fun invalidateNegatives() = negative.clear()

    /** Clears image index and thumbnail files only — sources, settings and playlists are untouched. */
    @Synchronized // with saveIndex: a write in progress cannot bring the deleted file back with old entries
    fun clearCaches() {
        synchronized(saveLock) { pendingSave?.cancel() }
        positive.clear()
        negative.clear()
        indexFile.delete()
        File(context.cacheDir, SourceImageFetcher.THUMB_DIR).deleteRecursively()
    }

    companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L
        private const val SAVE_DELAY_MS = 500L
        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ThumbnailRepository? = null
        fun get(context: Context): ThumbnailRepository =
            instance ?: synchronized(this) { instance ?: ThumbnailRepository(context.applicationContext).also { instance = it } }

        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var s = 1
            while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
            return s
        }
    }
}
