package com.wing.folderplayer.protocol

import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.wing.folderplayer.data.source.ConnectionOutcome
import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.SmbFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.data.source.readText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** over real SMB2/3 against the Samba fixture container. */
class SmbProtocolTest {
    private lateinit var host: String
    private var port = 445

    @Before fun setUp() {
        ProtocolFixture.require("fp.smb.host")
        host = ProtocolFixture.prop("fp.smb.host")!!
        port = ProtocolFixture.prop("fp.smb.port")?.toInt() ?: 445
    }

    private fun fs(user: String, pass: String, share: String = "music", root: String? = null, id: String = "smb-$user", anonymous: Boolean = false): SmbFileSystem {
        val cfg = SourceConfig(id = id, name = id, type = SourceType.SMB, host = host, port = port, share = share, path = root, username = user, anonymous = anonymous)
        val store = InMemoryCredentialStore().apply { put(cfg.effectiveCredentialRef, pass) }
        return SmbFileSystem(cfg, store)
    }

    @Test fun negotiatesSmb3WithSigning() {
        val client = SMBClient(SmbConfig.builder().withSecurityProvider(BCSecurityProvider()).build())
        client.connect(host, port).use { conn ->
            val dialect = conn.connectionContext.negotiatedProtocol.dialect
            println("SMB negotiated dialect: $dialect")
            assertTrue("dialect $dialect", dialect.name.startsWith("SMB_3"))
            conn.authenticate(AuthenticationContext("alice", "alicepass".toCharArray(), null)).use { s ->
                assertTrue(s.connection.isConnected)
            }
        }
        client.close()
    }

    @Test fun listsWithSizesMtimeAndTrickyNames() {
        fs("alice", "alicepass").use { smb ->
            val entries = smb.list("/fixture/Album-A")
            val byName = entries.associateBy { it.name }
            assertTrue(byName.keys.toString(), byName.containsKey("01 曲 #1+%.flac"))
            for (n in listOf("01 曲 #1+%.flac", "cover.jpg", "folder.png", "01 曲 #1+%.lrc", "Info.nfo")) {
                assertEquals(n, ProtocolFixture.local("/fixture/Album-A/$n").length(), byName[n]!!.size)
                assertTrue(byName[n]!!.lastModified > 0)
                assertEquals("/fixture/Album-A/$n", byName[n]!!.path)
            }
            assertTrue(smb.list("/fixture").first { it.name == "Album-A" }.isDirectory)
        }
    }

    @Test fun positionalReadsMatchFixtureBytes() {
        val local = ProtocolFixture.local(ProtocolFixture.TRICKY).readBytes()
        fs("alice", "alicepass").use { smb ->
            smb.openRead(ProtocolFixture.TRICKY).use { assertArrayEquals(local, it.readBytes()) }
            for (off in listOf(0L, 1L, 12_345L, local.size / 2L, local.size - 1000L)) {
                smb.openRead(ProtocolFixture.TRICKY, off, 1000).use { s ->
                    val got = s.readBytes()
                    assertArrayEquals("offset $off", local.copyOfRange(off.toInt(), minOf(local.size, off.toInt() + 1000)), got)
                }
            }
            smb.openRandomAccess(ProtocolFixture.TRICKY).use { ra ->
                assertEquals(local.size.toLong(), ra.size)
                val buf = ByteArray(4096)
                val near = local.size - 100L
                val n = ra.read(near, buf, 0, 4096)
                assertEquals(100, n)
                assertArrayEquals(local.copyOfRange(near.toInt(), local.size), buf.copyOf(100))
                assertEquals(-1, ra.read(local.size.toLong(), buf, 0, 10))
            }
        }
    }

    @Test fun readsImagesLyricsCueAndNfo() {
        fs("alice", "alicepass").use { smb ->
            assertArrayEquals(ProtocolFixture.local("/fixture/Album-A/cover.jpg").readBytes(), smb.readBytes("/fixture/Album-A/cover.jpg", 1 shl 22))
            assertTrue(smb.readText("/fixture/Album-A/01 曲 #1+%.lrc").contains("LRC line two #1+%"))
            assertTrue(smb.readText("/fixture/Cue/image.cue").contains("TRACK 03"))
            assertTrue(smb.readText("/fixture/Album-A/Info.nfo").contains("<album>"))
            assertTrue(smb.readText("/fixture/Unindexed/Info.nfo").contains("Hidden Artist"))
        }
    }

