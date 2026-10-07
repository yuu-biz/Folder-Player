package com.wing.folderplayer.data.artwork

import androidx.core.content.edit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.NetworkStats
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Total size limit of the image cache on disk (thumbnails and player artwork together), one of [CACHE_LIMITS_MB]. */
    var cacheLimitMb: Int
        get() = prefs.getInt("cache_limit_mb", DEFAULT_CACHE_MB).takeIf { it in CACHE_LIMITS_MB } ?: DEFAULT_CACHE_MB
        set(v) = prefs.edit { putInt("cache_limit_mb", v) }
    val cacheLimitBytes: Long get() = cacheLimitMb * 1024L * 1024L

    companion object {
        val CACHE_LIMITS_MB = listOf(128, 256, 512, 1024, 2048)
        const val DEFAULT_CACHE_MB = 512
    }
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
    private val semaphore = Semaphore(4)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ArtworkResult>>()
    private val negative = ConcurrentHashMap<String, Long>()

    // Index file reads / writes and thumbnail clean-up run here, never on the caller's (often the main) thread.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Positive results: read from disk in the background as soon as the repository exists; written coalesced.
    private val positive = ArtworkIndex(File(context.filesDir, "artwork-index.json"), ioScope, SAVE_DELAY_MS)

    /**
     * Images on disk: thumbnails (128 / 256 px) and player artwork (up to 2048 px), under one user-chosen size limit. The
     * cache of earlier versions (`thumbs`) is deleted once.
     */
    val imageCache = ImageDiskCache(
        File(context.cacheDir, SourceImageFetcher.IMAGE_CACHE_DIR), ioScope, { settings.cacheLimitBytes },
        legacyDirs = listOf(File(context.cacheDir, SourceImageFetcher.THUMB_DIR)),
    )

    /** Sets the size limit (a lower one takes effect at once: the cache is cleaned up). */
    fun setCacheLimitMb(mb: Int) {
        settings.cacheLimitMb = mb
        imageCache.onLimitChanged()
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
        positive.get(key)?.let { e ->
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
                    positive.put(key, ArtworkIndex.Entry(result.image.toUriString(), result.entry.name, result.entry.size, result.entry.lastModified))
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
        validate = { entry -> validateImage(entry) },
    )

    private fun localImagesRestricted(sourceId: String): Boolean {
        if (SourceRegistry.get(sourceId)?.type != SourceType.LOCAL) return false
        return com.wing.folderplayer.utils.PermissionDiagnostics.report(context).images != com.wing.folderplayer.utils.PermissionDiagnostics.Access.GRANTED
    }

    /**
     * Reads (≤ [MAX_IMAGE_BYTES]) and fully decodes a small sample; false means the image itself is broken. A good
     * image's bytes stay in [imageBytes] so that showing it does not read the source again.
     */
    private fun validateImage(entry: MusicFile): Boolean = try {
        imageBytes.validate(entry)
    } catch (e: com.wing.folderplayer.data.source.SourceException.TooLarge) {
        false
    }

    fun invalidate(folder: SourceRef) {
        val prefix = folder.toUriString() + "|"
        positive.removePrefix(prefix)
        negative.keys.removeIf { it.startsWith(prefix) }
    }

    fun invalidateSource(sourceId: String) {
        val prefix = SourceUris.toUri(sourceId, "/").removeSuffix("/")
        positive.removePrefix(prefix)
        negative.keys.removeIf { it.startsWith(prefix) }
        imageBytes.cache.removePrefix(prefix)
    }

    /** Forget every "no image" result (after a permission grant or settings change). */
    fun invalidateNegatives() = negative.clear()

    // ---- player artwork (the picture of what is playing) ----

    private val artworkLocks = ConcurrentHashMap<String, Any>()

    /**
     * The player artwork file of the image [raw] (a versioned `fpsrc://` URI): kept on disk after the first time it is
     * needed, cut down to at most [ImageScaling.PLAYER_ARTWORK_MAX_PX], and used by every surface that shows it (mini, full
     * and wide player, notification). Until the image itself changes (its version in the URI) the source is not asked again.
     * Null when it cannot be kept (an unversioned URI cannot tell when the picture changes; a broken image is never kept);
     * nothing is written then. Blocks on I/O: call from an I/O thread. Concurrent callers for one image wait for the first.
     */
    fun playerArtworkFile(raw: String, ref: SourceRef): File? {
        if (!raw.contains("?v=")) return null
        val file = imageCache.file(ImageTier.ARTWORK, raw)
        if (file.exists()) { imageCache.markUsed(file); NetworkStats.artworkHit(); return file }
        val lock = artworkLocks.computeIfAbsent(raw) { Any() }
        try {
            synchronized(lock) {
                if (file.exists()) { imageCache.markUsed(file); NetworkStats.artworkHit(); return file }
                NetworkStats.artworkMiss()
                val bytes = imageBytes.load(raw, ref) // the source, unless cover validation just read it
                val art = ImageScaling.playerArtwork(bytes) ?: return null
                imageCache.write(file, art)
                NetworkStats.artworkFetched()
                return file
            }
        } finally {
            artworkLocks.remove(raw, lock)
        }
    }

    /** The player artwork bytes for the notification: the cached file, else the image as read (see [playerArtworkFile]). */
    fun playerArtworkBytes(raw: String, ref: SourceRef): ByteArray =
        playerArtworkFile(raw, ref)?.readBytes() ?: imageBytes.load(raw, ref)

    /**
     * Called with an image the cover validation read from its source: its 256 px thumbnail is written now, so the browser
     * never reads the same image a second time (a large cover does not fit [ValidatedImageCache]). Only a decodable image
     * gets here; the 128 px one is cut from this file when needed.
     */
    internal fun storeValidatedThumbnail(entry: MusicFile, bytes: ByteArray) {
        val uri = ImageUris.of(SourceRef(entry.sourceId, entry.path), entry)
        val file = imageCache.file(ImageTier.THUMB, uri, ImageDiskCache.LARGE)
        if (file.exists()) return
        val thumb = ImageScaling.fit(bytes, ImageDiskCache.LARGE, SourceImageFetcher.THUMB_QUALITY) ?: return
        imageCache.write(file, thumb)
        NetworkStats.thumbnailFetched()
    }

    /** Clears image index and thumbnail files only — sources, settings and playlists are untouched. */
    @Synchronized // the index's clear is also serialized with its save: a write in progress cannot bring the file back
    fun clearCaches() {
        positive.clear()
        negative.clear()
        imageBytes.cache.clear()
        imageCache.clear()
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

        /** The repository once the app created it (null before): for code that has no context (the notification's bitmap loader). */
        fun current(): ThumbnailRepository? = instance

        /**
         * Image bytes shared by cover validation and display (Coil fetcher, notification): a cover read for validation is
         * not read again for display. Process-wide, bounded, see [ValidatedImageCache].
         */
        val imageBytes = ArtworkImageBytes(
            ValidatedImageCache(),
            read = { ref -> SourceRegistry.fileSystem(ref).readBytes(ref.path, MAX_IMAGE_BYTES) },
            revisionOf = { id -> SourceRegistry.get(id)?.revision ?: 0 },
            decodes = ::decodesAsImage,
            onValidated = { entry, bytes -> instance?.storeValidatedThumbnail(entry, bytes) },
        )

        /** True when [bytes] decode to a bitmap (a small sample is fully decoded). */
        fun decodesAsImage(bytes: ByteArray): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 64) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            bmp.recycle()
            return true
        }

        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var s = 1
            while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
            return s
        }
    }
}
