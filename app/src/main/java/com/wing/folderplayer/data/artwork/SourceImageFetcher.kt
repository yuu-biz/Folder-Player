package com.wing.folderplayer.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Coil fetcher for `fpsrc://` images. Credentials are resolved by sourceId in [SourceRegistry] (no shared auth state).
 * Small requests (thumbnails) are downsampled and cached on disk ([ThumbnailDiskCache]) under a key made of source URI,
 * image size/mtime (the `?v=` part), bucketed requested size and the artwork settings revision (a directory) — never credentials.
 * The image bytes come from the memory cache when cover validation just read them ([ArtworkImageBytes]).
 */
class SourceImageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val raw = uri.toString()
        val ref = SourceUris.parse(raw) ?: throw SourceException.NotFound("bad image uri")
        val requested = requestedPx()
        val repo = ThumbnailRepository.get(context)
        val diskCache = repo.thumbnailCache
        val cacheFile = if (requested in 1..THUMB_MAX_PX) diskCache.file(raw, requested, repo.settings.revision) else null
        if (cacheFile != null && cacheFile.exists()) {
            diskCache.markUsed(cacheFile)
            return SourceResult(ImageSource(cacheFile.toOkioPath(), okio.FileSystem.SYSTEM), "image/jpeg", DataSource.DISK)
        }
        // Cover validation may have read this image already: then the bytes come from memory, not from the source.
        val bytes = runInterruptible(Dispatchers.IO) { ThumbnailRepository.imageBytes.load(raw, ref) }
        if (cacheFile != null) {
            // Cut to the bucket size, not the requested one, so the file serves every request of that bucket.
            val thumb = runInterruptible(Dispatchers.Default) { downsample(bytes, diskCache.bucketOf(requested)) }
            if (thumb != null) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val tmp = File(cacheFile.path + ".tmp")
                    tmp.writeBytes(thumb)
                    tmp.renameTo(cacheFile)
                    diskCache.stored(cacheFile)
                }
                return SourceResult(ImageSource(Buffer().write(thumb), context), "image/jpeg", DataSource.NETWORK)
            }
        }
        return SourceResult(ImageSource(Buffer().write(bytes), context), null, DataSource.NETWORK)
    }

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
        const val THUMB_DIR = "thumbs"
        const val THUMB_MAX_PX = 512

        fun downsample(bytes: ByteArray, target: Int): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = ThumbnailRepository.sampleSize(bounds.outWidth, bounds.outHeight, target) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            return try {
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            } finally {
                bmp.recycle()
            }
        }
    }
}
