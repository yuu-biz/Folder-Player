package com.wing.folderplayer.cast

import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.testutil.InMemoryFileSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Ordering of cast / stop: a request that finishes or fails late must neither tear down the session that replaced
 * it nor bring back a session that was stopped meanwhile. The renderer is a fake whose calls can be held open; the
 * relay is the real one on a free port.
 */
class CastSessionOrderTest {
    /** Records calls; a call whose key ("setUri:<udn>", "play:<udn>", "transport:<udn>" …) has a gate blocks until the gate completes. */
    private class FakeRenderer : RendererControl {
        override var onChange: (() -> Unit)? = null
        val calls = CopyOnWriteArrayList<String>()
        val urls = ConcurrentHashMap<String, String>()
        private val gates = ConcurrentHashMap<String, CompletableFuture<Unit>>()
        private val reached = ConcurrentHashMap<String, CountDownLatch>()

        fun hold(key: String): CompletableFuture<Unit> = CompletableFuture<Unit>().also { gates[key] = it }
        fun awaitReached(key: String) =
            assertTrue("$key reached", reached.computeIfAbsent(key) { CountDownLatch(1) }.await(10, TimeUnit.SECONDS))

        private fun call(key: String) {
            calls += key
            reached.computeIfAbsent(key) { CountDownLatch(1) }.countDown()
            val gate = gates[key] ?: return
            try { gate.get(20, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause!! }
        }

        override fun start() = Unit
        override fun search() = Unit
        override fun renderers(): List<Renderer> = emptyList()
        override fun setUri(udn: String, url: String, didl: String) { urls[udn] = url; call("setUri:$udn") }
        override fun play(udn: String) = call("play:$udn")
        override fun pause(udn: String) = call("pause:$udn")
        override fun stop(udn: String) = call("stop:$udn")
        override fun seek(udn: String, positionMs: Long) = call("seek:$udn")
        override fun transportState(udn: String): String { call("transport:$udn"); return "PLAYING" }
        override fun progress(udn: String): Pair<Long, Long> = 1_000L to 60_000L
        override fun shutdown() = Unit
    }

    private class FakeLocks : CastLocks {
        @Volatile var sessionHeld = false
        override fun acquireDiscovery() = Unit
        override fun releaseDiscovery() = Unit
        override fun acquireSession() { sessionHeld = true }
        override fun releaseSession() { sessionHeld = false }
    }

    private val renderer = FakeRenderer()
    private val locks = FakeLocks()
    private val fs = InMemoryFileSystem("m").apply { put("/a.flac", ByteArray(4_000) { it.toByte() }); put("/b.flac", ByteArray(5_000)) }
    private val cast = CastController({ true }, renderer, { fs }, locks, relayPort = 0, pollIntervalMs = 20)
    private val a = Renderer("uuid:A", "A", "", "127.0.0.1")
    private val b = Renderer("uuid:B", "B", "", "127.0.0.1")

    @After fun tearDown() { cast.relay.stop() }

