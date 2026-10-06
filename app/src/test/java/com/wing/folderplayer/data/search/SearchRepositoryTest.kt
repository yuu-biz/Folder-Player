package com.wing.folderplayer.data.search

import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.testutil.InMemoryFileSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** recursive search stays under the chosen folder, skips unreadable folders, dedups, cancels. */
class SearchRepositoryTest {
    private val fs = InMemoryFileSystem("s").apply {
        put("/Music/Jazz/Blue Train/01 Blue Train.flac", "a")
        put("/Music/Jazz/Blue Train/cover.jpg", "i")
        put("/Music/Jazz/Kind of Blue/01 So What.mp3", "a")
        put("/Music/Rock/Blue Sky.flac", "a")
        put("/Music/Locked/Blue Secret.flac", "a")
        put("/Outside/Blue Outside.flac", "a")
        put("/Music/日本/青い 曲 #1+%.flac", "a")
        unreadable.add("/Music/Locked")
    }
    private val repo = SearchRepository({ ref -> fs.list(ref.path) })

    @Test fun findsFoldersAndSongsBelowRootOnly() = runBlocking {
        val p = repo.search(SourceRef("s", "/Music"), "blue").last()
        val paths = p.results.map { it.path }.toSet()
        assertEquals(setOf("/Music/Jazz/Blue Train", "/Music/Jazz/Blue Train/01 Blue Train.flac",
            "/Music/Jazz/Kind of Blue", "/Music/Rock/Blue Sky.flac"), paths)
        assertTrue(p.done)
        assertEquals(1, p.foldersSkipped)
        assertEquals(p.results.size, paths.size)
        assertTrue(paths.none { it.startsWith("/Outside") })
    }

    @Test fun matchesNonAsciiAndSymbols() = runBlocking {
        assertEquals(listOf("/Music/日本/青い 曲 #1+%.flac"), repo.search(SourceRef("s", "/Music"), "曲 #1+%").last().results.map { it.path })
        assertEquals(1, repo.search(SourceRef("s", "/Music"), "ＢＬＵＥ ＳＫＹ").last().results.size) // NFKC full-width
    }

    @Test fun eachFolderListedOnce() {
        val counts = listCountsOf(fs, "zzz")
        assertEquals("exactly the folders below the root were listed", fs.dirs.filter { it.startsWith("/Music") }.toSet(), counts.keys)
        assertTrue("each folder listed once: $counts", counts.values.all { it == 1 })
    }

    @Test fun emptyQueryDoesNothing() = runBlocking {
        val p = repo.search(SourceRef("s", "/Music"), "  ").first()
        assertTrue(p.done && p.results.isEmpty())
    }

    @Test fun collectorCancellationStopsWalking() = runBlocking {
        val big = InMemoryFileSystem("b").apply { for (i in 0 until 300) put("/R/f$i/track $i.flac", "a") }
        var calls = 0
        val slow = SearchRepository({ ref -> calls++; Thread.sleep(5); big.list(ref.path) }, parallelism = 2)
        slow.search(SourceRef("b", "/R"), "track").take(3).toList()
        val after = calls
        Thread.sleep(200)
        assertTrue("walk continued after cancel: $after -> $calls", calls - after <= 2)
        assertTrue(after < 301)
    }

    @Test fun resultLimitTruncates() = runBlocking {
        val big = InMemoryFileSystem("b").apply { for (i in 0 until 50) put("/R/x$i.flac", "a") }
        val p = SearchRepository({ ref -> big.list(ref.path) }, maxResults = 10).search(SourceRef("b", "/R"), "x").last()
        assertEquals(10, p.results.size)
        assertTrue(p.truncated)
    }
}

/**
 * Searches [query] below `/Music` of [fs] and returns how often each path was listed. The search lists folders in
 * parallel, so the counting is thread-safe (a plain counter in the file system double lost updates now and then).
 */
internal fun listCountsOf(fs: InMemoryFileSystem, query: String): Map<String, Int> {
    val counts = ConcurrentHashMap<String, AtomicInteger>()
    val repo = SearchRepository({ ref -> counts.computeIfAbsent(ref.path) { AtomicInteger() }.incrementAndGet(); fs.list(ref.path) })
    runBlocking { repo.search(SourceRef("s", "/Music"), query).last() }
    return counts.mapValues { it.value.get() }
}
