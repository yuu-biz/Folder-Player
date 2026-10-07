package com.wing.folderplayer.data.favorites

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two devices share one fav.json on a NAS. Each has its own source id (a random UUID per installation) and may have a
 * different root inside the share, so the file must say where an entry is on the server, not which device's source it
 * came from. The file is the anchor: whichever source it is read from is "the source this fav.json is on".
 */
class FavoritesCrossDeviceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun repo(device: String) = FavoritesRepository(java.io.File(tmp.root, "$device/fav.json")) { now }

    private val shared = "@sync"

    private fun song(sourceId: String, path: String) = MusicFile(path.substringAfterLast('/'), path, false, 10, 0, sourceId)
    private fun folder(sourceId: String, path: String) = MusicFile(path.substringAfterLast('/'), path, true, 0, 0, sourceId)

    private fun syncAs(r: FavoritesRepository, fs: SourceFileSystem, sourceId: String, root: String = "/") =
        r.sync(fs, "/fav.json", SyncAnchor(sourceId, root))

    private fun replaceAs(r: FavoritesRepository, fs: SourceFileSystem, sourceId: String, root: String = "/") =
        r.replaceRemote(fs, "/fav.json", SyncAnchor(sourceId, root))

    private fun remoteEntries(fs: InMemoryFileSystem) = FavoritesCodec.decode(String(fs.files["/fav.json"]!!)).items

    private fun pathsOf(r: FavoritesRepository, sourceId: String) = r.items.value.filter { it.sourceId == sourceId }.map { it.path }.toSet()

    @Test fun anotherDeviceSeesTheFavouriteUnderItsOwnSourceId() {
        val nas = InMemoryFileSystem("nas")
        val a = repo("a"); val b = repo("b")
        a.add(song("uuid-a", "/Library/Album/01.flac")); a.add(folder("uuid-a", "/Library/Album"))
        assertTrue(syncAs(a, nas, "uuid-a") is SyncResult.Synced)

        assertTrue(syncAs(b, nas, "uuid-b") is SyncResult.Synced)
        assertEquals(setOf("/Library/Album/01.flac", "/Library/Album"), pathsOf(b, "uuid-b"))
        assertTrue("B can look the entry up through its own source", b.isFavorite(com.wing.folderplayer.data.source.SourceRef("uuid-b", "/Library/Album"), true))
        assertFalse("nothing is left under the other device's id", b.items.value.any { it.sourceId == "uuid-a" })
    }

    @Test fun differentRootsInsideTheShareAreTranslatedBothWays() {
        val nas = InMemoryFileSystem("nas")
        val a = repo("a"); val b = repo("b")
        a.add(folder("uuid-a", "/Library/Album"))
        syncAs(a, nas, "uuid-a", root = "/")

        syncAs(b, nas, "uuid-b", root = "/Library")
        assertEquals(setOf("/Album"), pathsOf(b, "uuid-b"))

        b.add(folder("uuid-b", "/Other"))
        syncAs(b, nas, "uuid-b", root = "/Library")
        syncAs(a, nas, "uuid-a", root = "/")
        assertEquals(setOf("/Library/Album", "/Library/Other"), pathsOf(a, "uuid-a"))
    }

    @Test fun entryOutsideThisDevicesRootIsHiddenButNeverLostFromTheFile() {
        val nas = InMemoryFileSystem("nas")
        val a = repo("a"); val b = repo("b"); val c = repo("c")
        a.add(song("uuid-a", "/Elsewhere/x.flac")); a.add(song("uuid-a", "/Library/Album/y.flac"))
        syncAs(a, nas, "uuid-a")

        syncAs(b, nas, "uuid-b", root = "/Library")
        assertEquals("only what is below B's root is listed", setOf("/Album/y.flac"), pathsOf(b, "uuid-b"))
        assertTrue(b.items.value.none { it.sourceId == shared })

        b.add(folder("uuid-b", "/New"))
        syncAs(b, nas, "uuid-b", root = "/Library")
        assertTrue("still in the file after B wrote it", remoteEntries(nas).any { it.path == "/Elsewhere/x.flac" })

        syncAs(c, nas, "uuid-c", root = "/")
        assertEquals(setOf("/Elsewhere/x.flac", "/Library/Album/y.flac", "/Library/New"), pathsOf(c, "uuid-c"))
    }

    @Test fun otherSourcesOfADeviceAreWrittenUnchanged() {
        val nas = InMemoryFileSystem("nas")
        val a = repo("a")
        a.add(song("uuid-a", "/on-nas.flac")); a.add(song("local-internal", "/Music/on-phone.flac"))
        syncAs(a, nas, "uuid-a")
        val entries = remoteEntries(nas)
        assertEquals(shared, entries.single { it.path == "/on-nas.flac" }.sourceId)
        assertEquals("local-internal", entries.single { it.path == "/Music/on-phone.flac" }.sourceId)
    }

    @Test fun anUnchangedSharedFileIsNotRewrittenByAnotherDevice() {
        val nas = InMemoryFileSystem("nas")
        val a = repo("a"); val b = repo("b")
        a.add(song("uuid-a", "/Library/x.flac"))
        syncAs(a, nas, "uuid-a")
        syncAs(b, nas, "uuid-b", root = "/Library")
        val writes = nas.writes
        assertTrue(syncAs(b, nas, "uuid-b", root = "/Library") is SyncResult.Synced)
        assertTrue(syncAs(a, nas, "uuid-a") is SyncResult.Synced)
        assertEquals("nothing changed, nothing written", writes, nas.writes)
    }

    @Test fun entriesWrittenByAnEarlierVersionUnderThisDevicesIdAreRewrittenInTheSharedForm() {
        val nas = InMemoryFileSystem("nas")
        nas.put("/fav.json", FavoritesCodec.encode(FavoritesData(items = listOf(
            FavoriteItem(id = "1", sourceId = "uuid-a", path = "/old.flac", name = "old.flac", timestamp = 5)))))
        val a = repo("a")
        assertTrue(syncAs(a, nas, "uuid-a") is SyncResult.Synced)
        assertEquals(setOf("/old.flac"), pathsOf(a, "uuid-a"))
        assertEquals(shared, remoteEntries(nas).single().sourceId)
    }

    @Test fun replaceRemoteWritesTheSharedFormToo() {
        val nas = InMemoryFileSystem("nas")
        val b = repo("b"); val a = repo("a")
        b.add(folder("uuid-b", "/Album"))
        assertTrue(replaceAs(b, nas, "uuid-b", root = "/Library") is SyncResult.Synced)
        val e = remoteEntries(nas).single()
        assertEquals(shared, e.sourceId); assertEquals("/Library/Album", e.path)
        syncAs(a, nas, "uuid-a")
        assertEquals(setOf("/Library/Album"), pathsOf(a, "uuid-a"))
    }

    @Test fun serverOriginIsTheSameForEveryWayOfReachingTheSameFolder() {
        fun dav(url: String, root: String?) = SourceConfig(id = "x", type = SourceType.WEBDAV, url = url, path = root)
        assertEquals("/dav/Music", SyncAnchor.serverOrigin(dav("http://nas/dav/", "/Music")))
        assertEquals("/dav/Music", SyncAnchor.serverOrigin(dav("nas/dav", "Music")))
        assertEquals("/dav/Music", SyncAnchor.serverOrigin(dav("https://nas:5006/", "/dav/Music")))
        assertEquals("/", SyncAnchor.serverOrigin(dav("http://nas", null)))
        assertEquals("/Music", SyncAnchor.serverOrigin(SourceConfig(id = "s", type = SourceType.SMB, host = "nas", share = "music", path = "/Music")))
        assertEquals("/", SyncAnchor.serverOrigin(SourceConfig(id = "f", type = SourceType.FTP, host = "nas")))
        // The same folder through two WebDAV sources set up differently on two devices.
        val a = SyncAnchor.of(dav("http://nas/dav/", "/"))
        val b = SyncAnchor.of(dav("http://192.168.0.5/", "/dav"))
        assertEquals(a.origin, b.origin)
    }

    @Test fun aBrokenOrUnreadableRemoteIsStillNeverOverwrittenWithAnAnchor() {
        val nas = InMemoryFileSystem("nas")
        nas.put("/fav.json", "{ not json")
        val a = repo("a"); a.add(song("uuid-a", "/x.flac"))
        assertTrue(syncAs(a, nas, "uuid-a") is SyncResult.RemoteCorrupt)
        assertEquals("{ not json", String(nas.files["/fav.json"]!!))
        assertEquals(0, nas.writes)
    }
}
