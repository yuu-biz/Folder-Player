package com.wing.folderplayer.data.source

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Two WebDAV sources on the same origin with different accounts against a server that authenticates by session
 * cookie once the Basic login succeeded (common for NAS/Alist front ends). Each source must keep its own cookies,
 * otherwise the second account silently acts with the first account's session.
 */
class WebDavCookieIsolationTest {
    private lateinit var server: MockWebServer
    private val cookiesSeen = mutableListOf<Pair<String?, String?>>() // (Cookie, Authorization)

    private fun listing(user: String) = MockResponse().setResponseCode(207).setBody(
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        <d:response><d:href>/dav/only-$user.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>""")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cookie = request.getHeader("Cookie")
                val auth = request.getHeader("Authorization")
                synchronized(cookiesSeen) { cookiesSeen.add(cookie to auth) }
                // A valid session cookie wins over everything else.
                Regex("session=(\\w+)").find(cookie ?: "")?.let { return listing(it.groupValues[1]) }
                val user = when (auth) {
                    okhttp3.Credentials.basic("alice", "a1") -> "alice"
                    okhttp3.Credentials.basic("bob", "b2") -> "bob"
                    else -> return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"dav\"")
                }
                return listing(user).addHeader("Set-Cookie", "session=$user; Path=/; HttpOnly")
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun sameOriginAccountsDoNotShareSessionCookies() {
        val url = server.url("/dav/").toString()
        val store = InMemoryCredentialStore().apply { put("a", "a1"); put("b", "b2") }
        // Both use the default (shared) transport, exactly like SourceRegistry does.
        val alice = WebDavFileSystem(SourceConfig(id = "a", type = SourceType.WEBDAV, url = url, username = "alice"), store)
        val bob = WebDavFileSystem(SourceConfig(id = "b", type = SourceType.WEBDAV, url = url, username = "bob"), store)

        assertEquals(listOf("only-alice.txt"), alice.list("/").map { it.name })
        assertEquals("bob must see his own files, not alice's session", listOf("only-bob.txt"), bob.list("/").map { it.name })
        assertEquals(listOf("only-alice.txt"), alice.list("/").map { it.name })
        assertEquals(listOf("only-bob.txt"), bob.list("/").map { it.name })
        // Within one source the session cookie is still kept and sent (redirect/cookie flows keep working).
        org.junit.Assert.assertTrue(cookiesSeen.toString(), cookiesSeen.any { it.first == "session=alice" })
        org.junit.Assert.assertTrue(cookiesSeen.toString(), cookiesSeen.any { it.first == "session=bob" })
    }
}
