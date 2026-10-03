package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local favorites, fav.json format, additive merge, conflicts, read-only and broken remotes. */
class FavoritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun repo() = FavoritesRepository(java.io.File(tmp.root, "fav/fav.json")) { now }

    private val song = MusicFile("01 曲 #1+%.flac", "/fixture/Album-A/01 曲 #1+%.flac", false, 10, 0, "smb-1")
    private val folder = MusicFile("Album-A", "/fixture/Album-A", true, 0, 0, "smb-1")
    private val sameOnOtherSource = song.copy(sourceId = "ftp-1")

    @Test fun addRemoveAndRestoreKeepsSourceIdentity() {
        val r = repo()
        r.add(song); r.add(folder); r.add(sameOnOtherSource); r.add(song)
        assertEquals(3, r.items.value.size)
        assertTrue(r.isFavorite(song.ref, false))
        assertTrue(r.isFavorite(folder.ref, true))
        assertFalse(r.isFavorite(song.ref, true)) // same path as folder type: different entry
        r.remove(sameOnOtherSource.ref, false)
        assertTrue(r.isFavorite(song.ref, false))
        // "Restart": a new repository instance reads the file.
        val again = repo()
        assertEquals(listOf("SONG", "FOLDER"), again.items.value.map { it.type })
        assertEquals("smb-1", again.items.value[0].sourceId)
    }

    @Test fun fileFormatHasVersionAndItemsWithoutCredentials() {
        val r = repo()
        r.add(song)
        val json = java.io.File(tmp.root, "fav/fav.json").readText()
        val data = FavoritesCodec.decode(json)
        assertEquals(1, data.version)
        assertEquals(listOf("id", "type", "sourceId", "path", "name", "size", "timestamp", "transferStatus").sorted(),
            com.google.gson.JsonParser.parseString(json).asJsonObject["items"].asJsonArray[0].asJsonObject.keySet().sorted())
        assertFalse(json.contains("password", ignoreCase = true))
    }

    @Test fun strictDecodeRejectsBrokenJson() {
        for (bad in listOf("{", "[]", "{\"items\":[]}", "{\"version\":1}", "{\"version\":99,\"items\":[]}")) {
            try { FavoritesCodec.decode(bad); fail("accepted $bad") } catch (e: FavoritesFormatException) {}
        }
    }

    @Test fun mergeIsAdditiveAndNewerWins() {
        val a = FavoriteItem(id = "1", sourceId = "s", path = "/a", name = "local", timestamp = 10)
        val b = FavoriteItem(id = "2", sourceId = "s", path = "/b", name = "only-local", timestamp = 10)
        val a2 = FavoriteItem(id = "3", sourceId = "s", path = "/a", name = "remote-newer", timestamp = 20)
        val c = FavoriteItem(id = "4", sourceId = "s", path = "/c", name = "only-remote", timestamp = 5)
        val merged = FavoritesCodec.merge(listOf(a, b), listOf(a2, c))
        assertEquals(listOf("remote-newer", "only-local", "only-remote"), merged.map { it.name })
        val olderRemote = FavoritesCodec.merge(listOf(a2), listOf(a))
        assertEquals("remote-newer", olderRemote.single().name)
    }

    @Test fun syncCreatesMergesAndNeverDeletesRemoteEntries() {
        val remote = InMemoryFileSystem("nas")
        val r = repo()
        r.add(song)
        assertTrue(r.sync(remote, "/sync/fav.json") is SyncResult.Synced)
        assertEquals(1, FavoritesCodec.decode(String(remote.files["/sync/fav.json"]!!)).items.size)

        // Another device added a folder remotely.
        val other = FavoritesData(items = listOf(FavoriteItem(sourceId = "smb-1", path = "/x", type = "FOLDER", timestamp = 5)) +
            FavoritesCodec.decode(String(remote.files["/sync/fav.json"]!!)).items)
        remote.put("/sync/fav.json", FavoritesCodec.encode(other))
        r.remove(song.ref, false) // local deletion is NOT propagated
        val res = r.sync(remote, "/sync/fav.json") as SyncResult.Synced
        assertEquals(2, res.total)
        assertTrue(r.isFavorite(song.ref, false)) // came back from the remote copy
        assertEquals(2, FavoritesCodec.decode(String(remote.files["/sync/fav.json"]!!)).items.size)
        assertFalse(remote.files.keys.any { it.endsWith(".fp-tmp") })
    }

    @Test fun brokenRemoteIsNeverOverwrittenBySync() {
        val remote = InMemoryFileSystem("nas")
        remote.put("/fav.json", "{ this is not json")
        val r = repo()
        r.add(song)
        val res = r.sync(remote, "/fav.json")
        assertTrue(res is SyncResult.RemoteCorrupt)
        assertEquals("{ this is not json", String(remote.files["/fav.json"]!!))
        assertEquals(0, remote.writes)
        // Only the explicit replace overwrites it.
        assertTrue(r.replaceRemote(remote, "/fav.json") is SyncResult.Synced)
        assertEquals(1, FavoritesCodec.decode(String(remote.files["/fav.json"]!!)).items.size)
    }

    @Test fun readOnlyRemoteFailsOnlyTheSync() {
        val remote = InMemoryFileSystem("ro", writable = false)
        remote.put("/fav.json", FavoritesCodec.encode(FavoritesData(items = listOf(FavoriteItem(sourceId = "s", path = "/r", timestamp = 1)))))
        val r = repo()
        r.add(song)
        val res = r.sync(remote, "/fav.json")
        assertTrue(res is SyncResult.RemoteNotWritable)
        assertEquals(2, r.items.value.size) // remote entry merged locally, local kept
    }

    @Test fun corruptLocalFileIsKeptAside() {
        val f = java.io.File(tmp.root, "fav/fav.json").apply { parentFile!!.mkdirs(); writeText("garbage") }
        val r = repo()
        assertTrue(r.items.value.isEmpty())
        assertTrue(tmp.root.resolve("fav").listFiles()!!.any { it.name.startsWith("fav.json.corrupt-") })
        assertFalse(f.exists())
    }

    // ---- entries this version cannot read must not be dropped silently ----

    private val remoteWithInvalid = """{
      "version": 1,
      "items": [
        {"id": "1", "type": "SONG", "sourceId": "smb-1", "path": "/ok.flac", "name": "ok", "timestamp": 5},
        {"id": "2", "type": "SONG", "sourceId": "", "path": "relative/no-source.flac", "name": "future-format", "timestamp": 6}
      ]
    }"""

    @Test fun decodeReportsRejectedEntries() {
        val d = FavoritesCodec.decodeReport(remoteWithInvalid)
        assertEquals(listOf("/ok.flac"), d.data.items.map { it.path })
        assertEquals(1, d.rejected)
    }

    @Test fun normalSyncNeverRewritesARemoteFileWithUnreadableEntries() {
        val remote = InMemoryFileSystem("nas")
        remote.put("/fav.json", remoteWithInvalid)
        val r = repo()
        r.add(song)
        val res = r.sync(remote, "/fav.json")
        assertEquals("remote file untouched", remoteWithInvalid, String(remote.files["/fav.json"]!!))
        assertEquals(0, remote.writes)
        assertTrue("reported, not 'synced': $res", res is SyncResult.RemoteHasUnreadableEntries && res.rejected == 1)
        // The readable remote entry is still merged locally.
        assertTrue(r.items.value.any { it.path == "/ok.flac" })
    }

    @Test fun syncDoesNotRewriteAnUnchangedRemote() {
        val remote = InMemoryFileSystem("nas")
        val r = repo()
        r.add(song)
        assertTrue(r.sync(remote, "/fav.json") is SyncResult.Synced)
        val writes = remote.writes
        assertTrue(r.sync(remote, "/fav.json") is SyncResult.Synced)
        assertEquals("nothing changed, nothing written", writes, remote.writes)
    }

    @Test fun localFileWithUnreadableEntriesIsBackedUpBeforeRewrite() {
        val f = java.io.File(tmp.root, "fav/fav.json").apply { parentFile!!.mkdirs(); writeText(remoteWithInvalid) }
        val r = repo()
        assertEquals(1, r.items.value.size)
        r.add(song)
        val backups = tmp.root.resolve("fav").listFiles()!!.filter { it.name.startsWith("fav.json.unreadable-") }
        assertEquals(1, backups.size)
        assertEquals(remoteWithInvalid, backups.single().readText())
        assertTrue(f.exists())
    }
}
