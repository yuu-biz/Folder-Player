package com.wing.folderplayer.data.metadata

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.TrackTags
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativeDecoderException
import com.wing.folderplayer.playback.NativeIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** "m:ss", or "h:mm:ss" from one hour on. */
object DurationFormat {
    fun format(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Track lengths for the browser list, read from the file's headers (MediaMetadataRetriever, or the FFmpeg decoder for
 * WMA/APE/DSF/DFF) only for rows on screen, for a folder sorted by length, or for an explicit scan ([startScan]). Local
 * and SAF always; network sources follow the thumbnail switches (per protocol, Wi-Fi only), except that a scan the user
 * asked for reads them whatever the switch (still on Wi-Fi only when that is set). Results are kept by source, path,
 * size and modification time, in memory and on disk, with the title, artist and album read in the same pass (the
 * songs list, [songs]).
 *
 * Only two outcomes are kept: a length, and "this file cannot be parsed" (looked at again after [UNKNOWN_RETRY_MS] or
 * on refresh, [forgetUnknown]). A failure to read (connection, authentication, permission, cancellation) is never
 * kept, so the same file gets its length once it can be read.
 *
 * Files move and disappear behind the app's back: a key never matches another file (a changed file has another size or
 * time), a file moved elsewhere on its source is found again by its name, size and time ([cached]) without being read,
 * and the entries of files that are no longer there are dropped when a scan or the browser lists their folder
 * ([onFolderListed]); the index is bounded ([MAX_ENTRIES], least recently used first).
 */
