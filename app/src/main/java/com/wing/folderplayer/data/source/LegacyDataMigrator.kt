package com.wing.folderplayer.data.source

import android.content.Context
import com.wing.folderplayer.data.playlist.PlaylistStore
import java.io.File

/**
 * One-time upgrade of public-main (schema 1) data written by com.wing.folderplayer 0.4 builds:
 * source list (passwords move to [CredentialStore]), last browsed state, per-folder sort keys, playback state and
 * playlists. A copy of the old files is kept in files/migration-backup/ with secrets redacted.
 */
// commit() on purpose: the migrated data must be on disk before the app reads it in the same start-up (apply() raced).
@android.annotation.SuppressLint("ApplySharedPref")
class LegacyDataMigrator(private val context: Context, private val credentials: CredentialStore) {

    fun migrateIfNeeded(builtIns: List<SourceConfig>) {
        if (SourceRegistry.schemaOf(context) >= SourceRegistry.SCHEMA) return
        try {
            backup()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "backup before migration failed; migrating anyway", e)
        }
        val sourcePrefs = context.getSharedPreferences("source_prefs", Context.MODE_PRIVATE)
        val result = SourceMigration.migrateSourcesJson(sourcePrefs.getString("sources_list", null), credentials)
        val all = builtIns + result.sources
        if (result.rejected.isNotEmpty()) android.util.Log.w(TAG, "${result.rejected.size} source record(s) could not be migrated")

        // Last browsed location.
        val lastSourceId = SourceMigration.legacySourceId(sourcePrefs.getString("last_source", null), all)
        val lastPath = sourcePrefs.getString("last_path", "ROOT") ?: "ROOT"
        val lastRef = if (lastPath == "ROOT") null else SourceMigration.legacyToRef(lastPath, all, lastSourceId)
        val edit = sourcePrefs.edit()
        edit.remove("last_source")
        if (lastRef != null) {
            edit.putString("last_source_id", lastRef.sourceId).putString("last_path", lastRef.path)
        } else {
            edit.remove("last_source_id").putString("last_path", "ROOT")
        }
        // Per-folder sort overrides were keyed by absolute path / URL.
        for ((key, value) in sourcePrefs.all) {
            val isField = key.startsWith("sort_field_")
            val isAsc = key.startsWith("sort_asc_")
            if (!isField && !isAsc) continue
            val legacy = key.removePrefix(if (isField) "sort_field_" else "sort_asc_")
            if (SourceUris.isSourceUri(legacy)) continue
            edit.remove(key)
            val ref = SourceMigration.legacyToRef(legacy, all) ?: continue
            val newKey = (if (isField) "sort_field_" else "sort_asc_") + ref.toUriString()
            when (value) {
                is String -> edit.putString(newKey, value)
                is Boolean -> edit.putBoolean(newKey, value)
            }
        }
        edit.commit()

        // Playback state.
        val pb = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        val pbSourceId = SourceMigration.legacySourceId(pb.getString("source_config", null), all)
        val folderRef = SourceMigration.legacyToRef(pb.getString("folder_path", null), all, pbSourceId)
        val mediaRaw = pb.getString("last_media_id", null)
        val mediaRef = SourceMigration.legacyToRef(mediaRaw, all, pbSourceId ?: folderRef?.sourceId)
        val cueStart = mediaRaw?.let { if (SourceUris.isCueTrackId(it)) SourceUris.cueStartMs(it) else null }
        pb.edit().apply {
            remove("source_config") // contained a plaintext password copy
            if (folderRef != null) putString("folder_ref", folderRef.toUriString()) else remove("folder_ref")
            remove("folder_path")
            val mediaId = mediaRef?.let { if (cueStart != null) SourceUris.cueTrackId(it, cueStart) else it.toUriString() }
            if (mediaId != null) putString("last_media_id", mediaId) else remove("last_media_id")
            remove("cached_metadata") // cover URIs in it were file:// or http and may point to the wrong source
        }.commit()

        // Playlists.
        PlaylistStore(context).migrateLegacyFiles { item ->
            val hint = when {
                item.sourceId == "local" -> null
                else -> all.firstOrNull { it.type == SourceType.WEBDAV && it.url == item.sourceId }?.id
            }
            SourceMigration.legacyToRef(item.path, all, hint)
        }

        SourceRegistry.writeMigrated(result.sources, result.rejected, context)
    }

    private fun backup() {
        val dir = File(context.filesDir, "migration-backup/schema1-${System.currentTimeMillis()}").apply { mkdirs() }
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        for (name in listOf("source_prefs.xml", "playback_prefs.xml", "lyric_prefs.xml")) {
            val f = File(prefsDir, name)
            if (f.exists()) File(dir, name).writeText(redact(f.readText()))
        }
        val playlists = File(context.filesDir, "playlists")
        if (playlists.isDirectory) playlists.copyRecursively(File(dir, "playlists"), overwrite = true)
    }

    companion object {
        private const val TAG = "LegacyDataMigrator"
        // SharedPreferences XML stores the JSON with &quot; escapes.
        private val XML_PASSWORD = Regex("(&quot;password&quot;:&quot;)(?:(?!&quot;).)*(&quot;)")

        fun redact(xml: String): String = xml.replace(XML_PASSWORD, "$1<removed>$2")
    }
}
