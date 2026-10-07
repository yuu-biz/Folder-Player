package com.wing.folderplayer.data.source

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class ReadKind { IMAGE, AUDIO_META, OTHER }

/**
 * A rough account of what the app asked network sources for since the process started (or the last [reset]): bytes
 * read through the file layer by kind and source, folder lists, and the image caches' hits, misses and fetches. It
 * counts requests made by the app's own code ([CountingFileSystem]), not bytes on the wire: no protocol overhead,
 * retransmission or other apps. Plain atomic counters, so recording never blocks playback or the UI. Keys are source ids
 * (never credentials); the Settings page shows the source names.
 */
object NetworkStats {
    class Snapshot(
        val since: Long,
        val bytes: Map<ReadKind, Long>,
        val perSource: Map<String, Long>,
        val folderLists: Long,
        val thumbnailsFetched: Long,
        val artworkFetched: Long,
        val thumbHits: Long,
        val thumbMisses: Long,
        val artworkHits: Long,
        val artworkMisses: Long,
    ) {
        val totalBytes: Long get() = bytes.values.sum()
        val hits: Long get() = thumbHits + artworkHits
        val misses: Long get() = thumbMisses + artworkMisses
        /** Hits / (hits + misses), or null before the first lookup. */
        val hitRate: Double? get() = (hits + misses).takeIf { it > 0 }?.let { hits.toDouble() / it }
    }

    private val kindBytes = ReadKind.values().associateWith { AtomicLong() }
    private val perSource = ConcurrentHashMap<String, AtomicLong>()
    private val folderLists = AtomicLong()
    private val thumbnailsFetched = AtomicLong()
    private val artworkFetched = AtomicLong()
    private val thumbHits = AtomicLong()
    private val thumbMisses = AtomicLong()
    private val artworkHits = AtomicLong()
    private val artworkMisses = AtomicLong()
    @Volatile private var since = System.currentTimeMillis()

    /** Images by extension; audio and what goes with it (lyrics, cue sheets, album info, the favourites file) as one group; the rest. */
    fun kindOf(path: String): ReadKind {
        val ext = SourcePath.extension(path)
        return when {
            MediaTypes.isImage(path) -> ReadKind.IMAGE
            MediaTypes.isAudio(path) || MediaTypes.isLyric(path) || MediaTypes.isCue(path) || ext in METADATA_EXTENSIONS -> ReadKind.AUDIO_META
            else -> ReadKind.OTHER
        }
    }

    fun addRead(sourceId: String, kind: ReadKind, bytes: Long) {
        if (bytes <= 0) return
        kindBytes.getValue(kind).addAndGet(bytes)
        perSource.computeIfAbsent(sourceId) { AtomicLong() }.addAndGet(bytes)
    }

    fun folderListed() { folderLists.incrementAndGet() }
    fun thumbnailHit() { thumbHits.incrementAndGet() }
    fun thumbnailMiss() { thumbMisses.incrementAndGet() }
    fun artworkHit() { artworkHits.incrementAndGet() }
    fun artworkMiss() { artworkMisses.incrementAndGet() }
    /** A thumbnail was made from an image read from its source. */
    fun thumbnailFetched() { thumbnailsFetched.incrementAndGet() }
    /** Player artwork was made from an image read from its source. */
    fun artworkFetched() { artworkFetched.incrementAndGet() }

    fun snapshot() = Snapshot(
        since, kindBytes.mapValues { it.value.get() }, perSource.mapValues { it.value.get() },
        folderLists.get(), thumbnailsFetched.get(), artworkFetched.get(),
        thumbHits.get(), thumbMisses.get(), artworkHits.get(), artworkMisses.get(),
    )

    fun reset() {
        kindBytes.values.forEach { it.set(0) }
        perSource.clear()
        listOf(folderLists, thumbnailsFetched, artworkFetched, thumbHits, thumbMisses, artworkHits, artworkMisses).forEach { it.set(0) }
        since = System.currentTimeMillis()
    }

    private val METADATA_EXTENSIONS = setOf("nfo", "txt", "json", "m3u", "m3u8")
}

/**
 * A network source's file system with the reads counted in [NetworkStats]. Everything else is the wrapped file system's,
 * unchanged. Reads through a sequential handle and through a random-access reader are both counted (a reader that falls
 * back to reopening the file goes through the wrapped file system's own handle, so nothing is counted twice).
 */
class CountingFileSystem(private val inner: SourceFileSystem) : SourceFileSystem by inner {
    private val sourceId = inner.config.id

    override fun list(path: String): List<MusicFile> {
        NetworkStats.folderListed()
        return inner.list(path)
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput =
        CountedInput(inner.openRead(path, offset, length), sourceId, NetworkStats.kindOf(path))

    override fun openRandomAccess(path: String): RandomAccessReader =
        CountedReader(inner.openRandomAccess(path), sourceId, NetworkStats.kindOf(path))

    private class CountedInput(private val input: SourceInput, private val sourceId: String, private val kind: ReadKind) : SourceInput() {
        override val length: Long get() = input.length
        override fun read(): Int = input.read().also { if (it >= 0) NetworkStats.addRead(sourceId, kind, 1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { NetworkStats.addRead(sourceId, kind, it.toLong()) }
        override fun skip(n: Long): Long = input.skip(n)
        override fun available(): Int = input.available()
        override fun close() = input.close()
    }

    private class CountedReader(private val reader: RandomAccessReader, private val sourceId: String, private val kind: ReadKind) : RandomAccessReader {
        override val size: Long get() = reader.size
        override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int =
            reader.read(position, buffer, offset, len).also { NetworkStats.addRead(sourceId, kind, it.toLong()) }
        override fun close() = reader.close()
    }
}
