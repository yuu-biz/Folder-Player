package com.wing.folderplayer.data.artwork

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import com.wing.folderplayer.data.source.NetworkStats
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Coil fetcher for `fpsrc://` images. Credentials are resolved by sourceId in [SourceRegistry] (no shared auth state).
 *
 * Two tiers, chosen by the request and not by its size:
 *  - the browser's folder thumbnails (the default): a 128 px or a 256 px JPEG in [ImageDiskCache], whichever fits the
 *    request (larger requests get the 256 px one);
 *  - the player's picture (`TIER_PARAM` = `TIER_ARTWORK`, set by the player surfaces): one file of at most 2048 px, shared
 *    by mini, full and wide player ([ThumbnailRepository.playerArtworkFile]; the notification reads the same file).
 *
 * A file is made once from the source and then used until the image changes: the key is the image URI with its size and
 * mtime (`?v=`), never credentials. The image bytes come from the memory cache when cover validation just read them
 * ([ArtworkImageBytes]).
 */
class SourceImageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val raw = uri.toString()
        val ref = SourceUris.parse(raw) ?: throw SourceException.NotFound("bad image uri")
        val repo = ThumbnailRepository.get(context)
        return if (options.parameters.value<String>(TIER_PARAM) == TIER_ARTWORK) playerArtwork(raw, ref, repo) else thumbnail(raw, ref, repo)
    }

    private suspend fun playerArtwork(raw: String, ref: SourceRef, repo: ThumbnailRepository): FetchResult {
        val file = runInterruptible(Dispatchers.IO) { repo.playerArtworkFile(raw, ref) }
        if (file != null) return SourceResult(ImageSource(file.toOkioPath(), okio.FileSystem.SYSTEM), null, DataSource.DISK)
        // Not kept (no version in the URI, or not an image): the image as it is, for the decoder to accept or reject.
        val bytes = runInterruptible(Dispatchers.IO) { ThumbnailRepository.imageBytes.load(raw, ref) }
        return SourceResult(ImageSource(Buffer().write(bytes), context), null, DataSource.NETWORK)
    }

    private suspend fun thumbnail(raw: String, ref: SourceRef, repo: ThumbnailRepository): FetchResult {
        val cache = repo.imageCache
        val bucket = cache.thumbBucket(requestedPx())
        // An unversioned URI cannot tell when the picture changes: not kept.
        val cacheFile = if (raw.contains("?v=")) cache.file(ImageTier.THUMB, raw, bucket) else null
        if (cacheFile != null) {
            if (cacheFile.exists()) {
                cache.markUsed(cacheFile)
                NetworkStats.thumbnailHit()
                return diskResult(cacheFile)
            }
            // The 128 px file is cut from the 256 px one when that exists: the source is not asked.
            if (bucket == ImageDiskCache.SMALL) {
                val large = cache.file(ImageTier.THUMB, raw, ImageDiskCache.LARGE)
                val small = if (large.exists()) runInterruptible(Dispatchers.Default) { ImageScaling.fit(large.readBytes(), bucket, THUMB_QUALITY) } else null
                if (small != null) {
                    runCatching { cache.write(cacheFile, small) }
                    cache.markUsed(large)
                    NetworkStats.thumbnailHit()
                    return SourceResult(ImageSource(Buffer().write(small), context), "image/jpeg", DataSource.DISK)
                }
            }
            NetworkStats.thumbnailMiss()
        }
        // Cover validation may have read this image already: then the bytes come from memory, not from the source.
        val bytes = runInterruptible(Dispatchers.IO) { ThumbnailRepository.imageBytes.load(raw, ref) }
        if (cacheFile != null) {
            val thumb = runInterruptible(Dispatchers.Default) { ImageScaling.fit(bytes, bucket, THUMB_QUALITY) }
            if (thumb != null) {
                runCatching { cache.write(cacheFile, thumb) }
                NetworkStats.thumbnailFetched()
                return SourceResult(ImageSource(Buffer().write(thumb), context), "image/jpeg", DataSource.NETWORK)
            }
        }
        return SourceResult(ImageSource(Buffer().write(bytes), context), null, DataSource.NETWORK)
    }

    private fun diskResult(file: File) = SourceResult(ImageSource(file.toOkioPath(), okio.FileSystem.SYSTEM), "image/jpeg", DataSource.DISK)

    private fun requestedPx(): Int {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: return -1
        val h = (options.size.height as? Dimension.Pixels)?.px ?: return -1
        return maxOf(w, h)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == SourceUris.SCHEME) SourceImageFetcher(data, options, context.applicationContext) else null
    }

    companion object {
        /** The cache directory of earlier versions (deleted once; the images are now under [IMAGE_CACHE_DIR]). */
        const val THUMB_DIR = "thumbs"
        const val IMAGE_CACHE_DIR = "imgcache"
        const val THUMB_QUALITY = 88

        /** Coil request parameter that marks a request of the player's picture; absent = a browser thumbnail. */
        const val TIER_PARAM = "fp.tier"
        const val TIER_ARTWORK = "artwork"
    }
}