    @Test fun connectionTestDistinguishesFailures() {
        assertOutcome(ConnectionOutcome.OK, fs("alice", "alicepass", root = "/fixture"))
        assertOutcome(ConnectionOutcome.AUTH_FAILED, fs("alice", "wrong"))
        assertOutcome(ConnectionOutcome.SHARE_MISSING, fs("alice", "alicepass", share = ""))
        assertOutcome(ConnectionOutcome.SHARE_NOT_FOUND, fs("alice", "alicepass", share = "nosuchshare"))
        assertOutcome(ConnectionOutcome.ROOT_NOT_FOUND, fs("alice", "alicepass", root = "/does-not-exist"))
        assertOutcome(ConnectionOutcome.PERMISSION_DENIED, fs("alice", "alicepass", root = "/Locked"))
        assertOutcome(ConnectionOutcome.PERMISSION_DENIED, fs("bob", "bobpass", share = "alice"))
        val unreachable = SmbFileSystem(SourceConfig(id = "u", type = SourceType.SMB, host = "127.0.0.1", port = 1, share = "music", username = "a"), InMemoryCredentialStore())
        assertOutcome(ConnectionOutcome.UNREACHABLE, unreachable)
    }

    @Test fun guestLoginOnlyWhenChosen() {
        val guest = fs("", "", share = "public", anonymous = true)
        assertOutcome(ConnectionOutcome.OK, guest)
        // Without the explicit guest option a wrong password is an error, never a silent guest fallback.
        assertOutcome(ConnectionOutcome.AUTH_FAILED, fs("alice", "wrong", share = "public"))
    }

    @Test fun sameHostAndPathWithDifferentCredentialsStaySeparate() {
        val a = fs("alice", "alicepass", share = "home", id = "A")
        val b = fs("bob", "bobpass", share = "home", id = "B")
        repeat(5) {
            assertEquals("alice", a.readText("/Album/owner.txt").trim())
            assertEquals("bob", b.readText("/Album/owner.txt").trim())
        }
        assertTrue(a.list("/Album").any { it.name == "cover.jpg" })
        assertTrue(b.list("/Album").any { it.name == "cover.png" })
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 40).map { i -> pool.submit<Pair<String, String>> { val f = if (i % 2 == 0) a else b; (if (i % 2 == 0) "alice" else "bob") to f.readText("/Album/owner.txt").trim() } }
        results.forEach { val (want, got) = it.get(30, TimeUnit.SECONDS); assertEquals(want, got) }
        pool.shutdown()
        assertFalse(a.config.toString().contains("alicepass"))
        a.close(); b.close()
    }

    @Test fun writeNeedsPermission() {
        val name = "/rw/write-test-${System.nanoTime()}.txt"
        fs("alice", "alicepass").use { smb ->
            smb.write(name, "hello".toByteArray(), overwrite = false)
            assertEquals("hello", smb.readText(name))
            assertTrue(smb.rename(name, "$name.renamed"))
            assertTrue(smb.delete("$name.renamed"))
        }
        fs("bob", "bobpass").use { smb ->
            try {
                smb.write(name, "x".toByteArray(), overwrite = true)
                fail("bob could write")
            } catch (e: SourceException.PermissionDenied) {
            }
        }
    }

    @Test fun reconnectsAfterIdleClose() {
        val smb = fs("alice", "alicepass")
        smb.list("/fixture")
        smb.close() // drops the session like an idle timeout would
        assertTrue(smb.list("/fixture").isNotEmpty())
        smb.close()
    }

    private fun assertOutcome(expected: ConnectionOutcome, fs: com.wing.folderplayer.data.source.SourceFileSystem) {
        val r = fs.testConnection()
        assertEquals("${r.outcome}: ${r.detail}", expected, r.outcome)
    }
}
