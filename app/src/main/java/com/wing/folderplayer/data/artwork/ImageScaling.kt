package com.wing.folderplayer.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/** Cutting images down for the disk cache: thumbnails (128 / 256 px) and player artwork (up to [PLAYER_ARTWORK_MAX_PX]). */
object ImageScaling {
    /** Longer side of the player artwork kept on disk: enough for the full player and a wide window, far below a 20 MB scan. */
    const val PLAYER_ARTWORK_MAX_PX = 2048

    /** An image that already fits and is at most this many bytes is kept as it is (no second lossy encode). */
    const val KEEP_AS_IS_BYTES = 2L * 1024 * 1024

    /** The largest power of two that leaves the longer side at [target] or more (1 when the image is not larger). */
    fun sampleForLongSide(w: Int, h: Int, target: Int): Int {
        val longer = maxOf(w, h)
        var s = 1
        while (longer / (s * 2) >= target) s *= 2
        return s
    }

    /**
     * [bytes] cut down so that the longer side is at most [maxLong] px (never enlarged), as JPEG; null when [bytes] is not
     * an image. Decoding is done at a power-of-two sample first so that a huge scan never has to be held at full size; if
     * memory still runs out the sample is doubled (a smaller result is better than none).
     */
    fun fit(bytes: ByteArray, maxLong: Int, quality: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = sampleForLongSide(bounds.outWidth, bounds.outHeight, maxLong)
        var bmp: Bitmap? = null
        for (attempt in 0..2) {
            try {
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                break
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        if (bmp == null) return null
        return try {
            val longer = maxOf(bmp.width, bmp.height)
            val scaled = if (longer > maxLong) {
                val f = maxLong.toFloat() / longer
                bmp.scale(maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()))
            } else bmp
            try {
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            } finally {
                if (scaled !== bmp) scaled.recycle()
            }
        } finally {
            bmp.recycle()
        }
    }

    /** The bytes to keep as player artwork for [source], or null when it is not a decodable image. */
    fun playerArtwork(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val fits = maxOf(bounds.outWidth, bounds.outHeight) <= PLAYER_ARTWORK_MAX_PX
        if (fits && source.size <= KEEP_AS_IS_BYTES) return if (ThumbnailRepository.decodesAsImage(source)) source else null
        return fit(source, PLAYER_ARTWORK_MAX_PX, 90)
    }
}
