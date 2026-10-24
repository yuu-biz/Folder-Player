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
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativeDecoderException
import com.wing.folderplayer.playback.NativeIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
 * size and modification time, in memory and on disk.
 *
 * Only two outcomes are kept: a length, and "this file cannot be parsed" (looked at again after [UNKNOWN_RETRY_MS] or
 * on refresh, [forgetUnknown]). A failure to read (connection, authentication, permission, cancellation) is never
 * kept, so the same file gets its length once it can be read.
 *
 * Files move and disappear behind the app's back: a key never matches another file (a changed file has another size or
 * time), a file moved elsewhere on its source is found again by its name, size and time ([cached]) without being read,
 * and the entries of files that are no longer there are dropped when a scan lists their folder; the index is bounded
 * ([MAX_ENTRIES], least recently used first).
 */
class DurationRepository internal constructor(
    context: Context,
    /** Where the index is kept (tests use their own directory). */
    dir: File = context.filesDir,
    /** Lists a folder for a scan (tests count or fail it). */
    private val listFolder: (SourceRef) -> List<MusicFile> = { ref -> SourceRegistry.fileSystem(ref).list(ref.path) },
    /** Opens a file for reading (tests wrap it to simulate failures). */
    private val openReader: (MusicFile) -> RandomAccessReader = { f -> SourceRegistry.fileSystem(f.ref).openRandomAccess(f.path) },
) {
    private val thumbnails = ThumbnailRepository.get(context)
    private val gson = Gson()
    private val indexFile = File(dir, "duration-index-v2.json")
    /** dev2 kept every failure as 0 ("unknown"): its known lengths are taken over, its unknowns are not. */
    private val legacyIndexFile = File(dir, "duration-index.json")
    private val semaphore = Semaphore(3)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSave: Job? = null

    /**
     * known: key to ms; unknown: key to when it was found unparsable. Least recently used first; bounded. [moved]: a
     * file's source, name, size and time to its key in [known], to find a file that moved (rebuilt on load).
     */
    private class Index(val known: LinkedHashMap<String, Long>, val unknown: LinkedHashMap<String, Long>, val moved: HashMap<String, String>)
    private data class IndexFile(val version: Int = 2, val known: Map<String, Long>? = null, val unknown: Map<String, Long>? = null)

    private val index: Index by lazy { load() }

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

    /** Caller holds the index lock. */
    private fun putKnown(idx: Index, key: String, ms: Long, file: MusicFile) {
        idx.known[key] = ms
        idx.unknown.remove(key)
        movedKey(file)?.let { idx.moved[it] = key }
        while (idx.known.size > MAX_ENTRIES) {
            val oldest = idx.known.keys.first()
            idx.known.remove(oldest)
            movedKey(oldest)?.let { if (idx.moved[it] == oldest) idx.moved.remove(it) }
        }
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
            putKnown(idx, key, ms, file)
            ms
        }
        scheduleSave()
        return ms
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

    private suspend fun lookup(file: MusicFile, explicit: Boolean): Outcome = withContext(Dispatchers.IO) {
        if (file.isDirectory || !MediaTypes.isAudio(file.name)) return@withContext Outcome.NotAllowed
        val cfg = SourceRegistry.get(file.sourceId) ?: return@withContext Outcome.NotAllowed
        if (cfg.isNetwork) {
            val s = thumbnails.settings
            if ((!explicit && !s.thumbnailsEnabled(cfg.type)) || (s.wifiOnly && !thumbnails.isOnWifi())) return@withContext Outcome.NotAllowed
        }
        val key = key(file)
        val idx = index
        cached(file)?.let { return@withContext Outcome.Known(it) }
        synchronized(idx) {
            idx.unknown[key]?.let { at -> if (System.currentTimeMillis() - at < UNKNOWN_RETRY_MS) return@withContext Outcome.Unparsable }
        }
        val ms = try {
            semaphore.withPermit { runInterruptible { read(file) } }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.javaClass.simpleName} ${e.message}")
            return@withContext Outcome.Failed
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "length of ${file.name} not read now: ${e.message}")
            return@withContext Outcome.Failed
        }
        ensureActive() // a cancelled look keeps nothing
        synchronized(idx) {
            if (ms > 0) putKnown(idx, key, ms, file) else idx.unknown[key] = System.currentTimeMillis()
            while (idx.unknown.size > MAX_ENTRIES) idx.unknown.remove(idx.unknown.keys.first())
        }
        scheduleSave()
        if (ms > 0) Outcome.Known(ms) else Outcome.Unparsable
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
            val ms = idx.known.remove(key(file))
            idx.unknown.remove(key(file))
            movedKey(file)?.let { if (idx.moved[it] == key(file)) idx.moved.remove(it) }
            if (ms != null && moved != null) putKnown(idx, key(moved), ms, moved)
        }
        scheduleSave()
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
        /** The folder being read (its name). */
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
     * Reads the lengths of the songs in [roots] and the folders below them, one file at a time (a NAS is not flooded; the
     * rows on screen keep the other readers). Known files are not read again, so a scan stopped half-way goes on where it
     * was the next time. A source that cannot be reached is left after its first failures. The entries of files that are
     * no longer in a listed folder are dropped. False when a scan is already running.
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
                val p = _scan.value
                _scan.value = p.copy(running = false, current = "", cancelled = p.cancelled, finished = finished + 1, showResult = true)
            }
        }
        true
    }

    fun cancelScan() {
        if (scanJob?.isActive != true) return
        _scan.value = _scan.value.copy(cancelled = true)
        scanJob?.cancel()
    }

    /** The result of the last scan is no longer shown. */
    fun dismissScanResult() {
        if (!_scan.value.running) _scan.value = _scan.value.copy(showResult = false)
    }

    private fun progress(f: (ScanProgress) -> ScanProgress) { _scan.value = f(_scan.value) }

    private suspend fun runScan(roots: List<SourceRef>) {
        val down = HashSet<String>()
        // Per source, the folders listed; and the keys of the songs in them (what is no longer there is dropped).
        val listed = HashMap<String, HashSet<String>>()
        val present = HashSet<String>()
        var folders = 0
        var songs = 0
        for (root in roots) {
            val queue = ArrayDeque<Pair<SourceRef, Int>>().apply { add(root to 0) }
            val seen = HashSet<String>()
            while (queue.isNotEmpty()) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (songs >= MAX_SCAN_SONGS || folders >= MAX_SCAN_FOLDERS) { progress { it.copy(truncated = true) }; break }
                val (folder, depth) = queue.removeFirst()
                if (folder.sourceId in down || !seen.add(folder.path)) continue
                progress { it.copy(current = SourcePath.name(folder.path).ifEmpty { SourceRegistry.get(folder.sourceId)?.name.orEmpty() }) }
                val entries = try {
                    runInterruptible { listFolder(folder) }
                } catch (e: com.wing.folderplayer.data.source.SourceException.NotFound) {
                    progress { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                } catch (e: com.wing.folderplayer.data.source.SourceException.PermissionDenied) {
                    progress { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An unreachable source (a NAS that is off) would cost a timeout per folder: leave it.
                    android.util.Log.w(TAG, "scan: ${folder.path} not listed, source left: ${e.javaClass.simpleName} ${e.message}")
                    down += folder.sourceId
                    progress { it.copy(skippedFolders = it.skippedFolders + 1) }; continue
                }
                folders++
                listed.getOrPut(folder.sourceId) { HashSet() } += folder.path
                progress { it.copy(folders = folders) }
                var failedInARow = 0
                for (f in entries.sortedBy { it.name.lowercase() }) {
                    if (f.isDirectory) { if (depth < MAX_SCAN_DEPTH) queue.add(f.ref to depth + 1); continue }
                    if (!MediaTypes.isAudio(f.name)) continue
                    if (folder.sourceId in down) break
                    songs++
                    present += key(f)
                    val outcome = if (cached(f) != null) null else lookup(f, explicit = true)
                    progress {
                        when (outcome) {
                            null -> it.copy(songs = songs)
                            is Outcome.Known -> it.copy(songs = songs, read = it.read + 1)
                            Outcome.Unparsable -> it.copy(songs = songs, unparsable = it.unparsable + 1)
                            else -> it.copy(songs = songs, notRead = it.notRead + 1)
                        }
                    }
                    when (outcome) {
                        // Wi-Fi required: the rest of this source is not read either.
                        Outcome.NotAllowed -> down += folder.sourceId
                        // The server stopped answering: the rest of it would fail the same way, a timeout each.
                        Outcome.Failed -> if (++failedInARow >= 3) down += folder.sourceId
                        else -> failedInARow = 0
                    }
                }
            }
        }
        prune(listed, present)
    }

    /** Drops the entries of files that are no longer in the folders a scan listed (or have changed since). */
    private fun prune(listed: Map<String, Set<String>>, present: Set<String>) {
        if (listed.isEmpty()) return
        val idx = index
        var removed = 0
        synchronized(idx) {
            for (map in listOf(idx.known, idx.unknown)) {
                val keys = map.keys.iterator()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in present) continue
                    val p = parts(k) ?: continue
                    val folders = listed[p.sourceId] ?: continue
                    if (SourcePath.parent(p.path) !in folders) continue
                    keys.remove()
                    removed++
                    if (map === idx.known) movedKey(k)?.let { m -> if (idx.moved[m] == k) idx.moved.remove(m) }
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i(TAG, "scan: $removed entries of files no longer there dropped")
            scheduleSave()
        }
    }

    /** Length in ms, 0 if the file was read but cannot be parsed; throws [IOException] if it could not be read. */
    private fun read(file: MusicFile): Long {
        val ext = SourcePath.extension(file.name)
        openReader(file).use { reader ->
            if (ext in MediaTypes.EXTRA_AUDIO) return readNative(reader, ext)
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
                    return 0L
                }
                source.failure?.let { throw it }
                return ms?.takeIf { it > 0 } ?: 0L
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
        runCatching {
            if (indexFile.exists()) {
                val f = gson.fromJson(indexFile.readText(), IndexFile::class.java)
                f.known?.let { known.putAll(it) }
                f.unknown?.let { unknown.putAll(it) }
            } else if (legacyIndexFile.exists()) {
                val old = gson.fromJson<Map<String, Long>>(legacyIndexFile.readText(), object : TypeToken<Map<String, Long>>() {}.type).orEmpty()
                old.forEach { (k, v) -> if (v > 0) known[k] = v }
            }
            Unit
        }
        val moved = HashMap<String, String>(known.size * 2)
        for (k in known.keys) movedKey(k)?.let { moved[it] = k }
        return Index(known, unknown, moved)
    }

    // Written on an I/O thread and coalesced, like the folder-image index. A save that is due is not put off by later
    // results: during a scan (a result every few milliseconds) the index is still written every few seconds.
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
        val snapshot = synchronized(idx) { IndexFile(known = HashMap(idx.known), unknown = HashMap(idx.unknown)) }
        synchronized(indexFile) {
            runCatching {
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (tmp.renameTo(indexFile)) legacyIndexFile.delete()
            }
        }
    }

    companion object {
        /** Lengths kept (about 3 MB on disk at most): a large library scanned once fits. */
        private const val MAX_ENTRIES = 30_000
        /** A file found unparsable is looked at again after this long (or on refresh). */
        private const val UNKNOWN_RETRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val SCAN_SAVE_DELAY_MS = 10_000L
        /** Bounds of one scan (a link loop, a whole NAS by mistake). */
        const val MAX_SCAN_SONGS = 20_000
        const val MAX_SCAN_FOLDERS = 5_000
        const val MAX_SCAN_DEPTH = 24
        private const val TAG = "DurationRepository"
        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: DurationRepository? = null
        fun get(context: Context): DurationRepository =
            instance ?: synchronized(this) { instance ?: DurationRepository(context.applicationContext).also { instance = it } }
    }
}
