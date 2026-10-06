package com.wing.folderplayer.data.prefs

import androidx.core.content.edit

import android.content.Context
import com.wing.folderplayer.data.source.SourceRef

data class SortConfig(
    val field: String,
    val ascending: Boolean
)

/** Browser state. Folders are identified by [SourceRef] (sourceId + source-relative path). */
class SourcePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("source_prefs", Context.MODE_PRIVATE)

    fun saveLastBrowsedState(folder: SourceRef?) {
        prefs.edit().apply {
            if (folder == null) {
                remove("last_source_id")
                putString("last_path", "ROOT")
            } else {
                putString("last_source_id", folder.sourceId)
                putString("last_path", folder.path)
            }
            apply()
        }
    }

    fun getLastBrowsed(): SourceRef? {
        val id = prefs.getString("last_source_id", null) ?: return null
        val path = prefs.getString("last_path", "ROOT") ?: return null
        if (path == "ROOT" || !path.startsWith("/")) return null
        return SourceRef(id, path)
    }

    /** Browser view mode per folder ("GRID"/"LIST"), falling back to the default. */
    fun getViewMode(folder: SourceRef?): String =
        folder?.let { prefs.getString("view_mode_" + it.toUriString(), null) } ?: getDefaultViewMode()

    fun saveViewMode(folder: SourceRef, mode: String) {
        prefs.edit { putString("view_mode_" + folder.toUriString(), mode) }
    }

    fun getDefaultViewMode(): String = prefs.getString("default_view_mode", "LIST") ?: "LIST"
    fun saveDefaultViewMode(mode: String) = prefs.edit { putString("default_view_mode", mode) }

    /** Grid columns on a phone in portrait (2..5). */
    fun getGridDensity(): Int = prefs.getInt("grid_density", 3)
    fun saveGridDensity(columns: Int) = prefs.edit { putInt("grid_density", columns.coerceIn(2, 5)) }

    fun getDefaultSort(): SortOption {
        val field = prefs.getString("default_sort_field", "NAME") ?: "NAME"
        val asc = prefs.getBoolean("default_sort_asc", true)
        return SortOption(field, asc)
    }

    fun saveDefaultSort(field: String, ascending: Boolean) {
        prefs.edit { putString("default_sort_field", field); putBoolean("default_sort_asc", ascending) }
    }

    fun getDirectorySort(folder: SourceRef): SortOption? {
        val key = folder.toUriString()
        val field = prefs.getString("sort_field_$key", null) ?: return null
        val asc = prefs.getBoolean("sort_asc_$key", true)
        return SortOption(field, asc)
    }

    fun saveDirectorySort(folder: SourceRef, field: String, ascending: Boolean) {
        val key = folder.toUriString()
        prefs.edit { putString("sort_field_$key", field); putBoolean("sort_asc_$key", ascending) }
    }

    data class SortOption(val field: String, val ascending: Boolean)

    /**
     * Safe-mode reset of debug builds (Music/Init folder present, see DevSafeMode), as in public main: clears everything
     * in this file, including the source list kept here by SourceRegistry (their stored passwords are left behind).
     */
    fun clearAll() {
        prefs.edit { clear(); putInt("sources_schema", 2) }
    }
}
