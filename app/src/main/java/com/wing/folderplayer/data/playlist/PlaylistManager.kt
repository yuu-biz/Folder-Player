package com.wing.folderplayer.data.playlist

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import java.io.File

/**
 * A playlist entry. [sourceId] + [path] (source-relative) identify the file. For CUE virtual tracks [path] is the
 * .cue file and [cueStartMs] the track start. An entry whose source could not be resolved during migration keeps
 * its old raw value in [path] with an empty [sourceId] and is shown as unavailable instead of being dropped.
 */
data class PlaylistItem(
    val path: String = "",
    val title: String = "",
    val artist: String? = null,
    val sourceId: String = "",
    val artworkUri: String? = null,
    val durationMs: Long = 0,
    val cueStartMs: Long? = null,
) {
    val ref: SourceRef? get() = if (sourceId.isNotEmpty() && path.startsWith("/")) SourceRef(sourceId, path) else null
}

data class Playlist(
    val id: String, // Filename e.g. "default", "list_1"
    val name: String,
    val items: List<PlaylistItem> = emptyList()
)

private data class PlaylistFile(val version: Int = PlaylistStore.VERSION, val items: List<PlaylistItem> = emptyList())

class PlaylistStore(private val context: Context) {
    private val gson = Gson()
    private val playlistsDir = File(context.filesDir, "playlists").apply { if (!exists()) mkdirs() }
    private val metadataFile = File(playlistsDir, "metadata.json")

    // Metadata keeps track of display names for IDs
    private var playlistMetadata: MutableMap<String, String> = mutableMapOf("default" to "Default")

    init {
        loadMetadata()
    }

    private fun loadMetadata() {
        if (metadataFile.exists()) {
            try {
                val json = metadataFile.readText()
                val type = object : TypeToken<MutableMap<String, String>>() {}.type
                playlistMetadata = gson.fromJson(json, type) ?: mutableMapOf("default" to "Default")
                // Ensure default always exists
                if (!playlistMetadata.containsKey("default")) {
                    playlistMetadata["default"] = "Default"
                }
            } catch (e: Exception) {
                playlistMetadata = mutableMapOf("default" to "Default")
            }
        }
    }

    private fun saveMetadata() {
        metadataFile.writeText(gson.toJson(playlistMetadata))
    }

    fun getAllPlaylists(): List<Playlist> {
        return playlistMetadata.map { (id, name) ->
            getPlaylist(id) ?: Playlist(id, name)
        }
    }

    private fun readItems(file: File): List<PlaylistItem>? {
        val json = file.readText()
        val root = JsonParser.parseString(json)
        return if (root.isJsonArray) {
            // Schema 1 file not migrated yet (should not happen after LegacyDataMigrator); read as-is.
            gson.fromJson<List<PlaylistItem>>(root, object : TypeToken<List<PlaylistItem>>() {}.type)
        } else {
            gson.fromJson(root, PlaylistFile::class.java)?.items
        }
    }

    fun getPlaylist(id: String): Playlist? {
        val file = File(playlistsDir, "$id.fpl")
        if (!file.exists()) return null
        return try {
            Playlist(id, playlistMetadata[id] ?: id, readItems(file).orEmpty())
        } catch (e: Exception) {
            android.util.Log.e("PlaylistStore", "playlist $id unreadable; leaving file untouched", e)
            null
        }
    }

    fun savePlaylist(playlist: Playlist) {
        val file = File(playlistsDir, "${playlist.id}.fpl")
        val tmp = File(playlistsDir, "${playlist.id}.fpl.tmp")
        tmp.writeText(gson.toJson(PlaylistFile(VERSION, playlist.items)))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        playlistMetadata[playlist.id] = playlist.name
        saveMetadata()
    }

    fun createPlaylist(name: String): String? {
        if (playlistMetadata.size >= 11) return null
        val id = "list_${System.currentTimeMillis()}"
        playlistMetadata[id] = name
        savePlaylist(Playlist(id, name, emptyList()))
        return id
    }

    fun renamePlaylist(id: String, newName: String) {
        if (id == "default") return // Cannot rename default
        playlistMetadata[id] = newName
        saveMetadata()
    }

    fun deletePlaylist(id: String) {
        if (id == "default") return
        val file = File(playlistsDir, "$id.fpl")
        if (file.exists()) file.delete()
        playlistMetadata.remove(id)
        saveMetadata()
    }

    fun appendToPlaylist(id: String, newItem: PlaylistItem) {
        val p = getPlaylist(id) ?: Playlist(id, playlistMetadata[id] ?: id)
        val newItems = p.items.toMutableList().apply { add(newItem) }
        savePlaylist(p.copy(items = newItems))
    }

    fun appendToPlaylist(id: String, newItems: List<PlaylistItem>) {
        val p = getPlaylist(id) ?: Playlist(id, playlistMetadata[id] ?: id)
        val updatedItems = p.items.toMutableList().apply { addAll(newItems) }
        savePlaylist(p.copy(items = updatedItems))
    }

    fun removeFromPlaylist(id: String, index: Int) {
        val p = getPlaylist(id) ?: return
        if (index in p.items.indices) {
            val newItems = p.items.toMutableList().apply { removeAt(index) }
            savePlaylist(p.copy(items = newItems))
        }
    }

    /**
     * Rewrites schema-1 playlist files (a bare JSON array with absolute paths / URLs) to [VERSION] with
     * sourceId-based paths. Each file and each item is converted independently.
     */
    fun migrateLegacyFiles(resolve: (PlaylistItem) -> SourceRef?) {
        playlistsDir.listFiles { f -> f.name.endsWith(".fpl") }?.forEach { file ->
            try {
                val root = JsonParser.parseString(file.readText())
                if (!root.isJsonArray) return@forEach
                val migrated = root.asJsonArray.mapNotNull { el ->
                    val item = runCatching { gson.fromJson(el, PlaylistItem::class.java) }.getOrNull() ?: return@mapNotNull null
                    val ref = resolve(item)
                    val art = item.artworkUri?.let { a -> resolve(item.copy(path = a))?.toUriString() }
                    if (ref != null) item.copy(sourceId = ref.sourceId, path = ref.path, artworkUri = art)
                    else item.copy(sourceId = "", artworkUri = art)
                }
                file.writeText(gson.toJson(PlaylistFile(VERSION, migrated)))
            } catch (e: Exception) {
                android.util.Log.e("PlaylistStore", "could not migrate ${file.name}; left unchanged", e)
            }
        }
    }

    companion object {
        const val VERSION = 2
        fun artworkRef(item: PlaylistItem): SourceRef? = SourceUris.parse(item.artworkUri)
    }
}

typealias PlaylistManager = PlaylistStore
