package com.wing.folderplayer.data.source

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Converts public-main (schema 1) data to the sourceId-based model. Pure Kotlin so it is unit tested with the exact
 * JSON shapes the baseline app wrote. Every record is migrated on its own: a broken or unknown record is reported
 * and preserved for diagnosis, never a reason to drop the rest.
 */
object SourceMigration {
    private val gson = Gson()

    data class SourcesResult(
        val sources: List<SourceConfig>,
        /** Records that could not be migrated, as JSON with any password removed. */
        val rejected: List<String>,
        val passwordsMoved: Int,
    )

    fun migrateSourcesJson(json: String?, credentials: CredentialStore): SourcesResult {
        if (json.isNullOrBlank()) return SourcesResult(emptyList(), emptyList(), 0)
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return SourcesResult(emptyList(), listOf(json.take(2000).replace(PASSWORD_PATTERN, "\"password\":\"<removed>\"")), 0)
        }
        if (!root.isJsonArray) return SourcesResult(emptyList(), listOf(redact(root)), 0)
        val out = ArrayList<SourceConfig>()
        val rejected = ArrayList<String>()
        var moved = 0
        val seenIds = HashSet<String>()
        for (el in root.asJsonArray) {
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject
            if (obj == null) { rejected.add(redact(el)); continue }
            val password = obj.get("password")?.takeIf { it.isJsonPrimitive }?.asString
            val sanitized = obj.deepCopy().apply { remove("password") }
            val typeName = sanitized.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            val type = SourceType.values().firstOrNull { it.name == typeName }
            if (type == null) { rejected.add(sanitized.toString()); continue }
            val parsed = try {
                gson.fromJson(sanitized, SourceConfig::class.java)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) { rejected.add(sanitized.toString()); continue }
            // Gson writes JSON null into non-null Kotlin fields; repair field by field instead of failing.
            var cfg = repairNulls(parsed, sanitized, type)
            if (cfg.id.isBlank() || !seenIds.add(cfg.id)) cfg = cfg.copy(id = java.util.UUID.randomUUID().toString())
            if (cfg.type == SourceType.WEBDAV && cfg.url.isBlank()) { rejected.add(sanitized.toString()); continue }
            if (!password.isNullOrEmpty()) {
                credentials.put(cfg.effectiveCredentialRef, password)
                moved++
            }
            out.add(cfg)
        }
        return SourcesResult(out, rejected, moved)
    }

    private fun repairNulls(c: SourceConfig, obj: JsonObject, type: SourceType): SourceConfig {
        fun s(name: String, fallback: String): String =
            obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
        @Suppress("SENSELESS_COMPARISON")
        return SourceConfig(
            id = s("id", ""),
            name = s("name", "").ifBlank { if (type == SourceType.WEBDAV) "WebDAV" else type.name },
            type = type,
            url = s("url", ""),
            path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString,
            username = s("username", ""),
            host = s("host", ""),
            port = obj.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            share = s("share", ""),
            domain = s("domain", ""),
            useTls = c.useTls,
            tlsPinnedSha256 = s("tlsPinnedSha256", ""),
            anonymous = c.anonymous,
            syncPath = s("syncPath", ""),
            credentialRef = s("credentialRef", ""),
            revision = c.revision,
        )
    }

    fun redact(el: JsonElement): String =
        if (el.isJsonObject) el.asJsonObject.deepCopy().apply { remove("password") }.toString() else el.toString().take(2000)

    private val PASSWORD_PATTERN = Regex("\"password\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")

    /** Legacy SourceConfig JSON (e.g. playback_prefs.source_config) → id of the matching migrated source. */
    fun legacySourceId(json: String?, sources: List<SourceConfig>): String? {
        if (json.isNullOrBlank() || json == "null") return null
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        if (type == SourceType.LOCAL.name) {
            // Local ids were random per launch; match by root directory instead.
            return sources.filter { it.type == SourceType.LOCAL }.firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') }?.id
                ?: sources.firstOrNull { it.type == SourceType.LOCAL }?.id
        }
        sources.firstOrNull { it.id == id }?.let { return it.id }
        return sources.firstOrNull { it.type == SourceType.WEBDAV && it.url == url }?.id
    }

    /**
     * Converts a public-main path / URI (absolute file path, file:// URI, full WebDAV URL, or an already new fpsrc URI)
     * to a [SourceRef]. [hintSourceId] is a migrated id that should win when several sources match.
     */
    fun legacyToRef(raw: String?, sources: List<SourceConfig>, hintSourceId: String? = null): SourceRef? {
        if (raw.isNullOrBlank()) return null
        SourceUris.parse(raw)?.let { return it }
        val noFragment = raw.substringBefore("#track_")
        when {
            noFragment.startsWith("file://") -> {
                val decoded = SourceUris.decode(noFragment.removePrefix("file://")) ?: return null
                return localRef(decoded, sources, hintSourceId)
            }
            noFragment.startsWith("/") -> return localRef(noFragment, sources, hintSourceId)
            noFragment.startsWith("http://") || noFragment.startsWith("https://") -> return webDavRef(noFragment, sources, hintSourceId)
        }
        return null
    }

    private fun localRef(abs: String, sources: List<SourceConfig>, hint: String?): SourceRef? {
        val candidates = sources.filter { it.type == SourceType.LOCAL }
            .mapNotNull { s -> runCatching { SourcePath.relativize(s.url, abs) }.getOrNull()?.let { s to it } }
            .sortedByDescending { it.first.url.length }
        val pick = candidates.firstOrNull { it.first.id == hint } ?: candidates.firstOrNull() ?: return null
        return SourceRef(pick.first.id, pick.second)
    }

    private fun webDavRef(url: String, sources: List<SourceConfig>, hint: String?): SourceRef? {
        val u = url.toHttpUrlOrNull() ?: return null
        val segs = u.pathSegments.filter { it.isNotEmpty() }
        val matches = sources.filter { it.type == SourceType.WEBDAV }.mapNotNull { s ->
            val base = WebDavFileSystem.normalizeBaseUrl(s.url).toHttpUrlOrNull() ?: return@mapNotNull null
            if (base.host != u.host || base.port != u.port) return@mapNotNull null
            val root = base.pathSegments.filter { it.isNotEmpty() } + SourcePath.segments(s.rootPath)
            if (segs.size < root.size) return@mapNotNull null
            for (i in root.indices) if (!segs[i].equals(root[i], ignoreCase = true)) return@mapNotNull null
            val rest = segs.drop(root.size)
            if (rest.any { it == ".." || it == "." }) return@mapNotNull null
            Triple(s, root.size, if (rest.isEmpty()) SourcePath.ROOT else rest.joinToString("/", prefix = "/"))
        }.sortedByDescending { it.second }
        val pick = matches.firstOrNull { it.first.id == hint } ?: matches.firstOrNull() ?: return null
        return SourceRef(pick.first.id, pick.third)
    }
}
