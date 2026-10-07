package com.wing.folderplayer.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The two kinds of image kept on disk. [share] is the part of the user's limit a tier is entitled to (see [ImageDiskCache.trim]). */
enum class ImageTier(val dir: String, val share: Int) {
    /** Folder thumbnails of the browser: 128 or 256 px. */
    THUMB("t", 40),
    /** The picture of what is playing: one file per image, up to 2048 px, shared by mini / full / wide player and notification. */
    ARTWORK("a", 60),
}

data class TierUsage(val bytes: Long, val files: Int)

/**
 * Disk cache of images under [root]: `t/` thumbnails (a 128 px and a 256 px file per image) and `a/` player artwork (one
 * file per image). One total limit, [limitBytes] (the user's choice), is shared by both.
 *
 *  - A file's name is made from the image's URI (source id, path and the `?v=<size>-<mtime>` version) and, for
 *    thumbnails, the size tier — never credentials. A file is used until the image itself changes (its version changes);
 *    nothing else (settings switches, the connection settings of the source) invalidates it. Only Clear or the clean-up
 *    below deletes files.
 *  - Clean-up ([trim]): after enough new data was written (and once per process, and when the limit is lowered) the least
 *    recently used files are deleted on [scope] until the cache is back under three quarters of the limit. A tier that
 *    holds more than its [ImageTier.share] of that gives way first, so a tier can use the other's unused room but never
 *    pushes it below its own share. A hit refreshes the file's timestamp (at most once per [TOUCH_INTERVAL_MS]), which is
 *    the "recently used" order.
 *  - [legacyDirs] (the cache of earlier versions, whose sizes and layout are no longer used) are deleted once on [scope].
 *
 * Nothing here touches the caller's thread except computing a path and, for a hit, one `setLastModified`.
 */
class ImageDiskCache(
    private val root: File,
    private val scope: CoroutineScope,
    private val limitBytes: () -> Long,
    legacyDirs: List<File> = emptyList(),
    private val trimEveryBytes: Long = TRIM_EVERY_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val trimming = AtomicBoolean(false)
    private val writtenSinceTrim = AtomicLong(0)
    private val everTrimmed = AtomicBoolean(false)
    private val legacy = legacyDirs

    init {
        if (legacy.isNotEmpty()) scope.launch { legacy.forEach { it.deleteRecursively() } }
    }

    /** The cache file of [uri] in [tier]; [bucket] is the thumbnail size ([SMALL] or [LARGE]), 0 for artwork. */
    fun file(tier: ImageTier, uri: String, bucket: Int = 0): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$uri|$bucket".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(root, tier.dir), digest + if (tier == ImageTier.THUMB) ".jpg" else ".img")
    }

    /** The thumbnail size a request of [px] is served at: 128 px up to that size, otherwise 256 px (also when the size is unknown). */
    fun thumbBucket(px: Int): Int = if (px in 1..SMALL) SMALL else LARGE

    /** Call when [f] was found and is used: keeps it among the recently used files. */
    fun markUsed(f: File) {
        val t = now()
        if (t - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(t)
    }

    /** Writes [bytes] to [f] through a temporary file (a reader never sees half a file) and accounts for it. */
    fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
        stored(f)
    }

    /** Call after [f] was written: accounts for its size and starts a clean-up when enough was added. */
    fun stored(f: File) {
        val total = writtenSinceTrim.addAndGet(f.length())
        if (!everTrimmed.get() || total >= trimEveryBytes) requestTrim()
    }

    /** Call after the limit was changed: a lower limit takes effect now. */
    fun onLimitChanged() = requestTrim()

    /** Bytes and number of files of [tier] (walks the directory: call from an I/O thread). */
    fun usage(tier: ImageTier): TierUsage {
        val files = File(root, tier.dir).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return TierUsage(0, 0)
        return TierUsage(files.sumOf { it.length() }, files.size)
    }

    /** Deletes every cached image (both tiers). */
    fun clear() {
        root.deleteRecursively()
        legacy.forEach { it.deleteRecursively() }
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

    /** Deletes unfinished writes, then least recently used files while the total is above the limit. Runs on an I/O thread. */
    internal fun trim() {
        val t = now()
        val tiers = ImageTier.values()
        val lists = HashMap<ImageTier, MutableList<File>>()
        for (tier in tiers) {
            val all = File(root, tier.dir).listFiles()?.filter { it.isFile } ?: emptyList()
            // A write that never finished (process killed between write and rename).
            all.filter { it.name.endsWith(".tmp") && t - it.lastModified() > TMP_STALE_MS }.forEach { it.delete() }
            lists[tier] = all.filter { !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }.toMutableList()
        }
        val sizes = tiers.associateWith { tier -> lists.getValue(tier).sumOf { it.length() } }.toMutableMap()
        var total = sizes.values.sum()
        val limit = limitBytes()
        if (total <= limit) return
        val target = limit / 4 * 3
        val shares = tiers.sumOf { it.share }
        while (total > target) {
            // The tier furthest above its share of the target gives way first.
            val victim = tiers.filter { lists.getValue(it).isNotEmpty() }
                .maxByOrNull { sizes.getValue(it) - target * it.share / shares } ?: break
            val f = lists.getValue(victim).removeAt(0)
            val len = f.length()
            if (f.delete()) { sizes[victim] = sizes.getValue(victim) - len; total -= len }
        }
    }

    companion object {
        /** The two thumbnail sizes (px of the longer side). Browser requests larger than [SMALL] are served the [LARGE] one. */
        const val SMALL = 128
        const val LARGE = 256

        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
        /** New data written between two clean-ups (the clean-up lists the directories, so it is not run per file). */
        const val TRIM_EVERY_BYTES = 4L * 1024 * 1024
        private const val TMP_STALE_MS = 60 * 60 * 1000L
    }
}
