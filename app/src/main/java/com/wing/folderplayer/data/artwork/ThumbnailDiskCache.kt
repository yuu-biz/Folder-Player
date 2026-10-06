package com.wing.folderplayer.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk cache of downsampled folder thumbnails (JPEG), kept under [root] as `r<revision>/<sha1>.jpg`.
 *
 *  - The requested size is rounded up to one of [BUCKETS], so nearby sizes (another phone, rotation, a slightly
 *    different grid) share one file, and a thumbnail is never smaller than what was asked for.
 *  - The artwork settings revision is a directory: a new revision makes every older directory (and the flat files of
 *    versions before this layout) garbage that is deleted on [scope], so superseded generations do not pile up.
 *  - The total size is bounded: after enough new data has been written (and once per process) the least recently used
 *    files are deleted on [scope] until the cache is back under [trimToBytes]. A hit refreshes the file's timestamp (at
 *    most once per [TOUCH_INTERVAL_MS]), which is the "recently used" order.
 *
 * Nothing here touches the caller's thread except computing a path and, for a hit, one `setLastModified`.
 */
class ThumbnailDiskCache(
    private val root: File,
    private val scope: CoroutineScope,
    private val maxBytes: Long = MAX_BYTES,
    private val trimToBytes: Long = maxBytes / 4 * 3,
    private val trimEveryBytes: Long = TRIM_EVERY_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val latestRevision = AtomicLong(-1)
    private val trimming = AtomicBoolean(false)
    private val writtenSinceTrim = AtomicLong(0)
    private val everTrimmed = AtomicBoolean(false)

    /** The cache file for [uri] at [px] (rounded up to a bucket) under [revision]. */
    fun file(uri: String, px: Int, revision: Int): File {
        noteRevision(revision)
        val bucket = bucketFor(px)
        val digest = MessageDigest.getInstance("SHA-1").digest("$uri|$bucket".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(root, "r$revision"), "$digest.jpg")
    }

    /** The pixel size a thumbnail requested at [px] is cut to. */
    fun bucketOf(px: Int): Int = bucketFor(px)

    /** Call when [f] was found and is used: keeps it among the recently used files. */
    fun markUsed(f: File) {
        val t = now()
        if (t - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(t)
    }

    /** Call after [f] was written: accounts for its size and starts a clean-up when enough was added. */
    fun stored(f: File) {
        val total = writtenSinceTrim.addAndGet(f.length())
        if (!everTrimmed.get() || total >= trimEveryBytes) requestTrim()
    }

    private fun noteRevision(revision: Int) {
        while (true) {
            val seen = latestRevision.get()
            if (revision <= seen) return
            if (latestRevision.compareAndSet(seen, revision.toLong())) { requestTrim(); return }
        }
    }

    private fun requestTrim() {
        if (!trimming.compareAndSet(false, true)) return
        scope.launch {
            try {
                trim()
            } finally {
                everTrimmed.set(true)
                writtenSinceTrim.set(0)
                trimming.set(false)
            }
        }
    }

    /** Deletes superseded generations, then least recently used files above the limit. Runs on an I/O thread. */
    internal fun trim() {
        val current = latestRevision.get()
        if (current < 0) return
        val keep = "r$current"
        root.listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
        val dir = File(root, keep)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val t = now()
        // A write that never finished (process killed between write and rename).
        files.filter { it.name.endsWith(".tmp") && t - it.lastModified() > TMP_STALE_MS }.forEach { it.delete() }
        val cached = files.filter { it.name.endsWith(".jpg") }
        var total = cached.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in cached.sortedBy { it.lastModified() }) {
            if (total <= trimToBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        /**
         * Thumbnail sizes (px of the longer side). Folder thumbnails are drawn at 40 dp (list; 105-140 px at 2.6-3.5x
         * density) and as grid cells of 1/3..1/5 of the screen width (216-360 px on a 1080 px phone). Steps of 1.5x to 1.8x
         * (at most 1.8x the requested size per side) let every grid / density combination of a phone
         * land on one or two files per image. THUMB_MAX_PX (512) is the largest request that is cached.
         */
        val BUCKETS = intArrayOf(128, 192, 288, 512)

        fun bucketFor(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

        /** Upper bound of the cache. A thumbnail is 10-30 KB at 128-192 px and about 100 KB at 512 px, so this holds a few thousand folders. */
        const val MAX_BYTES = 64L * 1024 * 1024
        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
        /** New data written between two clean-ups (the clean-up lists the directory, so it is not run per file). */
        const val TRIM_EVERY_BYTES = 4L * 1024 * 1024
        private const val TMP_STALE_MS = 60 * 60 * 1000L
    }
}
