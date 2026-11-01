package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.SmbFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One SMB connection serves the browser, the player and the scans at once. Through [Relay] (a TCP relay in front of
 * the Samba fixture that can delay answers or go silent on the open connections) these tests time what one caller's
 * trouble costs the others: a cancelled listing or read (the interrupt of a coroutine or of the player's loader) and a
 * connection that stopped answering while the app was idle (Wi-Fi power save, a phone that slept). Prints the times
 * for VERIFICATION.md.
 */
class SmbConnectionTest {
    private lateinit var relay: Relay

    @Before fun setUp() {
        ProtocolFixture.require("fp.smb.host")
        relay = Relay(ProtocolFixture.prop("fp.smb.host")!!, ProtocolFixture.prop("fp.smb.port")?.toInt() ?: 445)
    }

    @After fun tearDown() {
        if (::relay.isInitialized) relay.close()
    }

    private fun fs(): SmbFileSystem {
        val cfg = SourceConfig(
            id = "smb-relay", name = "smb-relay", type = SourceType.SMB, host = "127.0.0.1", port = relay.port,
            share = "music", username = "alice",
        )
        return SmbFileSystem(cfg, InMemoryCredentialStore().apply { put(cfg.effectiveCredentialRef, "alicepass") })
    }

    private class Timed(val millis: Long, val error: Throwable?)

    private fun timed(what: String, block: () -> Unit): Timed {
        val start = System.nanoTime()
        val error = runCatching(block).exceptionOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("SMBCONN $what: $ms ms -> ${error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok"}")
        return Timed(ms, error)
    }

    /** Runs [cancelled] on a thread that is interrupted while its request is on the wire, as `runInterruptible` does. */
    private fun interruptMidRequest(cancelled: () -> Unit) {
        val t = thread { runCatching(cancelled) }
        Thread.sleep(150)
        t.interrupt()
        t.join(60_000)
    }

    @Test fun aCancelledListingLeavesTheConnectionToTheOthers() {
        fs().use { smb ->
            smb.list("/fixture/Album-A")
            relay.answerDelayMillis = 400
            var other: Timed? = null
            val browse = thread { other = timed("listing while another one is cancelled") { smb.list("/fixture/Album-A") } }
            Thread.sleep(50)
            interruptMidRequest { smb.list("/fixture") }
            browse.join(120_000)
            relay.answerDelayMillis = 0
            println("SMBCONN connections: ${relay.accepted.get()}")
            assertNull("the other listing succeeds: ${other!!.error}", other!!.error)
            assertTrue("the other listing is not held up by the cancelled one: ${other!!.millis} ms", other!!.millis < 10_000)
            assertEquals("the cancelled listing does not close the shared connection", 1, relay.accepted.get())
        }
    }

    @Test fun aCancelledReadLeavesTheConnectionToTheBrowser() {
        fs().use { smb ->
            smb.list("/fixture/Album-A")
            val reader = smb.openRandomAccess(ProtocolFixture.TRICKY)
            relay.answerDelayMillis = 400
            var browse: Timed? = null
            val browser = thread { browse = timed("listing while a read is cancelled") { smb.list("/fixture/Album-A") } }
            Thread.sleep(50)
            interruptMidRequest { reader.read(0, ByteArray(64 * 1024), 0, 64 * 1024) }
            browser.join(120_000)
            relay.answerDelayMillis = 0
            runCatching { reader.close() }
            println("SMBCONN connections: ${relay.accepted.get()}")
            assertNull("the listing succeeds: ${browse!!.error}", browse!!.error)
            assertTrue("the listing is not held up by the cancelled read: ${browse!!.millis} ms", browse!!.millis < 10_000)
            assertEquals("the cancelled read does not close the shared connection", 1, relay.accepted.get())
        }
    }

    @Test fun aConnectionThatWentSilentWhileIdleIsReplacedQuickly() {
        fs().use { smb ->
            smb.list("/fixture/Album-A")
            Thread.sleep(12_000)
            relay.silenceOpenConnections()
            val after = timed("first listing after the idle connection went silent") { smb.list("/fixture/Album-A") }
            println("SMBCONN connections: ${relay.accepted.get()}")
            assertNull("the listing succeeds on a new connection: ${after.error}", after.error)
            assertTrue("a silent idle connection is noticed within seconds: ${after.millis} ms", after.millis < 10_000)
        }
    }

    @Test fun aHealthyIdleConnectionIsKept() {
        fs().use { smb ->
            smb.list("/fixture/Album-A")
            Thread.sleep(12_000)
            val after = timed("first listing after 12 s unused, server answering") { smb.list("/fixture/Album-A") }
            println("SMBCONN connections: ${relay.accepted.get()}")
            assertNull("the listing succeeds: ${after.error}", after.error)
            assertEquals("the connection answered the echo and is kept", 1, relay.accepted.get())
            assertTrue("no wait for an echo timeout: ${after.millis} ms", after.millis < 2_000)
        }
    }

    /**
     * TCP relay to the SMB server. [answerDelayMillis] holds each chunk from the server back; [silenceOpenConnections]
     * makes the relayed connections drop everything in both directions while their sockets stay open (no FIN, no RST),
     * and new connections are relayed normally.
     */
    private class Relay(private val host: String, private val targetPort: Int) : Closeable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val port: Int get() = server.localPort
        val accepted = AtomicInteger()
        @Volatile var answerDelayMillis = 0L
        private val links = CopyOnWriteArrayList<Link>()

        private inner class Link(val client: Socket, val target: Socket) {
            @Volatile var silent = false
            fun pump(from: InputStream, to: OutputStream, delayed: Boolean) = thread(isDaemon = true) {
                val buf = ByteArray(64 * 1024)
                runCatching {
                    while (true) {
                        val n = from.read(buf)
                        if (n < 0) break
                        if (delayed && answerDelayMillis > 0) Thread.sleep(answerDelayMillis)
                        if (!silent) { to.write(buf, 0, n); to.flush() }
                    }
                }
                runCatching { client.close() }
                runCatching { target.close() }
            }
        }

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    val target = Socket(host, targetPort)
                    val link = Link(client, target)
                    links += link
                    link.pump(client.getInputStream(), target.getOutputStream(), delayed = false)
                    link.pump(target.getInputStream(), client.getOutputStream(), delayed = true)
                }
            }
        }

        fun silenceOpenConnections() = links.forEach { it.silent = true }

        override fun close() {
            runCatching { server.close() }
            links.forEach { runCatching { it.client.close() }; runCatching { it.target.close() } }
        }
    }
}
