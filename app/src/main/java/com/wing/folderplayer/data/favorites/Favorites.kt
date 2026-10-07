package com.wing.folderplayer.data.favorites

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.WebDavFileSystem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** [rejected] = entries present in the file that this version cannot read (kept out of [data], never silently lost). */
    data class DecodeReport(val data: FavoritesData, val rejected: Int)

    /** Strict decode: rejects anything that is not a {version, items[]} object; unreadable items are left out but counted. */
    fun decodeReport(json: String): DecodeReport {
        val root = try { JsonParser.parseString(json) } catch (e: Exception) { throw FavoritesFormatException("not JSON: ${e.message}") }
        if (!root.isJsonObject) throw FavoritesFormatException("root is not an object")
        val o = root.asJsonObject
        val version = o.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: throw FavoritesFormatException("missing version")
        if (version > VERSION) throw FavoritesFormatException("unsupported version $version")
        val arr = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw FavoritesFormatException("missing items")
        val items = arr.mapNotNull { el ->
            val item = runCatching { gson.fromJson(el, FavoriteItem::class.java) }.getOrNull() ?: return@mapNotNull null
            // Gson bypasses Kotlin null-safety: a missing field arrives as null.
            val sourceId: String? = item.sourceId
            val path: String? = item.path
            if (sourceId.isNullOrBlank() || path == null || !path.startsWith("/")) null
            else item.copy(type = if (item.type == FavoriteItem.TYPE_FOLDER) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG)
        }
        return DecodeReport(FavoritesData(VERSION, items), arr.size() - items.size)
    }

    fun decode(json: String): FavoritesData = decodeReport(json).data

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

/**
 * Where the shared fav.json is, as seen from this device: the source the file lives on ([sourceId]) and the location of
 * that source's root on the server ([origin]: the root path inside the share / FTP home, for WebDAV the base URL's path
 * plus the root path).
 *
 * A source id is a random UUID per installation, so another device cannot resolve it. The file therefore stores an
 * entry on the file's own source as [SOURCE] plus its path on the server, and each device maps that back to its own
 * source and its own root. Entries of other sources keep their ids (they mean something on this device only).
 */
class SyncAnchor(val sourceId: String, origin: String) {
    val origin: String = SourcePath.normalize(origin)

    /** Entries of the remote file in this device's terms, plus those it cannot show (outside this device's root). */
    data class Incoming(val items: List<FavoriteItem>, val passthrough: List<FavoriteItem>)

    fun toRemote(item: FavoriteItem): FavoriteItem =
        if (item.sourceId == sourceId) item.copy(sourceId = SOURCE, path = SourcePath.join(origin, item.path)) else item

    fun toRemote(items: List<FavoriteItem>): List<FavoriteItem> = items.map(::toRemote)

    /** Entries outside [origin] cannot be addressed through this source: they are kept for the file, not listed here. */
    fun fromRemote(items: List<FavoriteItem>): Incoming {
        val local = ArrayList<FavoriteItem>()
        val rest = ArrayList<FavoriteItem>()
        for (item in items) {
            if (item.sourceId != SOURCE) { local.add(item); continue }
            val rel = SourcePath.relativize(origin, item.path)
            if (rel == null) rest.add(item) else local.add(item.copy(sourceId = sourceId, path = rel))
        }
        return Incoming(local, rest)
    }

    companion object {
        /** `sourceId` of an entry on the source the fav.json lives on. Cannot clash with a generated id (UUID, local-*). */
        const val SOURCE = "@sync"

        fun of(cfg: SourceConfig): SyncAnchor = SyncAnchor(cfg.id, serverOrigin(cfg))

        /** Where the source's root is on the server: the same for every device that reaches the same share / folder. */
        fun serverOrigin(cfg: SourceConfig): String {
            if (cfg.type != SourceType.WEBDAV) return cfg.rootPath
            val base = WebDavFileSystem.normalizeBaseUrl(cfg.url).toHttpUrlOrNull() ?: return cfg.rootPath
            return SourcePath.normalize((base.pathSegments.filter { it.isNotEmpty() } + SourcePath.segments(cfg.rootPath)).joinToString("/", prefix = "/"))
        }
    }
}

