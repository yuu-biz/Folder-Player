package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.source.ConnectionOutcome
import com.wing.folderplayer.data.source.FtpConnectionFactory
import com.wing.folderplayer.data.source.FtpFileSystem
import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.ReopeningRandomAccessReader
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.data.source.readText
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** over real FTP and explicit FTPS against the pyftpdlib fixture container. */
class FtpProtocolTest {
    private lateinit var host: String

    @Before fun setUp() {
        ProtocolFixture.require("fp.ftp.host")
        host = ProtocolFixture.prop("fp.ftp.host")!!
    }

    private fun cfg(port: Int, user: String, tls: Boolean = false, pin: String = "", root: String? = null, anonymous: Boolean = false) =
        SourceConfig(id = "ftp-$port-$user", name = "ftp", type = SourceType.FTP, host = host, port = port, username = user,
            useTls = tls, tlsPinnedSha256 = pin, path = root, anonymous = anonymous)

    private fun fs(port: Int, user: String, pass: String, tls: Boolean = false, pin: String = "", root: String? = null): FtpFileSystem {
        val c = cfg(port, user, tls, pin, root)
        return FtpFileSystem(c, InMemoryCredentialStore().apply { put(c.effectiveCredentialRef, pass) })
    }

    @Test fun passiveBinaryLogin() {
        val client: FTPClient = FtpConnectionFactory(cfg(2121, "alice"), "alicepass").connect()
        assertEquals(FTPClient.PASSIVE_LOCAL_DATA_CONNECTION_MODE, client.dataConnectionMode)
        assertTrue(client.hasFeature("MLST"))
        assertTrue(client.hasFeature("REST"))
        client.logout(); client.disconnect()
    }

    @Test fun listRetrAndRest() {
        val local = ProtocolFixture.local(ProtocolFixture.TRICKY).readBytes()
        fs(2121, "alice", "alicepass").use { ftp ->
            val entries = ftp.list("/fixture/Album-A").associateBy { it.name }
            assertEquals(local.size.toLong(), entries["01 曲 #1+%.flac"]!!.size)
            assertTrue(entries["01 曲 #1+%.flac"]!!.lastModified > 0)
            assertTrue(ftp.list("/fixture").first { it.name == "Album-A" }.isDirectory)
            ftp.openRead(ProtocolFixture.TRICKY).use { assertArrayEquals(local, it.readBytes()) } // TYPE I: byte exact
            for (off in listOf(1L, 54_321L, local.size - 512L)) {
                ftp.openRead(ProtocolFixture.TRICKY, off, 512).use { s ->
                    assertArrayEquals("REST $off", local.copyOfRange(off.toInt(), minOf(local.size, off.toInt() + 512)), s.readBytes())
                }
            }
            ReopeningRandomAccessReader(ftp, ProtocolFixture.TRICKY).use { ra ->
                val buf = ByteArray(1000)
                assertEquals(1000, ra.read(200_000, buf, 0, 1000))
                assertArrayEquals(local.copyOfRange(200_000, 201_000), buf)
                val n = ra.read(10, buf, 0, 100) // backwards seek reopens with REST
                assertEquals(100, n)
                assertArrayEquals(local.copyOfRange(10, 110), buf.copyOf(100))
            }
            assertTrue(ftp.readText("/fixture/Album-A/01 曲 #1+%.lrc").contains("曲"))
            assertArrayEquals(ProtocolFixture.local("/fixture/Album-A/folder.png").readBytes(), ftp.readBytes("/fixture/Album-A/folder.png", 1 shl 22))
            // The connection pool stays healthy after many partial transfers.
            repeat(10) { ftp.openRead("/fixture/Cue/image.flac", 1000L * it, 100).use { s -> assertEquals(100, s.readBytes().size) } }
            assertTrue(ftp.list("/fixture/Cue").isNotEmpty())
        }
    }