class DurationRepository internal constructor(
    context: Context,
    /** Where the index is kept (tests use their own directory). */
    dir: File = context.filesDir,
    /** Lists a folder for a scan (tests count or fail it). */
    private val listFolder: (SourceRef) -> List<MusicFile> = { ref -> SourceRegistry.fileSystem(ref).list(ref.path) },
    /** Files of one source a scan reads at the same time. */
    private val scanParallel: (String) -> Int = { id -> parallelReads(SourceRegistry.get(id)?.type) },
    /** Opens a file for reading (tests wrap it to simulate failures). */
    private val openReader: (MusicFile) -> RandomAccessReader = { f -> SourceRegistry.fileSystem(f.ref).openRandomAccess(f.path) },
) {
    private val thumbnails = ThumbnailRepository.get(context)
    private val gson = Gson()
    private val indexFile = File(dir, "duration-index-v2.json")
    /** dev2 kept every failure as 0 ("unknown"): its known lengths are taken over, its unknowns are not. */
    private val legacyIndexFile = File(dir, "duration-index.json")
    /** The rows on screen and a list sorted by length (a scan has its own readers). */
    private val semaphore = Semaphore(3)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSave: Job? = null

    /**
     * known: key to ms; unknown: key to when it was found unparsable; tags: key to the tags read with the length (absent:
     * kept by a version before 1.7.1). Least recently used first; bounded. [moved]: a file's source, name, size and time
     * to its key in [known], to find a file that moved (rebuilt on load).
     */
    private class Index(
        val known: LinkedHashMap<String, Long>,
        val unknown: LinkedHashMap<String, Long>,
        val tags: HashMap<String, TrackTags>,
        val moved: HashMap<String, String>,
    )
    /** On disk; tags as [title, artist, album, track]. Version 2 had no tags (older versions ignore them). */
    private data class IndexFile(
        val version: Int = 3,
        val known: Map<String, Long>? = null,
        val unknown: Map<String, Long>? = null,
        val tags: Map<String, List<String>>? = null,
    )

    private val index: Index by lazy { load() }

    private val _revision = MutableStateFlow(0)
    /** Bumped when the index is written (lengths or tags added, entries dropped): the songs list is built again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun key(file: MusicFile) = "${file.sourceId}|${file.path}|${file.size}|${file.lastModified}"

    /** The move key of [file]; null when its size or time is not known (too many files would share it). */
    private fun movedKey(file: MusicFile): String? =
        if (file.size > 0 && file.lastModified > 0) "${file.sourceId}|${file.name}|${file.size}|${file.lastModified}" else null

    /** Source id, path, size and time of an index key (`sourceId|path|size|mtime`; a path may hold `|`, an id does not). */
    private class KeyParts(val sourceId: String, val path: String, val size: Long, val time: Long)

    private fun parts(key: String): KeyParts? {
        val timeStart = key.lastIndexOf('|')
        val sizeStart = key.lastIndexOf('|', timeStart - 1)
        val idEnd = key.indexOf('|')
        if (idEnd < 0 || sizeStart <= idEnd) return null
        val size = key.substring(sizeStart + 1, timeStart).toLongOrNull() ?: return null
        val time = key.substring(timeStart + 1).toLongOrNull() ?: return null
        return KeyParts(key.substring(0, idEnd), key.substring(idEnd + 1, sizeStart), size, time)
    }

    private fun movedKey(key: String): String? {
        val p = parts(key) ?: return null
        return if (p.size > 0 && p.time > 0) "${p.sourceId}|${SourcePath.name(p.path)}|${p.size}|${p.time}" else null
    }

    /** Caller holds the index lock. [tags] null: keep what there is. */
    private fun putKnown(idx: Index, key: String, ms: Long, file: MusicFile, tags: TrackTags?) {
        idx.known[key] = ms
        idx.unknown.remove(key)
        if (tags != null) idx.tags[key] = tags
        movedKey(file)?.let { idx.moved[it] = key }
        while (idx.known.size > MAX_ENTRIES) {
            val oldest = idx.known.keys.first()
            removeKnown(idx, oldest)
        }
    }

    /** Caller holds the index lock. */
    private fun removeKnown(idx: Index, key: String): Long? {
        val ms = idx.known.remove(key)
        idx.tags.remove(key)
        movedKey(key)?.let { if (idx.moved[it] == key) idx.moved.remove(it) }
        return ms
    }

    /**
     * The length known for [file] without reading anything: its own entry, or that of the same file (name, size and time)
     * found elsewhere on its source, which is then kept under this path too. The first call loads the index from disk:
     * make it off the main thread ([warmUp]).
     */
    fun cached(file: MusicFile): Long? {
        if (file.isDirectory || !MediaTypes.isAudio(file.name)) return null
        val idx = index
        val key = key(file)
        val ms = synchronized(idx) {
            idx.known[key]?.let { return it }
            val other = movedKey(file)?.let { idx.moved[it] } ?: return null
            val ms = idx.known[other] ?: return null
            putKnown(idx, key, ms, file, idx.tags[other])
            ms
        }
        scheduleSave()
        return ms
    }

    /** Known with its tags (read since 1.7.1): a scan has nothing to read for [file]. */
    private fun complete(file: MusicFile): Boolean {
        if (cached(file) == null) return false
        val idx = index
        return synchronized(idx) { key(file) in idx.tags }
    }

    /** [files] with the lengths that are known ([cached]); songs only, the rest as they are. */
    fun withCachedLengths(files: List<MusicFile>): List<MusicFile> =
        files.map { f -> if (f.isDirectory || !MediaTypes.isAudio(f.name)) f else f.copy(durationMs = cached(f)) }

    /** Loads the index (from disk, once). */
    fun warmUp() { index }

    /** Length in ms, or null when not shown (not audio, switched off, Wi-Fi only, unreadable now or unparsable). */
    suspend fun duration(file: MusicFile): Long? = (lookup(file, explicit = false) as? Outcome.Known)?.ms

    private sealed interface Outcome {
        data class Known(val ms: Long) : Outcome
        /** Not audio, switched off for the source, or Wi-Fi required. */
        object NotAllowed : Outcome
        object Unparsable : Outcome
        /** Could not be read now (connection, authentication, permission): nothing kept. */
        object Failed : Outcome
    }

    /** [withTags]: a length kept without tags (before 1.7.1) is read again for them; [gate]: the readers to wait for. */
    private suspend fun lookup(file: MusicFile, explicit: Boolean, withTags: Boolean = false, gate: Semaphore = semaphore): Outcome = withContext(Dispatchers.IO) {
        if (file.isDirectory || !MediaTypes.isAudio(file.name)) return@withContext Outcome.NotAllowed
        val cfg = SourceRegistry.get(file.sourceId) ?: return@withContext Outcome.NotAllowed
        if (cfg.isNetwork) {
            val s = thumbnails.settings
            if ((!explicit && !s.thumbnailsEnabled(cfg.type)) || (s.wifiOnly && !thumbnails.isOnWifi())) return@withContext Outcome.NotAllowed
        }
        val key = key(file)
        val idx = index
        cached(file)?.let { if (!withTags || complete(file)) return@withContext Outcome.Known(it) }
        synchronized(idx) {
            idx.unknown[key]?.let { at -> if (System.currentTimeMillis() - at < UNKNOWN_RETRY_MS) return@withContext Outcome.Unparsable }
        }
        val result = try {
            gate.withPermit { runInterruptible { read(file) } }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.javaClass.simpleName} ${e.message}")
            return@withContext Outcome.Failed
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.message}")
            return@withContext Outcome.Failed
        }
        ensureActive() // a cancelled look keeps nothing
        synchronized(idx) {
            if (result.ms > 0) putKnown(idx, key, result.ms, file, result.tags) else idx.unknown[key] = System.currentTimeMillis()
            while (idx.unknown.size > MAX_ENTRIES) idx.unknown.remove(idx.unknown.keys.first())
        }
        scheduleSave()
        if (result.ms > 0) Outcome.Known(result.ms) else Outcome.Unparsable
    }

    /** Files found unparsable are looked at again (browser refresh). */
    fun forgetUnknown() {
        val idx = index
        synchronized(idx) {
            if (idx.unknown.isEmpty()) return
            idx.unknown.clear()
        }
        scheduleSave()
    }

    /** The app renamed or moved [file] to [moved] (null: deleted): its length goes with it, or is dropped. */
    fun onFileOp(file: MusicFile, moved: MusicFile?) {
        if (file.isDirectory) return
        val idx = index
        synchronized(idx) {
            val tags = idx.tags[key(file)]
            val ms = removeKnown(idx, key(file))
            idx.unknown.remove(key(file))
            if (ms != null && moved != null) putKnown(idx, key(moved), ms, moved, tags)
        }
        scheduleSave()
    }

    /** The browser listed [folder] in full: entries of files no longer in it (moved, deleted, changed) are dropped. */
    fun onFolderListed(folder: SourceRef, entries: List<MusicFile>) {
        val present = entries.mapNotNullTo(HashSet()) { if (it.isDirectory) null else key(it) }
        prune(mapOf(folder.sourceId to setOf(folder.path)), present)
    }

    // ---------------- songs list ----------------

    /**
     * Every song the index knows on the sources there are, with its length and tags: what scans (and the lists that were
     * on screen) have read. A file that moved and was seen at both places is listed once, at the place seen last.
     */
    fun songs(): List<MusicFile> {
        val sources = SourceRegistry.sources.value.mapTo(HashSet()) { it.id }
        val idx = index
        val entries = synchronized(idx) { idx.known.entries.map { Triple(it.key, it.value, idx.tags[it.key]) } }
        // Most recently used last: the place a moved file was seen at last wins.
        val byMoved = HashMap<String, Int>()
        val out = ArrayList<MusicFile>(entries.size)
        for ((key, ms, tags) in entries) {
            val p = parts(key) ?: continue
            if (p.sourceId !in sources) continue
            val f = MusicFile(SourcePath.name(p.path), p.path, false, p.size, p.time, p.sourceId, durationMs = ms, tags = tags)
            val m = movedKey(key)
            val earlier = m?.let { byMoved[it] }
            if (earlier != null) out[earlier] = f else { m?.let { byMoved[it] = out.size }; out += f }
        }
        return out
    }

    // ---------------- scan ----------------

    /**
     * An explicit scan: [folders] listed and [songs] seen, of which [read] were read now (the rest were known),
     * [notRead] could not be read (or not now: Wi-Fi only, server gone) and [unparsable] have no length; [skippedFolders]
     * could not be listed.
     */
    data class ScanProgress(
        val running: Boolean = false,
        val folders: Int = 0,
        val songs: Int = 0,
        val read: Int = 0,
        val notRead: Int = 0,
        val unparsable: Int = 0,
        val skippedFolders: Int = 0,
        /** The folder being listed (its name). */
        val current: String = "",
        val cancelled: Boolean = false,
        /** Stopped at [MAX_SCAN_SONGS] or [MAX_SCAN_FOLDERS]. */
        val truncated: Boolean = false,
        /** Bumped when a scan ends (the browser sorts again). */
        val finished: Int = 0,
        /** A scan ended and its result has not been dismissed. */
        val showResult: Boolean = false,
    )

    private val _scan = MutableStateFlow(ScanProgress())
    val scan: StateFlow<ScanProgress> = _scan.asStateFlow()
    private var scanJob: Job? = null

    /**
     * Reads the lengths and tags of the songs in [roots] and the folders below them, a few files of a source at a time
     * ([parallelReads]; the rows on screen keep their own readers). Known files are not read again, so a scan stopped
     * half-way goes on where it was the next time. A source that cannot be reached is left after its first failures. The
     * entries of files that are no longer in a listed folder are dropped. False when a scan is already running.
     */
    fun startScan(roots: List<SourceRef>): Boolean = synchronized(this) {
        if (scanJob?.isActive == true) return false
        val distinct = roots.distinct().filter { r ->
            roots.none { o -> o != r && o.sourceId == r.sourceId && SourcePath.isAncestorOrSelf(o.path, r.path) }
        }
        val finished = _scan.value.finished
        _scan.value = ScanProgress(running = true, finished = finished)
        scanJob = ioScope.launch {
            try {
                runScan(distinct)
            } finally {
                flush()
                _scan.update { it.copy(running = false, current = "", finished = finished + 1, showResult = true) }
            }
        }
        true
    }

    fun cancelScan() {
        if (scanJob?.isActive != true) return
        _scan.update { it.copy(cancelled = true) }
        scanJob?.cancel()
    }

    /** The result of the last scan is no longer shown. */
    fun dismissScanResult() {
        if (!_scan.value.running) _scan.update { it.copy(showResult = false) }
    }

    private suspend fun runScan(roots: List<SourceRef>) = coroutineScope {
        // Sources left: not reachable, or Wi-Fi required. Read by the readers, set by both sides.
        val down = ConcurrentHashMap.newKeySet<String>()
        // Per source, the folders listed; and the keys of the songs in them (what is no longer there is dropped).
        val listed = HashMap<String, HashSet<String>>()
        val present = HashSet<String>()
        // Per source: its readers, and the reads that failed since the last one that worked.
        val gates = ConcurrentHashMap<String, Semaphore>()
        val failures = ConcurrentHashMap<String, AtomicInteger>()
        val queue = Channel<MusicFile>(capacity = QUEUE)

        val lister = launch {
            try {
                var folders = 0
                var songs = 0
                for (root in roots) {
                    val pending = ArrayDeque<Pair<SourceRef, Int>>().apply { add(root to 0) }
                    val seen = HashSet<String>()
                    while (pending.isNotEmpty()) {
                        ensureActive()
                        if (songs >= MAX_SCAN_SONGS || folders >= MAX_SCAN_FOLDERS) { _scan.update { it.copy(truncated = true) }; break }
                        val (folder, depth) = pending.removeFirst()
                        if (folder.sourceId in down || !seen.add(folder.path)) continue
                        _scan.update { it.copy(current = SourcePath.name(folder.path).ifEmpty { SourceRegistry.get(folder.sourceId)?.name.orEmpty() }) }
                        val entries = try {
                            runInterruptible { listFolder(folder) }
                        } catch (e: com.wing.folderplayer.data.source.SourceException.NotFound) {
                            _scan.update { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                        } catch (e: com.wing.folderplayer.data.source.SourceException.PermissionDenied) {
                            _scan.update { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // An unreachable source (a NAS that is off) would cost a timeout per folder: leave it.
                            android.util.Log.w(TAG, "scan: ${folder.path} not listed, source left: ${e.javaClass.simpleName} ${e.message}")
                            down += folder.sourceId
                            _scan.update { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                        }
                        folders++
                        listed.getOrPut(folder.sourceId) { HashSet() } += folder.path
                        _scan.update { it.copy(folders = folders) }
                        for (f in entries.sortedBy { it.name.lowercase() }) {
                            if (f.isDirectory) { if (depth < MAX_SCAN_DEPTH) pending.add(f.ref to depth + 1); continue }
                            if (!MediaTypes.isAudio(f.name)) continue
                            songs++
                            present += key(f)
                            if (complete(f)) { _scan.update { it.copy(songs = songs) }; continue }
                            _scan.update { it.copy(songs = songs) }
                            queue.send(f) // waits while the readers are behind: the listing keeps a little ahead
                        }
                    }
                }
            } finally {
                queue.close()
            }
        }

        val readers = List(MAX_READERS) {
            launch {
                for (f in queue) {
                    val outcome = if (f.sourceId in down) Outcome.Failed else {
                        val gate = gates.getOrPut(f.sourceId) { Semaphore(scanParallel(f.sourceId).coerceIn(1, MAX_READERS)) }
                        lookup(f, explicit = true, withTags = true, gate = gate)
                    }
                    val failed = failures.getOrPut(f.sourceId) { AtomicInteger() }
                    when (outcome) {
                        // Wi-Fi required: the rest of this source is not read either.
                        Outcome.NotAllowed -> down += f.sourceId
                        // The server stopped answering: the rest of it would fail the same way, a timeout each. Its
                        // readers fail together, so this counts failures since the last read that worked.
                        Outcome.Failed -> if (failed.incrementAndGet() >= MAX_FAILURES) down += f.sourceId
                        else -> failed.set(0)
                    }
                    _scan.update {
                        when (outcome) {
                            is Outcome.Known -> it.copy(read = it.read + 1)
                            Outcome.Unparsable -> it.copy(unparsable = it.unparsable + 1)
                            else -> it.copy(notRead = it.notRead + 1)
                        }
                    }
                }
            }
        }
        lister.join()
        readers.forEach { it.join() }
        prune(listed, present)
    }

    /** Drops the entries of files that are no longer in the folders listed (or have changed since). */
    private fun prune(listed: Map<String, Set<String>>, present: Set<String>) {
        if (listed.isEmpty()) return
        val idx = index
        var removed = 0
        synchronized(idx) {
            for (map in listOf(idx.known, idx.unknown)) {
                val keys = map.keys.toList()
                for (k in keys) {
                    if (k in present) continue
                    val p = parts(k) ?: continue
                    val folders = listed[p.sourceId] ?: continue
                    if (SourcePath.parent(p.path) !in folders) continue
                    if (map === idx.known) removeKnown(idx, k) else map.remove(k)
                    removed++
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i(TAG, "$removed entries of files no longer there dropped")
            scheduleSave()
        }
    }

    private class ReadResult(val ms: Long, val tags: TrackTags?)

    /** Length in ms (0 if the file was read but cannot be parsed) and tags; throws [IOException] if it could not be read. */
    private fun read(file: MusicFile): ReadResult {
        val ext = SourcePath.extension(file.name)
        openReader(file).use { reader ->
            // FFmpeg is asked for the length only: these formats keep their tags blank in the list.
            if (ext in MediaTypes.EXTRA_AUDIO) return ReadResult(readNative(reader, ext), TrackTags.NONE)
            val source = ReaderDataSource(reader)
            val mmr = MediaMetadataRetriever()
            try {
                val ms = try {
                    mmr.setDataSource(source)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (e: RuntimeException) {
                    // The retriever reports a failed read the same way as a file it cannot parse.
                    source.failure?.let { throw it }
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("interrupted")
                    android.util.Log.w(TAG, "length of ${file.name} not parsable: ${e.javaClass.simpleName} ${e.message}")
                    return ReadResult(0L, null)
                }
                source.failure?.let { throw it }
                val length = ms?.takeIf { it > 0 } ?: return ReadResult(0L, null)
                // Read with the length, from what the retriever already holds.
                val tags = runCatching {
                    fun tag(k: Int) = mmr.extractMetadata(k)?.trim().orEmpty()
                    TrackTags(
                        title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifEmpty { tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                        album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').trim().toIntOrNull() ?: 0,
                    )
                }.getOrDefault(TrackTags.NONE)
                source.failure?.let { throw it }
                return ReadResult(length, tags)
            } finally {
                runCatching { mmr.release() }
            }
        }
    }

    private fun readNative(reader: RandomAccessReader, ext: String): Long {
        if (!NativeDecoder.isAvailable()) return 0L
        val io = NativeIo(reader)
        val handle = try {
            NativeDecoder.nativeOpen(io, ext, 96_000)
        } catch (e: NativeDecoderException) {
            // ERR_IO: reading failed (the cause is what the reader threw); it says nothing about the file.
            if (e.code == NativeDecoder.ERR_IO || io.lastError != null) throw (io.lastError as? IOException) ?: e
            return 0L // unsupported or corrupt
        }
        try {
            val info = NativeDecoder.nativeInfo(handle) // [rate, channels, durationUs, totalFrames, ...]
            return when {
                info[2] > 0 -> info[2] / 1000
                info[3] > 0 && info[0] > 0 -> info[3] * 1000 / info[0]
                else -> 0L
            }
        } finally {
            NativeDecoder.nativeClose(handle)
        }
    }

    /** MediaMetadataRetriever over any source (local, SAF, SMB, FTP, WebDAV) by random access; keeps a read failure. */
    private class ReaderDataSource(private val reader: RandomAccessReader) : MediaDataSource() {
        @Volatile var failure: IOException? = null
            private set
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            return try {
                reader.read(position, buffer, offset, size)
            } catch (e: IOException) {
                failure = e; -1
            } catch (e: RuntimeException) {
                failure = IOException(e); -1
            }
        }
        override fun getSize(): Long = reader.size
        override fun close() = Unit
    }

    private fun load(): Index {
        val known = LinkedHashMap<String, Long>(256, 0.75f, true)
        val unknown = LinkedHashMap<String, Long>(64, 0.75f, true)
        val tags = HashMap<String, TrackTags>()
        runCatching {
            if (indexFile.exists()) {
                val f = gson.fromJson(indexFile.readText(), IndexFile::class.java)
                f.known?.let { known.putAll(it) }
                f.unknown?.let { unknown.putAll(it) }
                f.tags?.forEach { (k, v) ->
                    if (k in known) tags[k] = TrackTags(v.getOrNull(0).orEmpty(), v.getOrNull(1).orEmpty(), v.getOrNull(2).orEmpty(), v.getOrNull(3)?.toIntOrNull() ?: 0)
                }
            } else if (legacyIndexFile.exists()) {
                val old = gson.fromJson<Map<String, Long>>(legacyIndexFile.readText(), object : TypeToken<Map<String, Long>>() {}.type).orEmpty()
                old.forEach { (k, v) -> if (v > 0) known[k] = v }
            }
            Unit
        }
        val moved = HashMap<String, String>(known.size * 2)
        for (k in known.keys) movedKey(k)?.let { moved[it] = k }
        return Index(known, unknown, tags, moved)
    }

    // Written on an I/O thread and coalesced, like the folder-image index. A save that is due is not put off by later
    // results: during a scan (many results a second) the index is still written every few seconds.
    private fun scheduleSave() {
        synchronized(saveLock) {
            if (pendingSave?.isActive == true) return
            pendingSave = ioScope.launch {
                delay(if (_scan.value.running) SCAN_SAVE_DELAY_MS else 1_000)
                write()
            }
        }
    }

    /** Writes the index now (the end of a scan). */
    private fun flush() {
        synchronized(saveLock) { pendingSave?.cancel(); pendingSave = null }
        write()
    }

    private fun write() {
        val idx = index
        val snapshot = synchronized(idx) {
            IndexFile(
                known = HashMap(idx.known),
                unknown = HashMap(idx.unknown),
                tags = idx.tags.mapValues { (_, t) -> listOf(t.title, t.artist, t.album, t.track.toString()) },
            )
        }
        synchronized(indexFile) {
            runCatching {
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (tmp.renameTo(indexFile)) legacyIndexFile.delete()
            }
        }
        _revision.update { it + 1 }
    }

    companion object {
        /** Lengths kept (about 6 MB on disk with their tags at most): a large library scanned once fits. */
        private const val MAX_ENTRIES = 30_000
        /** A file found unparsable is looked at again after this long (or on refresh). */
        private const val UNKNOWN_RETRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val SCAN_SAVE_DELAY_MS = 10_000L
        /** Readers of a scan in all; songs listed ahead of them. */
        private const val MAX_READERS = 6
        private const val QUEUE = 64
        /** Reads of one source that fail with none working in between: the source is left. */
        private const val MAX_FAILURES = 3
        /** Bounds of one scan (a link loop, a whole NAS by mistake). */
        const val MAX_SCAN_SONGS = 20_000
        const val MAX_SCAN_FOLDERS = 5_000
        const val MAX_SCAN_DEPTH = 24

        /**
         * Files of one source a scan reads at once: the phone's storage is limited by its CPU, a NAS answers several
         * requests on one SMB session or HTTP connection pool; an FTP server often allows only a few connections per
         * address (each read takes one).
         */
        fun parallelReads(type: SourceType?): Int = when (type) {
            SourceType.LOCAL, SourceType.SAF -> 6
            SourceType.SMB, SourceType.WEBDAV -> 4
            SourceType.FTP -> 2
            null -> 1
        }

        private const val TAG = "DurationRepository"
        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: DurationRepository? = null
        fun get(context: Context): DurationRepository =
            instance ?: synchronized(this) { instance ?: DurationRepository(context.applicationContext).also { instance = it } }
    }
}
