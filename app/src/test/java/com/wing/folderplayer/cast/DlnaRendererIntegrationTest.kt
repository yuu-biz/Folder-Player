package com.wing.folderplayer.cast

import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.SmbFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.WebDavFileSystem
import com.wing.folderplayer.protocol.ProtocolFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * DLNA against a real UPnP MediaRenderer (gmrender-resurrect, GStreamer decoding) on the fixture Docker network:
 * SSDP discovery with jUPnP, SetAVTransportURI / Play / Pause / Seek / Stop, renderer state polling, and audio fetched
 * through the token relay from SMB (MP3) and WebDAV (FLAC). Runs via scripts/fixtures/run-in-network-tests.sh.
 */
class DlnaRendererIntegrationTest {
    private val cp = DlnaControlPoint()
    private lateinit var relay: RelayServer
    private lateinit var sources: Map<String, SourceFileSystem>

    @Before fun setUp() {
        ProtocolFixture.require("fp.dlna.renderer", "fp.net.smb.host", "fp.net.webdav.url")
        val store = InMemoryCredentialStore().apply { put("smb", "alicepass"); put("dav", "pa:ss/1") }
        sources = mapOf(
            "smb" to SmbFileSystem(SourceConfig(id = "smb", type = SourceType.SMB, host = ProtocolFixture.prop("fp.net.smb.host")!!, share = "music", username = "alice"), store),
            "dav" to WebDavFileSystem(SourceConfig(id = "dav", type = SourceType.WEBDAV, url = ProtocolFixture.prop("fp.net.webdav.url")!!, username = "alice"), store),
        )
        relay = RelayServer({ ref -> sources.getValue(ref.sourceId) }, preferredPort = RelayServer.DEFAULT_PORT)
    }

    @After fun tearDown() {
        // setUp may have been skipped (no renderer configured): only release what was created.
        if (::relay.isInitialized) relay.stop()
        cp.shutdown()
        if (::sources.isInitialized) sources.values.forEach { it.close() }
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(250)
        }
        fail("timed out waiting for $what")
    }

    private fun localAddressFor(host: String): String = DatagramSocket().use { s ->
        s.connect(InetSocketAddress(InetAddress.getByName(host), 1900)); s.localAddress.hostAddress!!
    }

    @Test fun discoverCastAndControlMp3AndFlac() {
        val name = ProtocolFixture.prop("fp.dlna.renderer")!!
        cp.start()
        waitFor(20_000, "renderer '$name'") { cp.search(); cp.renderers().any { it.name == name } }
        val renderer = cp.renderers().first { it.name == name }
        println("found renderer: $renderer")
        val port = relay.start()
        val base = "http://${localAddressFor(renderer.address)}:$port"

        for ((ref, mime) in listOf(
            SourceRef("smb", "/fixture/Album-A/02 track.mp3") to "audio/mpeg",
            SourceRef("dav", "/fixture/Album-A/01 曲 #1+%.flac") to "audio/flac",
        )) {
            val before = relay.servedRequests.get()
            val url = base + relay.register(ref)
            val size = sources.getValue(ref.sourceId).stat(ref.path)!!.size
            cp.setUri(renderer.udn, url, DlnaControlPoint.didl(ref.name, "Fixture Artist", url, mime, size))
            cp.play(renderer.udn)
            waitFor(15_000, "PLAYING ${ref.name}") { cp.transport(renderer.udn).currentTransportState.value == "PLAYING" }
            waitFor(10_000, "renderer fetching through the relay") { relay.servedRequests.get() > before }
            waitFor(10_000, "position advancing") { DlnaControlPoint.parseTime(cp.position(renderer.udn).relTime) >= 2_000 }
            val info = cp.position(renderer.udn)
            println("${ref.name}: pos=${info.relTime} dur=${info.trackDuration} requests=${relay.servedRequests.get() - before}")
            assertTrue(DlnaControlPoint.parseTime(info.trackDuration) > 10_000)

            cp.pause(renderer.udn)
            waitFor(5_000, "PAUSED") { cp.transport(renderer.udn).currentTransportState.value == "PAUSED_PLAYBACK" }
            cp.seek(renderer.udn, 15_000)
            cp.play(renderer.udn)
            waitFor(10_000, "position after seek") {
                cp.transport(renderer.udn).currentTransportState.value == "PLAYING" &&
                    DlnaControlPoint.parseTime(cp.position(renderer.udn).relTime) >= 15_000
            }
            cp.stop(renderer.udn)
            waitFor(5_000, "STOPPED") { cp.transport(renderer.udn).currentTransportState.value == "STOPPED" }
        }
        // Ending the session revokes every token and closes the relay.
        val lastUrl = base + relay.register(SourceRef("smb", "/fixture/Album-B/track.flac"))
        relay.stop()
        val conn = runCatching { java.net.URL(lastUrl).openConnection().apply { connectTimeout = 2000 }.getInputStream().close() }
        assertTrue("relay reachable after stop", conn.isFailure)
        assertNotNull(renderer.udn)
        assertEquals(name, renderer.name)
    }
}