sealed class SyncResult {
    data class Synced(val added: Int, val total: Int) : SyncResult()
    data class RemoteCorrupt(val reason: String) : SyncResult()
    data class RemoteNotWritable(val reason: String, val mergedFromRemote: Int) : SyncResult()
    data class Failed(val reason: String) : SyncResult()
    data class RemoteHasUnreadableEntries(val rejected: Int, val mergedFromRemote: Int) : SyncResult()
}

/**
 * Local favorites (files/favorites/fav.json) plus explicit sync with a user-chosen remote fav.json.
 *
 * Two locks: [localLock] guards the list and the local file and is only ever held briefly (add / remove from the UI
 * thread, and the step of a sync that merges into the list); [syncLock] lets one sync or remote replace run at a time
 * and is held across the network I/O, which add / remove never wait for.
 */
class FavoritesRepository(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val localLock = Any()
    private val syncLock = Any()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()

    /** Set when the local file holds entries this version cannot read: backed up once before it is rewritten. */
    private var backupBeforeWrite = false

    private fun load(): List<FavoriteItem> = try {
        if (file.exists()) FavoritesCodec.decodeReport(file.readText()).also { backupBeforeWrite = it.rejected > 0 }.data.items
        else emptyList()
    } catch (e: Exception) {
        // Keep the unreadable file for diagnosis instead of overwriting it.
        file.renameTo(File(file.path + ".corrupt-" + clock()))
        emptyList()
    }

    /** Caller holds [localLock]. */
    private fun store(items: List<FavoriteItem>) {
        file.parentFile?.mkdirs()
        if (backupBeforeWrite) {
            file.copyTo(File(file.path + ".unreadable-" + clock()), overwrite = false)
            backupBeforeWrite = false
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(FavoritesCodec.encode(FavoritesData(items = items)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        _items.value = items
    }

    fun isFavorite(ref: SourceRef, folder: Boolean): Boolean {
        val type = if (folder) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG
        return _items.value.any { it.sourceId == ref.sourceId && it.path == ref.path && it.type == type }
    }

    fun add(entry: MusicFile) {
        synchronized(localLock) {
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
    }

    fun remove(ref: SourceRef, folder: Boolean) {
        val type = if (folder) FavoriteItem.TYPE_FOLDER else FavoriteItem.TYPE_SONG
        synchronized(localLock) {
            store(_items.value.filterNot { it.sourceId == ref.sourceId && it.path == ref.path && it.type == type })
        }
    }

    fun toggle(entry: MusicFile) {
        synchronized(localLock) {
            if (isFavorite(entry.ref, entry.isDirectory)) remove(entry.ref, entry.isDirectory) else add(entry)
        }
    }

    /**
     * Merge with the remote file at [remotePath] on [fs]. The remote is written only when the merge changed it, and
     * never when it is broken or holds entries this version cannot read (those need the explicit [replaceRemote]).
     *
     * The remote is read and written without holding [localLock]; the merge is applied to the list as it is at that
     * moment, so favourites added or removed while the remote was being read are kept as the user left them (a removed
     * entry that the remote still has comes back, as with any sync: deletions are not propagated). Entries added while
     * the merged list is being written stay local and reach the remote with the next sync; entries this sync put on the
     * remote and the user removed while it was writing are taken off again ([writeRemoteFollowingRemovals]).
     *
     * With an [anchor] the file is shared between devices: entries on the file's own source are read and written in
     * server terms (see [SyncAnchor]); those outside this device's root are neither listed nor dropped.
     */
    fun sync(fs: SourceFileSystem, remotePath: String, anchor: SyncAnchor? = null): SyncResult = synchronized(syncLock) {
        val remote: FavoritesCodec.DecodeReport? = try {
            val st = fs.stat(remotePath)
            if (st == null) null else {
                val bytes = fs.openRead(remotePath).use { it.readBytes() }
                FavoritesCodec.decodeReport(String(bytes, Charsets.UTF_8))
            }
        } catch (e: FavoritesFormatException) {
            return SyncResult.RemoteCorrupt(e.message.orEmpty())
        } catch (e: SourceException) {
            return SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val remoteRaw = remote?.data?.items.orEmpty()
        // Everything below runs in this device's terms; only the read above and the write convert (see [SyncAnchor]).
        val incoming = anchor?.fromRemote(remoteRaw) ?: SyncAnchor.Incoming(remoteRaw, emptyList())
        val remoteItems = incoming.items
        val (merged, added) = synchronized(localLock) {
            val before = _items.value
            val merged = FavoritesCodec.merge(before, remoteItems)
            if (merged != before) store(merged)
            merged to merged.size - before.size
        }
        if (remote != null && remote.rejected > 0) return SyncResult.RemoteHasUnreadableEntries(remote.rejected, added)
        // Semantically unchanged: leave the remote file (and its formatting) alone.
        val outgoing = (anchor?.toRemote(merged) ?: merged) + incoming.passthrough
        if (remote != null && outgoing.size == remoteRaw.size && outgoing.toSet() == remoteRaw.toSet()) return SyncResult.Synced(added, merged.size)
        val remoteKeys = remoteItems.mapTo(HashSet()) { it.key }
        return writeRemoteFollowingRemovals(fs, remotePath, merged, added, anchor, incoming.passthrough) { it.key !in remoteKeys }
    }

    /**
     * Explicit user action: overwrite the remote file with the local list (also used to repair a broken remote). The
     * write runs without [localLock]; if the list changed meanwhile (added or removed favourites), the remote is
     * written again with the list as it is then, so it equals the local list when this returns. Entries the remote has
     * outside this device's root (which a sync carries along) are part of what is replaced: they go with the old file.
     */
    fun replaceRemote(fs: SourceFileSystem, remotePath: String, anchor: SyncAnchor? = null): SyncResult = synchronized(syncLock) {
        var written = _items.value
        var result = writeRemote(fs, remotePath, anchor.toRemote(written), 0, written.size)
        while (result is SyncResult.Synced) {
            val now = synchronized(localLock) { _items.value }
            if (now == written) break
            written = now
            result = writeRemote(fs, remotePath, anchor.toRemote(written), 0, written.size)
        }
        result
    }

    private fun SyncAnchor?.toRemote(items: List<FavoriteItem>): List<FavoriteItem> = this?.toRemote(items) ?: items

    /**
     * Writes [items] to the remote for a sync. The list was taken before the (unlocked) write, so the user may have
     * removed some of its entries meanwhile; those that [retractable] allows (entries this write itself put on the
     * remote — never ones the remote already had, whose deletion a sync does not propagate) are taken out and the
     * remote is written again, until nothing was removed during the last write. Entries added meanwhile go out with
     * the next sync.
     */
    private fun writeRemoteFollowingRemovals(
        fs: SourceFileSystem,
        remotePath: String,
        items: List<FavoriteItem>,
        added: Int,
        anchor: SyncAnchor?,
        passthrough: List<FavoriteItem>,
        retractable: (FavoriteItem) -> Boolean,
    ): SyncResult {
        var written = items
        while (true) {
            val result = writeRemote(fs, remotePath, anchor.toRemote(written) + passthrough, added, written.size)
            if (result !is SyncResult.Synced) return result
            val removed = synchronized(localLock) {
                val now = _items.value.mapTo(HashSet()) { it.key }
                written.filter { it.key !in now && retractable(it) }
            }
            if (removed.isEmpty()) return result
            written = written - removed.toSet()
        }
    }

    /** [items] are written as they are (already in the file's terms); [total] is the local list size reported back. */
    private fun writeRemote(fs: SourceFileSystem, remotePath: String, items: List<FavoriteItem>, added: Int, total: Int): SyncResult {
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
            SyncResult.Synced(added, total)
        } catch (e: SourceException.ReadOnly) {
            SyncResult.RemoteNotWritable(e.message ?: "read-only", added)
        } catch (e: SourceException.PermissionDenied) {
            SyncResult.RemoteNotWritable(e.message ?: "permission denied", added)
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
