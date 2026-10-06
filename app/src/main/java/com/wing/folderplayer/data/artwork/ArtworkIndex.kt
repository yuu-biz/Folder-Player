package com.wing.folderplayer.data.artwork

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Folder → image index kept in `artwork-index.json`. The file is read and parsed on [scope] (an I/O dispatcher) as soon
 * as the index is created, never on the caller's thread; [get] waits for that. Changes made before the file has been
 * read are remembered and applied to what it contains, so an early [remove] / [clear] is not undone by the late load
 * and a save can never write less than the file held. Saves are coalesced: at most one write per [saveDelayMs] of
 * changes, on [scope].
 */
internal class ArtworkIndex(
    private val file: File,
    private val scope: CoroutineScope,
    private val saveDelayMs: Long = 500,
    private val read: (File) -> String? = { f -> if (f.exists()) f.readText() else null },
) {
    data class Entry(val image: String = "", val name: String = "", val size: Long = 0, val mtime: Long = 0)

    private val gson = Gson()
    private val map = ConcurrentHashMap<String, Entry>()
    private val ready = CompletableDeferred<Unit>()
    private val lock = Any()
    private var loaded = false
    /** Removals made before the load finished; they also apply to the keys the file brings. */
    private val removals = ArrayList<(String) -> Boolean>()
    private val saveLock = Any()
    private var pendingSave: Job? = null

    init {
        scope.launch { load() }
    }

    private fun load() {
        val parsed: Map<String, Entry?> = try {
            read(file)?.let { gson.fromJson<Map<String, Entry?>>(it, object : TypeToken<Map<String, Entry?>>() {}.type) }.orEmpty()
        } catch (e: Exception) {
            emptyMap() // unreadable file: start empty, the next save replaces it
        }
        synchronized(lock) {
            for ((k, v) in parsed) if (v != null && removals.none { it(k) }) map.putIfAbsent(k, v)
            removals.clear()
            loaded = true
        }
        ready.complete(Unit)
    }

    /** The entry for [key]; waits until the file has been read. */
    suspend fun get(key: String): Entry? {
        ready.await()
        return map[key]
    }

    fun put(key: String, entry: Entry) {
        map[key] = entry
        scheduleSave()
    }

    fun remove(key: String) = removeWhere { it == key }

    fun removePrefix(prefix: String) = removeWhere { it.startsWith(prefix) }

    private fun removeWhere(matches: (String) -> Boolean) {
        synchronized(lock) {
            map.keys.removeIf(matches)
            if (!loaded) removals += matches
        }
        scheduleSave()
    }

    /** Forgets everything, including what the file still holds, and deletes the file. */
    @Synchronized // with write(): a save in progress cannot bring the deleted file back with old entries
    fun clear() {
        synchronized(saveLock) { pendingSave?.cancel() }
        synchronized(lock) {
            map.clear()
            if (!loaded) removals += { true }
        }
        file.delete()
    }

    private fun scheduleSave() {
        synchronized(saveLock) {
            pendingSave?.cancel()
            pendingSave = scope.launch {
                delay(saveDelayMs)
                ready.await() // never write before the file's own entries were merged in
                write()
            }
        }
    }

    @Synchronized
    private fun write() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(gson.toJson(HashMap(map)))
            tmp.renameTo(file)
        }
    }

    /** Number of entries in memory (tests). */
    val size: Int get() = map.size
}
