package com.wing.folderplayer.data.favorites

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** fav.json entry (id/type/sourceId/path/name/size/timestamp/transferStatus). No credentials are stored. */
data class FavoriteItem(
    val id: String = UUID.randomUUID().toString(),
    val type: String = TYPE_SONG,
    val sourceId: String = "",
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val timestamp: Long = 0,
    val transferStatus: String = "NONE",
) {
    val ref: SourceRef? get() = if (sourceId.isNotEmpty() && path.startsWith("/")) SourceRef(sourceId, path) else null
    val key: Triple<String, String, String> get() = Triple(sourceId, path, type)

    companion object {
        const val TYPE_SONG = "SONG"
        const val TYPE_FOLDER = "FOLDER"
    }
}

data class FavoritesData(val version: Int = FavoritesCodec.VERSION, val items: List<FavoriteItem> = emptyList())

class FavoritesFormatException(msg: String) : Exception(msg)

object FavoritesCodec {
    const val VERSION = 1
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(data: FavoritesData): String = gson.toJson(data)

    /** Strict decode: rejects anything that is not a {version, items[]} object with valid items. */
    fun decode(json: String): FavoritesData {
        val root = try { JsonParser.parseString(json) } catch (e: Exception) { throw FavoritesFormatException("not JSON: ${e.message}") }
        if (!root.isJsonObject) throw FavoritesFormatException("root is not an object")
        val o = root.asJsonObject
        val version = o.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: throw FavoritesFormatException("missing version")
        if (version > VERSION) throw FavoritesFormatException("unsupported version $version")
        val arr = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw FavoritesFormatException("missing items")
        val items = arr.mapNotNull { el ->
            val item = runCatching { gson.fromJson(el, FavoriteItem::class.java) }.getOrNull() ?: return@mapNotNull null
            if (item.sourceId.isBlank() || !item.path.startsWith("/")) null
            else item.copy(type = if (item.type == FavoriteItem.TYPE_FOLDER) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG)
        }
        return FavoritesData(VERSION, items)
    }

    /**
     * Additive merge: union by (sourceId, path, type); for an entry present on both sides the newer
     * timestamp wins. Deletions are not propagated — removing remote entries requires an explicit replace.
     */
    fun merge(local: List<FavoriteItem>, remote: List<FavoriteItem>): List<FavoriteItem> {
        val byKey = LinkedHashMap<Triple<String, String, String>, FavoriteItem>()
        for (i in local) byKey[i.key] = i
        for (r in remote) {
            val l = byKey[r.key]
            if (l == null || r.timestamp > l.timestamp) byKey[r.key] = r
        }
        return byKey.values.toList()
    }
}

sealed class SyncResult {
    data class Synced(val added: Int, val total: Int) : SyncResult()
    data class RemoteCorrupt(val reason: String) : SyncResult()
    data class RemoteNotWritable(val reason: String, val mergedFromRemote: Int) : SyncResult()
    data class Failed(val reason: String) : SyncResult()
}

/** Local favorites (files/favorites/fav.json) plus explicit sync with a user-chosen remote fav.json. */
class FavoritesRepository(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()

    private fun load(): List<FavoriteItem> = try {
        if (file.exists()) FavoritesCodec.decode(file.readText()).items else emptyList()
    } catch (e: Exception) {
        // Keep the unreadable file for diagnosis instead of overwriting it.
        file.renameTo(File(file.path + ".corrupt-" + clock()))
        emptyList()
    }

    @Synchronized
    private fun store(items: List<FavoriteItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(FavoritesCodec.encode(FavoritesData(items = items)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _items.value = items
    }

    fun isFavorite(ref: SourceRef, folder: Boolean): Boolean {
        val type = if (folder) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG
        return _items.value.any { it.sourceId == ref.sourceId && it.path == ref.path && it.type == type }
    }

    @Synchronized
    fun add(entry: MusicFile) {
        if (isFavorite(entry.ref, entry.isDirectory)) return
        val item = FavoriteItem(
            type = if (entry.isDirectory) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG,
            sourceId = entry.sourceId,
            path = SourcePath.normalize(entry.path),
            name = entry.name,
            size = entry.size,
            timestamp = clock(),
        )
        store(_items.value + item)
    }

    @Synchronized
    fun remove(ref: SourceRef, folder: Boolean) {
        val type = if (folder) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG
        store(_items.value.filterNot { it.sourceId == ref.sourceId && it.path == ref.path && it.type == type })
    }

    fun toggle(entry: MusicFile) = if (isFavorite(entry.ref, entry.isDirectory)) remove(entry.ref, entry.isDirectory) else add(entry)

    /** Merge with the remote file at [remotePath] on [fs]. Never writes when the remote JSON is broken. */
    @Synchronized
    fun sync(fs: SourceFileSystem, remotePath: String): SyncResult {
        val remoteItems: List<FavoriteItem> = try {
            val st = fs.stat(remotePath)
            if (st == null) emptyList() else {
                val bytes = fs.openRead(remotePath).use { it.readBytes() }
                FavoritesCodec.decode(String(bytes, Charsets.UTF_8)).items
            }
        } catch (e: FavoritesFormatException) {
            return SyncResult.RemoteCorrupt(e.message.orEmpty())
        } catch (e: SourceException) {
            return SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val before = _items.value
        val merged = FavoritesCodec.merge(before, remoteItems)
        val added = merged.size - before.size
        store(merged)
        return writeRemote(fs, remotePath, merged, added)
    }

    /** Explicit user action: overwrite the remote file with the local list (also used to repair a broken remote). */
    @Synchronized
    fun replaceRemote(fs: SourceFileSystem, remotePath: String): SyncResult = writeRemote(fs, remotePath, _items.value, 0)

    private fun writeRemote(fs: SourceFileSystem, remotePath: String, items: List<FavoriteItem>, added: Int): SyncResult {
        val json = FavoritesCodec.encode(FavoritesData(items = items))
        // Validate what we are about to write; a broken file must never reach the sync path.
        FavoritesCodec.decode(json)
        val bytes = json.toByteArray(Charsets.UTF_8)
        return try {
            val tmp = remotePath + ".fp-tmp"
            val viaTemp = try {
                fs.write(tmp, bytes, overwrite = true)
                fs.rename(tmp, remotePath).also { ok -> if (!ok) runCatching { fs.delete(tmp) } }
            } catch (e: SourceException.ReadOnly) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!viaTemp) fs.write(remotePath, bytes, overwrite = true)
            SyncResult.Synced(added, items.size)
        } catch (e: SourceException.ReadOnly) {
            SyncResult.RemoteNotWritable(e.message ?: "read-only", added)
        } catch (e: SourceException.PermissionDenied) {
            SyncResult.RemoteNotWritable(e.message ?: "permission denied", added)
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
