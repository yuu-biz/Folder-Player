package com.wing.folderplayer.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** A folder listing the user walked away from (the browser cancels it) ends as cancellation, never as an error to show. */
class SourceCallTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() = server.shutdown()

    @Test fun aWebDavListingCancelledWhileTheAnswerArrivesEndsAsCancellation() = runBlocking {
        val entries = (1..30).joinToString("") { "<d:response><d:href>/dav/song-$it.flac</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>" }
        // The answer trickles in for about 3 s: the read goes on after the interrupt of the cancelled call.
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">$entries</d:multistatus>""")
            .throttleBody(128, 100, TimeUnit.MILLISECONDS))
        val dav = WebDavFileSystem(SourceConfig(id = "dav", name = "dav", type = SourceType.WEBDAV, url = server.url("/dav/").toString()), InMemoryCredentialStore())
        var failure: Exception? = null
        val listing = launch(Dispatchers.Default) {
            try {
                runSourceCall { dav.list("/") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        assertNotNull("the listing reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        delay(300)
        listing.cancelAndJoin()
        assertNull("error of the cancelled listing: $failure", failure)
    }
}
