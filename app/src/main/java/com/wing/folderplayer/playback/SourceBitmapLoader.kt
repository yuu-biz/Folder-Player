package com.wing.folderplayer.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.readBytes
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Bitmaps for the media notification / lock screen. Resolves `fpsrc://` artwork through [SourceRegistry] (so SMB/FTP/
 * WebDAV/SAF covers work without passing internal URIs to other processes) and applies the cover priority
 * folder image → embedded image → none.
 */
@OptIn(UnstableApi::class)
class SourceBitmapLoader(private val fallback: BitmapLoader) : BitmapLoader {
    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    /**
     * The notification is rebuilt on every player event and each rebuild discards the previous pending bitmap
     * callback, so the same artwork must map to the same (in-flight or finished) future instead of a new load.
     */
    private val cache = object : LinkedHashMap<String, ListenableFuture<Bitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ListenableFuture<Bitmap>>?) = size > 6
    }

    private fun cached(key: String, load: () -> ListenableFuture<Bitmap>): ListenableFuture<Bitmap> = synchronized(cache) {
        cache[key]?.let { f -> if (!f.isDone || runCatching { Futures.getDone(f) }.isSuccess) return f }
        load().also { cache[key] = it }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        cached("data:" + data.size + ":" + data.contentHashCode()) { executor.submit<Bitmap> { decode(data) } }

    override fun loadBitmap(uri: Uri, options: BitmapFactory.Options?): ListenableFuture<Bitmap> {
        val ref = SourceUris.parse(uri.toString()) ?: return fallback.loadBitmap(uri, options)
        return cached(uri.toString()) {
            executor.submit<Bitmap> { decode(SourceRegistry.fileSystem(ref).readBytes(ref.path, ThumbnailRepository.MAX_IMAGE_BYTES)) }
        }
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val uri = metadata.artworkUri
        val data = metadata.artworkData
        if (uri == null) return data?.let { decodeBitmap(it) }
        val fromUri = loadBitmap(uri)
        if (data == null) return fromUri
        // Folder image first; if it cannot be read or decoded, fall back to the embedded picture.
        return Futures.catchingAsync(fromUri, Throwable::class.java, { decodeBitmap(data) }, MoreExecutors.directExecutor())
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("not an image")
        val opts = BitmapFactory.Options().apply { inSampleSize = ThumbnailRepository.sampleSize(bounds.outWidth, bounds.outHeight, MAX_PX) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("image could not be decoded")
    }

    companion object {
        const val MAX_PX = 1024
    }
}
