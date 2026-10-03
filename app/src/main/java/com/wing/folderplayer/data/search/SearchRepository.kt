package com.wing.folderplayer.data.search

import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer

data class SearchProgress(
    val results: List<MusicFile>,
    val foldersScanned: Int,
    val foldersSkipped: Int,
    val done: Boolean,
    val truncated: Boolean = false,
)

/**
 * Recursive name search below one folder of one source. Never leaves [root]; each folder is listed once
 * (visited set), listings run with bounded parallelism, unreadable folders are skipped and counted, results are
 * unique by sourceId + path. Cancelling the collector stops the walk.
 */
class SearchRepository(
    private val lister: (SourceRef) -> List<MusicFile>,
    private val parallelism: Int = 3,
    private val maxResults: Int = 2000,
) {
    fun search(root: SourceRef, query: String): Flow<SearchProgress> = channelFlow {
        val needle = norm(query.trim())
        if (needle.isEmpty()) {
            send(SearchProgress(emptyList(), 0, 0, true))
            return@channelFlow
        }
        val visited = HashSet<String>()
        val results = LinkedHashMap<String, MusicFile>()
        val lock = Mutex()
        val semaphore = Semaphore(parallelism)
        var scanned = 0
        var skipped = 0
        var truncated = false

        suspend fun visit(folder: SourceRef): Unit = coroutineScope {
            val first = lock.withLock { visited.add(folder.path) }
            if (!first || truncated) return@coroutineScope
            val entries = try {
                semaphore.withPermit { runInterruptible(Dispatchers.IO) { lister(folder) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lock.withLock { skipped++ }
                return@coroutineScope
            }
            val subfolders = ArrayList<SourceRef>()
            lock.withLock {
                scanned++
                for (e in entries) {
                    // Only direct children of the listed folder are accepted (guards against odd server replies).
                    if (SourcePath.parent(e.path) != SourcePath.normalize(folder.path) || e.sourceId != root.sourceId) continue
                    if (e.isDirectory) subfolders.add(e.ref)
                    val match = (e.isDirectory || MediaTypes.isAudio(e.name) || MediaTypes.isCue(e.name)) && norm(e.name).contains(needle)
                    if (match && results.size < maxResults) results.putIfAbsent(e.sourceId + "\u0000" + e.path, e)
                    if (results.size >= maxResults) truncated = true
                }
            }
            send(lock.withLock { SearchProgress(results.values.toList(), scanned, skipped, false, truncated) })
            for (s in subfolders) launch { visit(s) }
        }

        visit(root)
        send(lock.withLock { SearchProgress(results.values.toList(), scanned, skipped, true, truncated) })
    }

    companion object {
        fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    }
}
