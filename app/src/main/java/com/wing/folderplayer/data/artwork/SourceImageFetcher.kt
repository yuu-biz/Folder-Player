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
import com.wing.folderplayer.data.source.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Coil fetcher for `fpsrc://` images. Credentials are resolved by sourceId in [SourceRegistry] (no shared auth state).
 * Small requests (thumbnails) are downsampled and cached on disk under a key made of source URI, image size/mtime
 * (the `?v=` part), requested size and the artwork settings revision — never credentials.
 */
class SourceImageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val raw = uri.toString()
        val ref = SourceUris.parse(raw) ?: throw SourceException.NotFound("bad image uri")
        val target = requestedPx()
        val cacheFile = if (target in 1..THUMB_MAX_PX) thumbFile(raw, target) else null
        if (cacheFile != null && cacheFile.exists()) {
            return SourceResult(ImageSource(cacheFile.toOkioPath(), okio.FileSystem.SYSTEM), "image/jpeg", DataSource.DISK)
        }
        val bytes = runInterruptible(Dispatchers.IO) {
            SourceRegistry.fileSystem(ref).readBytes(ref.path, ThumbnailRepository.MAX_IMAGE_BYTES)
        }
        if (cacheFile != null) {
            val thumb = runInterruptible(Dispatchers.Default) { downsample(bytes, target) }
            if (thumb != null) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val tmp = File(cacheFile.path + ".tmp")
                    tmp.writeBytes(thumb)
                    tmp.renameTo(cacheFile)
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

    private fun thumbFile(raw: String, px: Int): File {
        val rev = ThumbnailRepository.get(context).settings.revision
        val digest = MessageDigest.getInstance("SHA-1").digest("$raw|$px|$rev".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, THUMB_DIR), "$digest.jpg")
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
