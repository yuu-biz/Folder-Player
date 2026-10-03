package com.wing.folderplayer.data.source

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * WebDAV over a local HTTP server: href decoding (single decode, '+' literal), Range reads, per-source credentials
 * (two sources on the same host/path with different users), auth failure mapping and redirect credential isolation.
 */
class WebDavFileSystemTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val audio = ByteArray(10_000) { (it % 256).toByte() }
    private val seenAuth = mutableListOf<String?>()

    @Before fun setUp() {
        other = MockWebServer().apply {
            // A redirect target that also asks for credentials: it must never receive this source's password.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\"")
            }
            start()
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                synchronized(seenAuth) { seenAuth.add(auth) }
                val user = when (auth) {
                    okhttp3.Credentials.basic("alice", "pa:ss/1") -> "alice"
                    okhttp3.Credentials.basic("bob", "b0b") -> "bob"
                    else -> null
                }
                if (user == null) return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"dav\"")
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PROPFIND" && path == "/dav/Music/" -> MockResponse().setResponseCode(207).setBody(
                        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/dav/Music/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                        <d:response><d:href>/dav/Music/Album-A/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
                          <d:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
                        <d:response><d:href>http://${request.requestUrl!!.host}:${request.requestUrl!!.port}/dav/Music/01%20%E6%9B%B2%20%231%2B%25.flac</d:href><d:propstat><d:prop><d:resourcetype/>
                          <d:getcontentlength>10000</d:getcontentlength></d:prop></d:propstat></d:response>
                        <d:response><d:href>/dav/Music/only-$user.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1</d:getcontentlength></d:prop></d:propstat></d:response>
                        </d:multistatus>""")
                    request.method == "GET" && request.requestUrl!!.pathSegments == listOf("dav", "Music", "01 曲 #1+%.flac") -> {
                        val range = request.getHeader("Range")
                        if (range == null) MockResponse().setBody(okio.Buffer().write(audio))
                        else {
                            val (s, e) = range.removePrefix("bytes=").split("-").let { it[0].toInt() to (it[1].ifEmpty { "${audio.size - 1}" }).toInt() }
                            if (s >= audio.size) MockResponse().setResponseCode(416)
                            else MockResponse().setResponseCode(206).setBody(okio.Buffer().write(audio.copyOfRange(s, minOf(e, audio.size - 1) + 1)))
                                .addHeader("Content-Range", "bytes $s-$e/${audio.size}")
                        }
                    }
                    request.method == "GET" && path == "/dav/Music/redirect.flac" ->
                        MockResponse().setResponseCode(302).addHeader("Location", other.url("/elsewhere.flac").toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown(); other.shutdown() }

    private fun fs(id: String, user: String, pass: String): WebDavFileSystem {
        val store = InMemoryCredentialStore()
        val cfg = SourceConfig(id = id, name = id, type = SourceType.WEBDAV, url = server.url("/dav").toString(), path = "/Music", username = user)
        store.put(cfg.effectiveCredentialRef, pass)
        return WebDavFileSystem(cfg, store)
    }

    @Test fun listsWithSingleDecodeAndRangeReads() {
        val a = fs("A", "alice", "pa:ss/1")
        val entries = a.list("/")
        val names = entries.map { it.name }.toSet()
        assertEquals(setOf("Album-A", "01 曲 #1+%.flac", "only-alice.txt"), names)
        val song = entries.first { it.name.endsWith(".flac") }
        assertEquals("/01 曲 #1+%.flac", song.path)
        assertEquals(10_000L, song.size)
        assertTrue(entries.first { it.name == "Album-A" }.isDirectory)

        a.openRead(song.path).use { assertEquals(audio.toList(), it.readBytes().toList()) }
        a.openRead(song.path, 9_000).use { assertEquals(audio.copyOfRange(9000, 10000).toList(), it.readBytes().toList()) }
        a.openRead(song.path, 100, 50).use { s -> assertEquals(50L, s.length); assertEquals(audio.copyOfRange(100, 150).toList(), s.readBytes().toList()) }
        try { a.openRead(song.path, 20_000); fail("416 not reported") } catch (e: java.io.EOFException) {}
    }

    @Test fun twoSourcesOnSameHostKeepTheirOwnCredentials() {
        val a = fs("A", "alice", "pa:ss/1")
        val b = fs("B", "bob", "b0b")
        repeat(3) {
            assertTrue(a.list("/").any { it.name == "only-alice.txt" })
            assertTrue(b.list("/").any { it.name == "only-bob.txt" })
        }
        val parallel = (1..8).map { i -> Thread { val f = if (i % 2 == 0) a else b; f.list("/") }.apply { start() } }
        parallel.forEach { it.join() }
        assertFalse(seenAuth.filterNotNull().any { it.contains("pa:ss") }) // never sent in clear form
    }

    @Test fun wrongPasswordIsAuthFailureAndTestDistinguishes() {
        val bad = fs("X", "alice", "nope")
        try { bad.list("/"); fail() } catch (e: SourceException.AuthFailed) {}
        assertEquals(ConnectionOutcome.AUTH_FAILED, bad.testConnection().outcome)
        assertEquals(ConnectionOutcome.OK, fs("A", "alice", "pa:ss/1").testConnection().outcome)
        assertNull(fs("A", "alice", "pa:ss/1").stat("/missing.flac"))
    }

    @Test fun credentialsAreNotSentToRedirectTargets() {
        val a = fs("A", "alice", "pa:ss/1")
        seenAuth.clear()
        runCatching { a.openRead("/redirect.flac").close() }
        val toOther = (0 until other.requestCount).map { other.takeRequest() }
        assertEquals(1, toOther.size) // challenged once, not answered with credentials
        assertTrue(toOther.all { it.getHeader("Authorization") == null })
    }

    @Test fun hrefOutsideRootIsIgnored() {
        val a = fs("A", "alice", "pa:ss/1")
        assertNull(a.hrefToPath("/other/x"))
        assertNull(a.hrefToPath("/dav/Music/%2E%2E/x"))
        assertEquals("/a+b c", a.hrefToPath("/dav/Music/a+b%20c"))
    }
}
