package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A sync waits on the network for as long as the server takes; adding / removing a favourite from the UI meanwhile
 * must finish at once, and what it changed must survive the sync.
 */
class FavoritesSyncConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private val pool = Executors.newCachedThreadPool()
    private fun repo() = FavoritesRepository(java.io.File(tmp.root, "fav/fav.json")) { now }

    @After fun tearDown() { pool.shutdownNow() }

    private val remoteSong = FavoriteItem(sourceId = "smb-1", path = "/remote.flac", name = "remote.flac", timestamp = 5)
    private val localOnly = MusicFile("local.flac", "/local.flac", false, 1, 0, "smb-1")
    private val addedDuringSync = MusicFile("new.flac", "/new.flac", false, 1, 0, "smb-1")

    /** Remote whose reads (or writes) stop at a gate: a slow NAS, held until the test lets it go. */
    private class GatedFs(val inner: InMemoryFileSystem, private val gateWrites: Boolean = false) : SourceFileSystem by inner {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private fun hold() { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
        override fun stat(path: String) = inner.stat(path).also { if (!gateWrites) hold() }
        override fun write(path: String, data: ByteArray, overwrite: Boolean) { if (gateWrites) hold(); inner.write(path, data, overwrite) }
    }

    private fun remoteWith(vararg items: FavoriteItem) = InMemoryFileSystem("nas").apply {
        put("/fav.json", FavoritesCodec.encode(FavoritesData(items = items.toList())))
    }

    private fun <T> Future<T>.within(ms: Long, what: String): T = try {
        get(ms, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        throw AssertionError("$what did not finish within $ms ms: it waited for the sync's network I/O")
    }

    private fun remoteItems(fs: InMemoryFileSystem) = FavoritesCodec.decode(String(fs.files["/fav.json"]!!)).items

    @Test fun addAndRemoveDuringRemoteReadFinishAtOnceAndAreKept() {
        val r = repo()
        r.add(localOnly)
        val remote = GatedFs(remoteWith(remoteSong))
        val sync = pool.submit<SyncResult> { r.sync(remote, "/fav.json") }
        assertTrue("sync reached the network", remote.entered.await(5, TimeUnit.SECONDS))

        now = 2_000L
        pool.submit { r.add(addedDuringSync) }.within(1_000, "add during sync")
        pool.submit { r.remove(localOnly.ref, false) }.within(1_000, "remove during sync")
        assertTrue(r.isFavorite(addedDuringSync.ref, false))

        remote.release.countDown()
        val res = sync.get(10, TimeUnit.SECONDS)
        assertTrue("$res", res is SyncResult.Synced)
        val paths = r.items.value.map { it.path }.toSet()
        assertEquals("local add kept, remote entry merged, local-only removal kept", setOf("/new.flac", "/remote.flac"), paths)
        assertEquals(setOf("/new.flac", "/remote.flac"), remoteItems(remote.inner).map { it.path }.toSet())
        // The file on disk agrees (a restart reads it).
        assertEquals(paths, repo().items.value.map { it.path }.toSet())
    }

    @Test fun addDuringRemoteWriteIsKeptAndReachesTheRemoteNextTime() {
        val r = repo()
        r.add(localOnly)
        val remote = GatedFs(remoteWith(remoteSong), gateWrites = true)
        val sync = pool.submit<SyncResult> { r.sync(remote, "/fav.json") }
        assertTrue("sync reached the remote write", remote.entered.await(5, TimeUnit.SECONDS))

        pool.submit { r.add(addedDuringSync) }.within(1_000, "add during the remote write")

        remote.release.countDown()
        assertTrue(sync.get(10, TimeUnit.SECONDS) is SyncResult.Synced)
        assertEquals(setOf("/local.flac", "/remote.flac", "/new.flac"), r.items.value.map { it.path }.toSet())
        assertFalse("written before the add", remoteItems(remote.inner).any { it.path == "/new.flac" })
        assertTrue(r.sync(remote, "/fav.json") is SyncResult.Synced)
        assertTrue(remoteItems(remote.inner).any { it.path == "/new.flac" })
    }

    @Test fun syncsRunOneAfterAnother() {
        val r = repo()
        r.add(localOnly)
        val slow = GatedFs(remoteWith(remoteSong))
        val first = pool.submit<SyncResult> { r.sync(slow, "/fav.json") }
        assertTrue(slow.entered.await(5, TimeUnit.SECONDS))
        val other = InMemoryFileSystem("nas2")
        val second = pool.submit<SyncResult> { r.sync(other, "/fav.json") }
        Thread.sleep(300)
        assertFalse("a second sync waits for the first one", second.isDone)
        slow.release.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is SyncResult.Synced)
        assertTrue(second.get(10, TimeUnit.SECONDS) is SyncResult.Synced)
        assertEquals(setOf("/local.flac", "/remote.flac"), remoteItems(other).map { it.path }.toSet())
    }
}
