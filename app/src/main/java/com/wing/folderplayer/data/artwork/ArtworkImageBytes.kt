package com.wing.folderplayer.data.artwork

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef

/**
 * Bytes of images that were read and decoded successfully while a cover was validated, kept in memory (bounded, least
 * recently used first out) so that displaying the cover does not read the source a second time. This matters for network
 * sources, where one read is a transfer.
 *
 * Keys are image URIs with their `?v=<size>-<mtime>` version and the source revision (see [ArtworkImageBytes.keyOf]); a
 * key holds the source id and path only, never credentials. Only complete, successfully read images are stored: a read
 * that failed, was cancelled or lost permission leaves nothing behind. Images above [maxItemBytes] are not kept (they are
 * read again for display: the safe fallback), and the total never exceeds [maxTotalBytes].
 */
class ValidatedImageCache(
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxItemBytes: Int = MAX_ITEM_BYTES,
) {
    private val map = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}
    private var total = 0L

    /** Stores [bytes] under [key]; false (and nothing stored) when it is above the per-image limit. */
    @Synchronized
    fun put(key: String, bytes: ByteArray): Boolean {
        if (bytes.size > maxItemBytes || bytes.size > maxTotalBytes) return false
        map.remove(key)?.let { total -= it.size }
        map[key] = bytes
        total += bytes.size
        val it = map.entries.iterator()
        while (total > maxTotalBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            total -= eldest.value.size
            it.remove()
        }
        return true
    }

    @Synchronized fun get(key: String): ByteArray? = map[key]

    @Synchronized fun removePrefix(prefix: String) {
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key.startsWith(prefix)) { total -= e.value.size; it.remove() } }
    }

    @Synchronized fun clear() { map.clear(); total = 0 }

    @get:Synchronized val totalBytes: Long get() = total
    @get:Synchronized val count: Int get() = map.size

    companion object {
        const val MAX_TOTAL_BYTES = 12L * 1024 * 1024
        const val MAX_ITEM_BYTES = 3 * 1024 * 1024
    }
}

/**
 * Reads image files for the two users of one image: cover validation (is it decodable?) and display (Coil fetcher,
 * notification bitmap). Whatever one of them read is handed to the other through [cache].
 *
 * Invariant: everything in [cache] decoded successfully ([decodes]) and had the size its version key states, whichever
 * side read it. So a cache hit means "valid" for [validate], and a glitch (cut connection, an error page, a file being
 * replaced) read for display is never kept: the next read goes to the source again and recovers.
 *
 * [read] fetches the whole file and throws for I/O errors; [revisionOf] gives the source's connection revision, so an
 * edited source does not serve bytes read from its old settings; [decodes] tells whether bytes are a decodable image.
 */
class ArtworkImageBytes(
    val cache: ValidatedImageCache,
    private val read: (SourceRef) -> ByteArray,
    private val revisionOf: (String) -> Int,
    private val decodes: (ByteArray) -> Boolean,
    /** Called with the bytes of an image that was read from the source and decodes (also those too large for [cache]). */
    private val onValidated: (MusicFile, ByteArray) -> Unit = { _, _ -> },
) {
    /** Cache key of an image URI as used by the display side (the URI carries the file's size / mtime as `?v=`). */
    fun keyOf(imageUri: String): String {
        val ref = com.wing.folderplayer.data.source.SourceUris.parse(imageUri)
        return imageUri + "|r" + (ref?.let { revisionOf(it.sourceId) } ?: 0)
    }

    /**
     * Reads [entry] (or finds it in the cache) and returns whether it decodes. A decodable image of the size the listing
     * gave is kept for [load]. I/O exceptions propagate and leave nothing cached; a broken image is not cached either.
     */
    fun validate(entry: MusicFile): Boolean {
        val ref = SourceRef(entry.sourceId, entry.path)
        val key = keyOf(ImageUris.of(ref, entry))
        if (cache.get(key) != null) return true // only valid images are ever stored
        val bytes = read(ref)
        if (!decodes(bytes)) return false
        keepIfConsistent(key, bytes, entry.size)
        runCatching { onValidated(entry, bytes) } // a by-product (the thumbnail): never a reason to fail the validation
        return true
    }

    /**
     * The bytes behind [imageUri]: from the cache when validation (or an earlier display) already read them, else from
     * the source. A read is kept for the next user only when the URI is versioned, the bytes decode and their size is the
     * one in the version (otherwise the key would describe another file). The caller gets the bytes in any case: a
     * broken image is for its own decoder to reject. An unversioned URI is never kept (it cannot tell when the file changes).
     */
    fun load(imageUri: String, ref: SourceRef): ByteArray {
        val key = if (imageUri.contains("?v=")) keyOf(imageUri) else null
        key?.let { cache.get(it) }?.let { return it }
        val bytes = read(ref)
        if (key != null && decodes(bytes)) keepIfConsistent(key, bytes, versionedSize(imageUri))
        return bytes
    }

    private fun keepIfConsistent(key: String, bytes: ByteArray, expectedSize: Long) {
        if (expectedSize <= 0 || bytes.size.toLong() == expectedSize) cache.put(key, bytes)
    }

    /** The file size in `?v=<size>-<mtime>` (see [ImageUris.of]); -1 when the URI has none. */
    private fun versionedSize(imageUri: String): Long =
        imageUri.substringAfter("?v=", "").substringBefore('-').toLongOrNull() ?: -1
}
