package com.wing.folderplayer.data.history

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.TrackTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A song (or a CUE sheet) of the playback history: what its row shows without listing its folder. [title], [artist] and
 * [album] are blank when the player did not know them; [durationMs] and [size] are 0 when not known. No credentials.
 */
data class HistoryEntry(
    val sourceId: String = "",
    val path: String = "",
    val name: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val size: Long = 0,
    /** When it was last played (epoch ms). */
    val playedAt: Long = 0,
) {
    val ref: SourceRef? get() = if (sourceId.isNotEmpty() && path.startsWith("/")) SourceRef(sourceId, path) else null
    val key: Pair<String, String> get() = sourceId to path
}

/** The history list: one entry per file, the one played last first, at most [MAX_ENTRIES]. */
object History {
    const val MAX_ENTRIES = 300

    fun record(entries: List<HistoryEntry>, entry: HistoryEntry, now: Long): List<HistoryEntry> =
        (listOf(entry.copy(playedAt = now)) + entries.filter { it.key != entry.key }).take(MAX_ENTRIES)

    fun remove(entries: List<HistoryEntry>, ref: SourceRef): List<HistoryEntry> = entries.filter { it.key != (ref.sourceId to ref.path) }

    fun clear(): List<HistoryEntry> = emptyList()

    /**
     * The entry a queue item makes: the song, or for a CUE track ([cuePath] set) the CUE sheet, whose track title is
     * not the sheet's. Null for an item that is not a file of a source (a file another app opened).
     */
    fun entryOf(mediaId: String?, cuePath: String?, title: CharSequence?, artist: CharSequence?, album: CharSequence?, durationMs: Long, size: Long): HistoryEntry? {
        val ref = SourceUris.parse(mediaId) ?: return null
        val cue = cuePath?.takeIf { SourceUris.isCueTrackId(mediaId) }?.let { runCatching { SourcePath.normalize(it) }.getOrNull() ?: return null }
        val path = cue ?: ref.path
        return HistoryEntry(
            sourceId = ref.sourceId,
            path = path,
            name = SourcePath.name(path),
            title = if (cue != null) "" else title?.toString()?.trim().orEmpty(),
            artist = artist?.toString()?.trim().orEmpty(),
            album = album?.toString()?.trim().orEmpty(),
            durationMs = if (cue != null) 0 else durationMs.coerceAtLeast(0),
            size = size.coerceAtLeast(0),
        )
    }

    /** The entry as the browser lists it: its title and length as the songs list keeps them. */
    fun asFile(e: HistoryEntry): MusicFile = MusicFile(
        name = e.name.ifEmpty { SourcePath.name(e.path) },
        path = e.path,
        isDirectory = false,
        size = e.size,
        sourceId = e.sourceId,
        durationMs = e.durationMs.takeIf { it > 0 },
        tags = TrackTags(title = e.title, artist = e.artist, album = e.album),
    )
}

data class HistoryData(val version: Int = HistoryCodec.VERSION, val items: List<HistoryEntry> = emptyList())

object HistoryCodec {
    const val VERSION = 1
    private val gson = Gson()

    fun encode(data: HistoryData): String = gson.toJson(data)

    /** Strict decode: anything that is not a {version, items[]} object throws; unreadable or repeated entries are left out. */
    fun decode(json: String): List<HistoryEntry> {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "root is not an object" }
        val o = root.asJsonObject
        val version = o.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: throw IllegalArgumentException("missing version")
        require(version <= VERSION) { "unsupported version $version" }
        val arr = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw IllegalArgumentException("missing items")
        val seen = HashSet<Pair<String, String>>()
        return arr.mapNotNull { el ->
            val e = runCatching { gson.fromJson(el, HistoryEntry::class.java) }.getOrNull() ?: return@mapNotNull null
            // Gson bypasses Kotlin null-safety: a missing field arrives as null.
            val sourceId: String? = e.sourceId
            val path: String? = e.path
            if (sourceId.isNullOrBlank() || path == null || !path.startsWith("/")) return@mapNotNull null
            val normalized = runCatching { SourcePath.normalize(path) }.getOrNull() ?: return@mapNotNull null
            if (!seen.add(sourceId to normalized)) return@mapNotNull null
            val name: String? = e.name
            val title: String? = e.title
            val artist: String? = e.artist
            val album: String? = e.album
            e.copy(path = normalized, name = name.orEmpty(), title = title.orEmpty(), artist = artist.orEmpty(), album = album.orEmpty())
        }.take(History.MAX_ENTRIES)
    }
}

/**
 * The playback history of this device (`files/history/history.json`), written by the player service and shown by the
 * browser. Writes go through a temporary file; an unreadable file is kept aside and the history starts empty.
 */
class HistoryRepository(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    private fun load(): List<HistoryEntry> = try {
        if (file.exists()) HistoryCodec.decode(file.readText()) else emptyList()
    } catch (e: Exception) {
        file.renameTo(File(file.path + ".corrupt-" + clock()))
        emptyList()
    }

    /** Caller holds [lock]. */
    private fun store(items: List<HistoryEntry>) {
        if (items == _items.value) return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(HistoryCodec.encode(HistoryData(items = items)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _items.value = items
    }

    fun record(entry: HistoryEntry) = synchronized(lock) { store(History.record(_items.value, entry, clock())) }

    fun remove(ref: SourceRef) = synchronized(lock) { store(History.remove(_items.value, ref)) }

    fun clear() = synchronized(lock) { store(History.clear()) }

    companion object {
        @Volatile private var instance: HistoryRepository? = null

        /** One list for the player service (which records) and the browser (which shows it), in the app's one process. */
        fun get(context: android.content.Context): HistoryRepository = instance ?: synchronized(this) {
            instance ?: HistoryRepository(File(context.applicationContext.filesDir, "history/history.json")).also { instance = it }
        }
    }
}
