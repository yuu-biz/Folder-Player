package com.wing.folderplayer.cast

import com.wing.folderplayer.data.source.FtpFileSystem
import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.LocalFileSystem
import com.wing.folderplayer.data.source.SmbFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.WebDavFileSystem
import com.wing.folderplayer.protocol.ProtocolFixture
import com.wing.folderplayer.testutil.InMemoryFileSystem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** HTTP relay semantics (200/206/416, Range, Content-Length, HEAD, tokens, stop) over every source type. */
class RelayServerTest {
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private var relay: RelayServer? = null

    @After fun tearDown() { relay?.stop() }

    private fun start(fs: Map<String, SourceFileSystem>): RelayServer =
        RelayServer({ ref -> fs.getValue(ref.sourceId) }, preferredPort = 0).also { it.start(); relay = it }

    private fun get(url: String, range: String? = null, head: Boolean = false): okhttp3.Response {
        val b = Request.Builder().url(url)
        if (range != null) b.header("Range", range)
        if (head) b.head()
        return http.newCall(b.build()).execute()
    }

    @Test fun rangeSemanticsOnInMemorySource() {
        val data = ByteArray(10_000) { (it * 7).toByte() }
        val mem = InMemoryFileSystem("m").apply { put("/a/song.flac", data) }
        val r = start(mapOf("m" to mem))
        val url = "http://127.0.0.1:${r.port}" + r.register(SourceRef("m", "/a/song.flac"))
        assertFalse(url.contains("/a/song"))

        get(url).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("10000", resp.header("Content-Length"))
            assertEquals("bytes", resp.header("Accept-Ranges"))
            assertEquals("audio/flac", resp.header("Content-Type"))
            assertArrayEquals(data, resp.body!!.bytes())
        }
        get(url, "bytes=100-199").use { resp ->
            assertEquals(206, resp.code)
            assertEquals("bytes 100-199/10000", resp.header("Content-Range"))
            assertEquals("100", resp.header("Content-Length"))
            assertArrayEquals(data.copyOfRange(100, 200), resp.body!!.bytes())
        }
        get(url, "bytes=9990-").use { resp -> assertEquals(206, resp.code); assertEquals(10, resp.body!!.bytes().size) }
        get(url, "bytes=-16").use { resp -> assertEquals("bytes 9984-9999/10000", resp.header("Content-Range")); assertArrayEquals(data.copyOfRange(9984, 10000), resp.body!!.bytes()) }
        get(url, "bytes=9000-20000").use { resp -> assertEquals("bytes 9000-9999/10000", resp.header("Content-Range")) }
        get(url, "bytes=10000-").use { resp ->
            assertEquals(416, resp.code)
            assertEquals("bytes */10000", resp.header("Content-Range"))
        }
        get(url, head = true).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("10000", resp.header("Content-Length"))
            assertEquals(0, resp.body!!.bytes().size)
        }
        get(url, "bytes=0-9", head = true).use { resp -> assertEquals(206, resp.code); assertEquals("10", resp.header("Content-Length")) }
    }

    @Test fun tokensAreRequiredAndStopEndsAccess() {
        val mem = InMemoryFileSystem("m").apply { put("/x.mp3", ByteArray(100)); put("/secret.mp3", ByteArray(5)) }
        val r = start(mapOf("m" to mem))
        val path = r.register(SourceRef("m", "/x.mp3"))
        val base = "http://127.0.0.1:${r.port}"
        get(base + path).use { assertEquals(200, it.code) }
        get("$base/audio/not-a-token/track.mp3").use { assertEquals(404, it.code) }
        get("$base/audio/../secret.mp3").use { assertEquals(404, it.code) }
        get("$base/m/secret.mp3").use { assertEquals(404, it.code) }
        r.revokeAll()
        get(base + path).use { assertEquals(404, it.code) }
        val path2 = r.register(SourceRef("m", "/x.mp3"))
        r.stop()
        try {
            get(base + path2).close()
            fail("relay still reachable after stop")
        } catch (e: java.io.IOException) {
        }
    }

    @Test fun relaysLocalSmbFtpAndWebDavWithoutExposingCredentials() {
        ProtocolFixture.require("fp.smb.host", "fp.ftp.host", "fp.webdav.url")
        val local = ProtocolFixture.local(ProtocolFixture.TRICKY).readBytes()
        val smbCfg = SourceConfig(id = "smb", type = SourceType.SMB, host = ProtocolFixture.prop("fp.smb.host")!!,
            port = ProtocolFixture.prop("fp.smb.port")!!.toInt(), share = "music", username = "alice")
        val ftpCfg = SourceConfig(id = "ftp", type = SourceType.FTP, host = ProtocolFixture.prop("fp.ftp.host")!!, port = 2121, username = "alice")
        val davCfg = SourceConfig(id = "dav", type = SourceType.WEBDAV, url = ProtocolFixture.prop("fp.webdav.url")!!, username = "alice")
        val store = InMemoryCredentialStore().apply { put("smb", "alicepass"); put("ftp", "alicepass"); put("dav", "pa:ss/1") }
        val localCfg = SourceConfig(id = "local", type = SourceType.LOCAL, url = ProtocolFixture.fixtureDir.path)
        val sources = mapOf("smb" to SmbFileSystem(smbCfg, store), "ftp" to FtpFileSystem(ftpCfg, store),
            "dav" to WebDavFileSystem(davCfg, store), "local" to LocalFileSystem(localCfg))
        val r = start(sources)
        for (id in sources.keys) {
            val url = "http://127.0.0.1:${r.port}" + r.register(SourceRef(id, ProtocolFixture.TRICKY))
            assertFalse(url.contains("alice") || url.contains("pass"))
            get(url, "bytes=1000-1999").use { resp ->
                assertEquals(id, 206, resp.code)
                assertArrayEquals(id, local.copyOfRange(1000, 2000), resp.body!!.bytes())
                assertTrue(resp.headers.none { (_, v) -> v.contains("pass") || v.contains("alice") })
            }
            get(url).use { resp -> assertArrayEquals(id, local, resp.body!!.bytes()) }
        }
        sources.values.forEach { it.close() }
    }

    @Test fun rangeParser() {
        assertEquals(RangeResult.Full, HttpRange.evaluate(null, 10))
        assertEquals(RangeResult.Full, HttpRange.evaluate("bytes=0-1,4-5", 10))
        assertEquals(RangeResult.Full, HttpRange.evaluate("items=0-1", 10))
        assertEquals(RangeResult.Partial(2, 9), HttpRange.evaluate("bytes=2-", 10))
        assertEquals(RangeResult.Partial(7, 9), HttpRange.evaluate("bytes=-3", 10))
        assertEquals(RangeResult.Partial(0, 9), HttpRange.evaluate("bytes=-30", 10))
        assertEquals(RangeResult.Unsatisfiable, HttpRange.evaluate("bytes=10-12", 10))
        assertEquals(RangeResult.Unsatisfiable, HttpRange.evaluate("bytes=-0", 10))
        assertEquals(RangeResult.Full, HttpRange.evaluate("bytes=5-2", 10))
    }
}
