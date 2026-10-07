package com.wing.folderplayer.data.favorites

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * A folder the user opens often (a source root, or a place deep in it). Unlike a favourite it never plays anything:
 * it is a shortcut into the browser. Kept on this device only (files/favorites/bookmarks.json, never synced): which
 * places are handy depends on the device, and a source id means nothing on another one. No credentials are stored.
 */
data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String = "",
    val path: String = SourcePath.ROOT,
    val name: String = "",
    val timestamp: Long = 0,
) {
    val ref: SourceRef get() = SourceRef(sourceId, path)
}

data class BookmarksData(val version: Int = BookmarksCodec.VERSION, val items: List<Bookmark> = emptyList())

object BookmarksCodec {
    const val VERSION = 1
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(data: BookmarksData): String = gson.toJson(data)

    /** [rejected] = entries in the file that this version cannot read (left out of [data], counted, never silently lost). */
    data class DecodeReport(val data: BookmarksData, val rejected: Int)

    /** Strict decode: anything that is not a {version, items[]} object throws; unreadable items are left out but counted. */
    fun decodeReport(json: String): DecodeReport {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "root is not an object" }
        val o = root.asJsonObject
        val version = o.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: throw IllegalArgumentException("missing version")
        require(version <= VERSION) { "unsupported version $version" }
        val arr = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw IllegalArgumentException("missing items")
        val seen = HashSet<Pair<String, String>>()
        val items = arr.mapNotNull { el ->
            val b = runCatching { gson.fromJson(el, Bookmark::class.java) }.getOrNull() ?: return@mapNotNull null
            // Gson bypasses Kotlin null-safety: a missing field arrives as null.
            val sourceId: String? = b.sourceId
            val path: String? = b.path
            if (sourceId.isNullOrBlank() || path == null || !path.startsWith("/")) return@mapNotNull null
            val normalized = runCatching { SourcePath.normalize(path) }.getOrNull() ?: return@mapNotNull null
            if (!seen.add(sourceId to normalized)) return@mapNotNull null
            val name: String? = b.name
            b.copy(path = normalized, name = name.orEmpty())
        }
        return DecodeReport(BookmarksData(VERSION, items), arr.size() - items.size)
    }
}

/** The bookmarks of this device, in the order they were added. Writes go through a temporary file. */
class BookmarksRepository(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    /** Set when the file holds entries this version cannot read: backed up once before it is rewritten. */
    private var backupBeforeWrite = false

    private fun load(): List<Bookmark> = try {
        if (file.exists()) BookmarksCodec.decodeReport(file.readText()).also { backupBeforeWrite = it.rejected > 0 }.data.items
        else emptyList()
    } catch (e: Exception) {
        // Keep the unreadable file for diagnosis instead of overwriting it.
        file.renameTo(File(file.path + ".corrupt-" + clock()))
        emptyList()
    }

    /** Caller holds [lock]. */
    private fun store(items: List<Bookmark>) {
        file.parentFile?.mkdirs()
        if (backupBeforeWrite) {
            file.copyTo(File(file.path + ".unreadable-" + clock()), overwrite = false)
            backupBeforeWrite = false
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(BookmarksCodec.encode(BookmarksData(items = items)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _items.value = items
    }

    private fun key(ref: SourceRef): Pair<String, String>? =
        runCatching { ref.sourceId to SourcePath.normalize(ref.path) }.getOrNull()

    fun isBookmarked(ref: SourceRef): Boolean {
        val k = key(ref) ?: return false
        return _items.value.any { it.sourceId == k.first && it.path == k.second }
    }

    /** [name]: what the list shows (the source's name for its root, otherwise the folder's name). */
    fun add(ref: SourceRef, name: String) {
        val k = key(ref) ?: return
        synchronized(lock) {
            if (isBookmarked(ref)) return
            store(_items.value + Bookmark(sourceId = k.first, path = k.second, name = name, timestamp = clock()))
        }
    }

    fun remove(ref: SourceRef) {
        val k = key(ref) ?: return
        synchronized(lock) {
            val kept = _items.value.filterNot { it.sourceId == k.first && it.path == k.second }
            if (kept.size != _items.value.size) store(kept)
        }
    }

    fun toggle(ref: SourceRef, name: String) {
        synchronized(lock) { if (isBookmarked(ref)) remove(ref) else add(ref, name) }
    }
}