    @Test fun serverWithoutRestReportsSeekUnsupported() {
        fs(2122, "alice", "alicepass").use { ftp ->
            ftp.openRead("/fixture/Album-B/track.flac").use { assertTrue(it.readBytes().isNotEmpty()) }
            try {
                ftp.openRead("/fixture/Album-B/track.flac", 1000)
                fail("REST failure treated as success")
            } catch (e: SourceException.SeekUnsupported) {
            }
        }
    }

    @Test fun earlyEndOfDataIsAnError() {
        fs(2123, "alice", "alicepass").use { ftp ->
            try {
                ftp.openRead("/fixture/Album-B/track.flac").use { it.readBytes() }
                fail("truncated transfer treated as end of file")
            } catch (e: SourceException.PrematureEof) {
            }
        }
    }

    @Test fun ftpsWithPinnedCertificate() {
        ProtocolFixture.require("fp.ftps.pin")
        val pin = ProtocolFixture.prop("fp.ftps.pin")!!
        fs(2124, "alice", "alicepass", tls = true, pin = pin).use { ftps ->
            assertOutcome(ConnectionOutcome.OK, ftps)
            val local = ProtocolFixture.local("/fixture/Album-B/track.flac").readBytes()
            ftps.openRead("/fixture/Album-B/track.flac").use { assertArrayEquals(local, it.readBytes()) }
            ftps.openRead("/fixture/Album-B/track.flac", 5000, 100).use { assertArrayEquals(local.copyOfRange(5000, 5100), it.readBytes()) }
        }
    }

    @Test fun ftpsRejectsUntrustedOrWrongCertificate() {
        // Self-signed certificate without an explicit pin: must fail, and must not fall back to plain FTP.
        assertOutcome(ConnectionOutcome.TLS_ERROR, fs(2124, "alice", "alicepass", tls = true))
        assertOutcome(ConnectionOutcome.TLS_ERROR, fs(2124, "alice", "alicepass", tls = true, pin = "00".repeat(32)))
        // TLS requested against a plain server: AUTH TLS refused → TLS error, no downgrade.
        assertOutcome(ConnectionOutcome.TLS_ERROR, fs(2121, "alice", "alicepass", tls = true, pin = "00".repeat(32)))
    }

    @Test fun connectionTestDistinguishesFailures() {
        assertOutcome(ConnectionOutcome.OK, fs(2121, "alice", "alicepass", root = "/fixture"))
        assertOutcome(ConnectionOutcome.AUTH_FAILED, fs(2121, "alice", "nope"))
        assertOutcome(ConnectionOutcome.ROOT_NOT_FOUND, fs(2121, "alice", "alicepass", root = "/does-not-exist"))
        assertOutcome(ConnectionOutcome.PERMISSION_DENIED, fs(2121, "alice", "alicepass", root = "/Locked"))
        assertOutcome(ConnectionOutcome.UNREACHABLE, fs(1, "alice", "alicepass"))
        val anon = FtpFileSystem(cfg(2121, "", anonymous = true), InMemoryCredentialStore())
        assertOutcome(ConnectionOutcome.OK, anon)
    }

    @Test fun writeRespectsAccountRights() {
        val name = "/rw/ftp-write-${System.nanoTime()}.json"
        fs(2121, "alice", "alicepass").use { ftp ->
            ftp.write(name, "{\"version\":1,\"items\":[]}".toByteArray(), overwrite = false)
            assertTrue(ftp.readText(name).contains("version"))
            assertTrue(ftp.rename(name, "$name.2"))
            assertTrue(ftp.delete("$name.2"))
        }
        fs(2121, "bob", "bobpass").use { ftp ->
            try {
                ftp.write(name, "x".toByteArray(), overwrite = true)
                fail("read-only account wrote")
            } catch (e: SourceException.PermissionDenied) {
            }
        }
    }

    private fun assertOutcome(expected: ConnectionOutcome, fs: com.wing.folderplayer.data.source.SourceFileSystem) {
        val r = fs.testConnection()
        assertEquals("${r.outcome}: ${r.detail}", expected, r.outcome)
    }
}
