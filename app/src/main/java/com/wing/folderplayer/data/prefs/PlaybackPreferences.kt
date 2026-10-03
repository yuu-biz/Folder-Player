package com.wing.folderplayer.data.prefs

import androidx.core.content.edit

import android.content.Context
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.utils.LyricLine
import com.google.gson.Gson

data class CachedMetadata(
    val title: String = "",
    val artist: String = "",
    val folderName: String = "",
    val audioInfo: String = "",
    val coverUri: String? = null,
    val lyrics: Array<LyricLine> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CachedMetadata
        if (title != other.title) return false
        if (!lyrics.contentEquals(other.lyrics)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + lyrics.contentHashCode()
        return result
    }
}

/**
 * Playback state restored after restart. The folder and media id are `fpsrc://` refs; no source config (and so no
 * credential) is stored here any more.
 */
class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePlaybackState(folder: SourceRef?, mediaId: String?, position: Long) {
        prefs.edit().apply {
            if (folder != null) putString("folder_ref", folder.toUriString()) else remove("folder_ref")
            putString("last_media_id", mediaId)
            putLong("last_position", position)
            apply()
        }
    }

    fun getLastFolder(): SourceRef? = SourceUris.parse(prefs.getString("folder_ref", null))
    fun getLastMediaId(): String? = prefs.getString("last_media_id", null)
    fun getLastPosition(): Long = prefs.getLong("last_position", 0L)

    fun saveCachedMetadata(metadata: CachedMetadata) {
        prefs.edit { putString("cached_metadata", gson.toJson(metadata)) }
    }

    fun getCachedMetadata(): CachedMetadata? {
        val json = prefs.getString("cached_metadata", null) ?: return null
        return try { gson.fromJson(json, CachedMetadata::class.java) } catch (e: Exception) { null }
    }

    fun getCoverDisplaySize(): String = prefs.getString("cover_display_size", "STANDARD") ?: "STANDARD"
    fun saveCoverDisplaySize(size: String) {
        prefs.edit { putString("cover_display_size", size) }
    }

    fun getAutoNextFolder(): Boolean = prefs.getBoolean("auto_next_folder", false)
    fun saveAutoNextFolder(enabled: Boolean) {
        prefs.edit { putBoolean("auto_next_folder", enabled) }
    }

    fun savePosition(position: Long) {
        prefs.edit { putLong("last_position", position) }
    }

    fun getActivePlaylistId(): String = prefs.getString("active_playlist_id", "default") ?: "default"
    fun saveActivePlaylistId(id: String) {
        prefs.edit { putString("active_playlist_id", id) }
    }

    /** "FILENAME" (public-main behaviour) or "TAGS". */
    fun getTitleMode(): String = prefs.getString("title_mode", "FILENAME") ?: "FILENAME"
    fun saveTitleMode(mode: String) = prefs.edit { putString("title_mode", mode) }

    /** Player background: "GRADIENT" (public-main look, default), "BLUR" (blurred cover) or "BLACK". */
    fun getBackgroundStyle(): String = prefs.getString("background_style", "GRADIENT") ?: "GRADIENT"
    fun saveBackgroundStyle(style: String) = prefs.edit { putString("background_style", style) }

    fun clearAll() {
        prefs.edit { clear() }
    }
}
