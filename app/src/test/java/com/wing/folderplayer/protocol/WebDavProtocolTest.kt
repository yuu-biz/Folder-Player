package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.favorites.FavoritesCodec
import com.wing.folderplayer.data.favorites.FavoritesRepository
import com.wing.folderplayer.data.favorites.SyncResult
import com.wing.folderplayer.data.nfo.NfoParser
import com.wing.folderplayer.data.source.ConnectionOutcome
import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.WebDavFileSystem
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.data.source.readText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WebDAV against Apache mod_dav (authenticated): listing, Range, write rights, fav.json sync, NFO round trip. */
class WebDavProtocolTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var url: String

    @Before fun setUp() {
        ProtocolFixture.require("fp.webdav.url")
        url = ProtocolFixture.prop("fp.webdav.url")!!
    }

    private fun fs(user: String, pass: String, root: String? = null): WebDavFileSystem {
        val c = SourceConfig(id = "dav-$user", name = "dav", type = SourceType.WEBDAV, url = url, path = root, username = user)
        return WebDavFileSystem(c, InMemoryCredentialStore().apply { put(c.effectiveCredentialRef, pass) })
    }

    @Test fun listsAndReadsWithRanges() {
        val local = ProtocolFixture.local(ProtocolFixture.TRICKY).readBytes()
        val dav = fs("bob", "bobpass")
        val e = dav.list("/fixture/Album-A").associateBy { it.name }
        assertEquals(local.size.toLong(), e["01 曲 #1+%.flac"]!!.size)
        dav.openRead(ProtocolFixture.TRICKY, 100_000, 4096).use { assertArrayEquals(local.copyOfRange(100_000, 104_096), it.readBytes()) }
        dav.openRead(ProtocolFixture.TRICKY).use { assertArrayEquals(local, it.readBytes()) }
        assertTrue(dav.readText("/fixture/Album-A/01 曲 #1+%.lrc").contains("#1+%"))
        assertArrayEquals(ProtocolFixture.local("/fixture/Album-A/cover.jpg").readBytes(), dav.readBytes("/fixture/Album-A/cover.jpg", 1 shl 22))
        assertOutcome(ConnectionOutcome.OK, fs("alice", "pa:ss/1", "/fixture"))
        assertOutcome(ConnectionOutcome.AUTH_FAILED, fs("alice", "wrong"))
        assertOutcome(ConnectionOutcome.ROOT_NOT_FOUND, fs("alice", "pa:ss/1", "/nope"))
    }

    @Test fun favoritesSyncAndNfoSaveRespectWriteRights() {
        val path = "/rw/fav-${System.nanoTime()}.json"
        val alice = fs("alice", "pa:ss/1")
        val repo = FavoritesRepository(tmp.root.resolve("fav.json"))
        repo.add(MusicFile("t.flac", "/fixture/Album-B/track.flac", false, 1, 0, "dav-alice"))
        repo.sync(alice, path).let { assertTrue(it.toString(), it is SyncResult.Synced) }
        assertEquals(1, FavoritesCodec.decode(alice.readText(path)).items.size)
        // Read-only account with a local entry the remote lacks: sync fails only for the remote part.
        val bobRepo = FavoritesRepository(tmp.root.resolve("bob.json"))
        bobRepo.add(MusicFile("b.flac", "/fixture/Album-A/b.flac", false, 1, 0, "dav-bob"))
        bobRepo.sync(fs("bob", "bobpass"), path).let { assertTrue(it.toString(), it is SyncResult.RemoteNotWritable) }
        assertEquals(2, bobRepo.items.value.size) // merged remote entry locally
        // Nothing new locally: the remote is not rewritten at all (no write attempt, so no error either).
        FavoritesRepository(tmp.root.resolve("carol.json")).sync(fs("bob", "bobpass"), path)
            .let { assertTrue(it.toString(), it is SyncResult.Synced) }

        val nfoPath = "/rw/Info-${System.nanoTime()}.nfo"
        alice.write(nfoPath, NfoParser.toXml("T", "A", "Review", null).toByteArray(), overwrite = true)
        assertEquals("Review", NfoParser.parse(alice.readBytes(nfoPath, 1 shl 20), "Info.nfo").description)
        try {
            fs("bob", "bobpass").write(nfoPath, "x".toByteArray(), overwrite = true)
            fail("bob wrote")
        } catch (e: SourceException.PermissionDenied) {
        }
    }

    private fun assertOutcome(expected: ConnectionOutcome, fs: com.wing.folderplayer.data.source.SourceFileSystem) {
        val r = fs.testConnection()
        assertEquals("${r.outcome}: ${r.detail}", expected, r.outcome)
    }
}