    private fun awaitState(what: String, predicate: (CastState) -> Boolean): CastState = runBlocking {
        try { withTimeout(10_000) { cast.state.first(predicate) } } catch (e: Exception) { throw AssertionError("$what; state ${cast.state.value}", e) }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > end) throw AssertionError("$what; state ${cast.state.value} calls ${renderer.calls}")
            Thread.sleep(10)
        }
    }

    private fun httpStatus(url: String): Int = (URL(url).openConnection() as HttpURLConnection).run {
        try { requestMethod = "HEAD"; connectTimeout = 5_000; readTimeout = 5_000; responseCode } finally { disconnect() }
    }

    @Test fun lateFailureOfAnOlderCastKeepsTheNewerSession() {
        val gateA = renderer.hold("setUri:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        renderer.awaitReached("setUri:uuid:A")
        val urlA = renderer.urls.getValue(a.udn)

        cast.cast(b, SourceRef("m", "/b.flac"), "b", null)
        // A fails only now, after B was requested.
        gateA.completeExceptionally(IOException("renderer A went away"))

        awaitState("B playing") { it.active == b && it.lastCommand == "Play" }
        awaitState("B polled") { it.rendererState == "PLAYING" }
        Thread.sleep(200) // several poll rounds: nothing from A arrives later
        val s = cast.state.value
        assertEquals(b, s.active)
        assertNull("A's stale failure is not reported over B", s.error)
        assertTrue("relay still running", cast.relay.isRunning)
        assertTrue("session locks still held", locks.sessionHeld)
        assertEquals("B's track is served", 200, httpStatus(renderer.urls.getValue(b.udn)))
        assertEquals("A's token is gone", 404, httpStatus(urlA))
        assertFalse("B was never stopped", renderer.calls.contains("stop:uuid:B"))
        assertFalse("A never got Play", renderer.calls.contains("play:uuid:A"))
    }

    @Test fun lateSuccessOfAnOlderCastIsUndoneAndKeepsTheNewerSession() {
        val gateA = renderer.hold("setUri:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        renderer.awaitReached("setUri:uuid:A")
        cast.cast(b, SourceRef("m", "/b.flac"), "b", null)
        gateA.complete(Unit)

        awaitState("B playing") { it.active == b && it.lastCommand == "Play" }
        Thread.sleep(200)
        assertEquals(b, cast.state.value.active)
        assertTrue("A's renderer was told to stop", renderer.calls.contains("stop:uuid:A"))
        assertFalse("A never got Play", renderer.calls.contains("play:uuid:A"))
        assertTrue(cast.relay.isRunning)
        assertTrue(locks.sessionHeld)
        assertEquals(200, httpStatus(renderer.urls.getValue(b.udn)))
    }

    @Test fun connectingIsReportedUntilTheCastHasStartedFailedOrStopped() {
        // Started: connecting from the request until the session is up.
        val gateA = renderer.hold("setUri:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        assertTrue("connecting at once", cast.state.value.connecting && cast.state.value.sessionInProgress)
        renderer.awaitReached("setUri:uuid:A")
        assertTrue(cast.state.value.connecting)
        gateA.complete(Unit)
        awaitState("A playing, no longer connecting") { it.active == a && it.lastCommand == "Play" && !it.connecting }
        assertTrue(cast.state.value.sessionInProgress)
        cast.stop()
        awaitState("stopped") { it.active == null && it.lastCommand == "Stop" }
        assertFalse(cast.state.value.sessionInProgress)

        // Failed: no longer connecting, no session.
        renderer.hold("setUri:uuid:B").completeExceptionally(IOException("renderer B went away"))
        cast.cast(b, SourceRef("m", "/b.flac"), "b", null)
        awaitState("B failed") { it.error != null && !it.connecting }
        assertFalse(cast.state.value.sessionInProgress)

        // Stopped while connecting.
        val gateA2 = renderer.hold("setUri:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        assertTrue(cast.state.value.connecting)
        cast.stop()
        gateA2.complete(Unit)
        awaitState("stop ends connecting") { !it.connecting && it.active == null && it.lastCommand == "Stop" }
    }

    @Test fun stopWhileConnectingDoesNotComeBackLater() {
        val gateA = renderer.hold("setUri:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        renderer.awaitReached("setUri:uuid:A")
        val urlA = renderer.urls.getValue(a.udn)
        cast.stop()
        // The renderer accepts the URI only after the user pressed stop.
        gateA.complete(Unit)

        awaitTrue("session ended") { cast.state.value.lastCommand == "Stop" && !cast.relay.isRunning && !locks.sessionHeld }
        Thread.sleep(300) // a resurrected session would have started polling / playing by now
        val s = cast.state.value
        assertNull(s.active)
        assertNull(s.rendererState)
        assertEquals("Stop", s.lastCommand)
        assertFalse("relay stopped", cast.relay.isRunning)
        assertFalse("session locks released", locks.sessionHeld)
        assertFalse("no Play after stop", renderer.calls.contains("play:uuid:A"))
        assertFalse("no polling after stop", renderer.calls.contains("transport:uuid:A"))
        assertTrue("the renderer that received the URI was stopped", renderer.calls.contains("stop:uuid:A"))
        assertTrue("A's URL is no longer served", runCatching { httpStatus(urlA) }.getOrDefault(-1) != 200)
    }

    @Test fun stopWhileStartingPlaybackDoesNotComeBackLater() {
        val gatePlay = renderer.hold("play:uuid:A")
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        renderer.awaitReached("play:uuid:A")
        cast.stop()
        gatePlay.complete(Unit)

        awaitTrue("session ended") { cast.state.value.lastCommand == "Stop" && !cast.relay.isRunning && !locks.sessionHeld }
        Thread.sleep(300)
        assertNull(cast.state.value.active)
        assertFalse(cast.relay.isRunning)
        assertFalse(locks.sessionHeld)
        assertFalse("no polling after stop", renderer.calls.contains("transport:uuid:A"))
    }

    @Test fun rendererAnswerArrivingAfterStopDoesNotReviveTheState() {
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        awaitState("A polled") { it.active == a && it.rendererState == "PLAYING" }
        val gatePoll = renderer.hold("transport:uuid:A")
        val before = renderer.calls.count { it == "transport:uuid:A" }
        awaitTrue("poll blocked in GetTransportInfo") { renderer.calls.count { it == "transport:uuid:A" } > before }
        cast.stop()
        awaitTrue("session ended") { cast.state.value.lastCommand == "Stop" && !cast.relay.isRunning }
        gatePoll.complete(Unit) // the renderer answers PLAYING after the stop
        Thread.sleep(200)
        val s = cast.state.value
        assertNull(s.active)
        assertNull("no renderer state written after stop", s.rendererState)
        assertEquals(-1L, s.positionMs)
        assertFalse(locks.sessionHeld)
    }

    @Test fun failedCurrentCastEndsItsSession() {
        renderer.hold("setUri:uuid:A").completeExceptionally(IOException("no such renderer"))
        cast.cast(a, SourceRef("m", "/a.flac"), "a", null)
        val s = awaitState("failure reported") { it.error != null }
        assertEquals("no such renderer", s.error)
        awaitTrue("relay and locks released") { !cast.relay.isRunning && !locks.sessionHeld }
        assertNull(cast.state.value.active)
    }
}
