package com.wing.folderplayer.data.source

import androidx.core.content.edit

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry: configured sources, their credentials and live [SourceFileSystem]s. Everything that reads
 * media (browser, player service, image loader, DLNA relay, export) resolves a [SourceRef] here by sourceId, so
 * there is no "currently selected source" credential state anywhere.
 */
object SourceRegistry {
    const val LOCAL_INTERNAL_ID = "local-internal"
    private const val PREFS = "source_prefs"
    private const val KEY_SOURCES = "sources_list"
    private const val KEY_SCHEMA = "sources_schema"
    private const val KEY_REJECTED = "sources_rejected"
    const val SCHEMA = 2

    private lateinit var appContext: Context
    lateinit var credentials: CredentialStore
        private set
    private val gson = Gson()
    private val fileSystems = ConcurrentHashMap<String, Pair<SourceConfig, SourceFileSystem>>()
    private val _sources = MutableStateFlow<List<SourceConfig>>(emptyList())
    val sources: StateFlow<List<SourceConfig>> = _sources.asStateFlow()

    /** Lets tests and the instrumentation harness inject file systems for fixture sources. */
    @Volatile var fileSystemFactory: ((SourceConfig) -> SourceFileSystem?)? = null

    val isInitialized get() = ::appContext.isInitialized

    @Synchronized
    fun init(context: Context, store: CredentialStore = KeystoreCredentialStore(context)) {
        if (isInitialized) return
        appContext = context.applicationContext
        credentials = store
        LegacyDataMigrator(appContext, store).migrateIfNeeded(builtInSources())
        reload()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun builtInSources(): List<SourceConfig> {
        val list = mutableListOf(
            SourceConfig(
                id = LOCAL_INTERNAL_ID,
                name = "Internal Storage",
                type = SourceType.LOCAL,
                url = Environment.getExternalStorageDirectory().absolutePath,
                builtIn = true,
            )
        )
        val dirs = appContext.getExternalFilesDirs(null)
        for (i in 1 until dirs.size) {
            val f = dirs[i] ?: continue
            val root = f.absolutePath.substringBefore("/Android")
            // Volume directory names (e.g. 1A2B-3C4D) are stable across launches, unlike the old random ids.
            list.add(SourceConfig(id = "local-sd-" + File(root).name, name = "SD Card" + if (i > 1) " $i" else "", type = SourceType.LOCAL, url = root, builtIn = true))
        }
        return list
    }

    fun savedSources(): List<SourceConfig> {
        val json = prefs().getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            gson.fromJson<List<SourceConfig>>(json, object : TypeToken<List<SourceConfig>>() {}.type).orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("SourceRegistry", "stored source list unreadable; keeping it untouched", e)
            emptyList()
        }
    }

    fun rejectedRecords(): List<String> =
        prefs().getStringSet(KEY_REJECTED, emptySet())?.toList().orEmpty()

    @Synchronized
    fun reload() {
        val all = builtInSources() + savedSources()
        _sources.value = all
        // Drop file systems whose config changed or vanished.
        fileSystems.entries.removeIf { (id, pair) ->
            val now = all.firstOrNull { it.id == id }
            val stale = now == null || now.revision != pair.first.revision || now.connectionDiffers(pair.first)
            if (stale) runCatching { pair.second.close() }
            stale
        }
    }

    fun get(id: String): SourceConfig? = _sources.value.firstOrNull { it.id == id }

    fun require(id: String): SourceConfig = get(id) ?: throw SourceException.NotFound("source $id is not configured")

    fun fileSystem(id: String): SourceFileSystem {
        val cfg = require(id)
        fileSystems[id]?.let { (c, fs) -> if (c == cfg) return fs }
        synchronized(this) {
            fileSystems[id]?.let { (c, fs) -> if (c == cfg) return fs else runCatching { fs.close() } }
            val fs = create(cfg)
            fileSystems[id] = cfg to fs
            return fs
        }
    }

    fun fileSystem(ref: SourceRef) = fileSystem(ref.sourceId)

    /** Builds a transient file system (e.g. for "test connection" before saving). */
    fun create(cfg: SourceConfig, passwordOverride: String? = null): SourceFileSystem {
        fileSystemFactory?.invoke(cfg)?.let { return it }
        val store = if (passwordOverride != null) {
            InMemoryCredentialStore().apply { put(cfg.effectiveCredentialRef, passwordOverride) }
        } else credentials
        return when (cfg.type) {
            SourceType.LOCAL -> LocalFileSystem(cfg, appContext)
            SourceType.SAF -> SafFileSystem(cfg, appContext)
            SourceType.WEBDAV -> WebDavFileSystem(cfg, store)
            SourceType.SMB -> SmbFileSystem(cfg, store)
            SourceType.FTP -> FtpFileSystem(cfg, store)
        }
    }

    // ---- editing (persisted list excludes built-ins) ----

    @Synchronized
    private fun persist(list: List<SourceConfig>) {
        prefs().edit { putString(KEY_SOURCES, gson.toJson(list.filter { !it.builtIn })) }
        reload()
    }

    /** Adds or replaces [cfg]. [password]: null keeps the stored secret, "" clears it. */
    @Synchronized
    fun upsert(cfg: SourceConfig, password: String?) {
        val list = savedSources().toMutableList()
        val idx = list.indexOfFirst { it.id == cfg.id }
        val old = list.getOrNull(idx)
        val bumped = if (old != null && (old.connectionDiffers(cfg) || password != null)) cfg.copy(revision = old.revision + 1) else cfg
        when (password) {
            null -> Unit
            "" -> credentials.remove(bumped.effectiveCredentialRef)
            else -> credentials.put(bumped.effectiveCredentialRef, password)
        }
        if (idx >= 0) list[idx] = bumped else list.add(bumped)
        persist(list)
    }

    @Synchronized
    fun remove(id: String) {
        val list = savedSources().toMutableList()
        val removed = list.firstOrNull { it.id == id } ?: return
        list.remove(removed)
        // Only delete the secret if no other source still points at the same reference.
        if (list.none { it.effectiveCredentialRef == removed.effectiveCredentialRef }) credentials.remove(removed.effectiveCredentialRef)
        persist(list)
    }

    /** Copies a source with a new id and its own copy of the credential (no shared secret slot). */
    @Synchronized
    fun duplicate(id: String, nameSuffix: String = " (Copy)"): SourceConfig? {
        val list = savedSources().toMutableList()
        val original = list.firstOrNull { it.id == id } ?: return null
        val newId = UUID.randomUUID().toString()
        val copy = original.copy(id = newId, name = original.name + nameSuffix, credentialRef = "", revision = 0)
        credentials.get(original.effectiveCredentialRef)?.let { credentials.put(copy.effectiveCredentialRef, it) }
        list.add(list.indexOf(original) + 1, copy)
        persist(list)
        return copy
    }

    @Synchronized
    fun move(id: String, delta: Int) {
        val list = savedSources().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        persist(list)
    }

    internal fun writeMigrated(sources: List<SourceConfig>, rejected: List<String>, context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY_SOURCES, gson.toJson(sources)); putStringSet(KEY_REJECTED, rejected.toSet()); putInt(KEY_SCHEMA, SCHEMA) }
    }

    internal fun schemaOf(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SCHEMA, 1)
}
