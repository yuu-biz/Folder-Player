package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.source.InMemoryCredentialStore
import com.wing.folderplayer.data.source.SmbFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceType
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A server that stops answering in the middle of a song (the container is paused, the TCP connection stays open):
 * how long a read and the next opens take to fail, and that the source works again once the server answers. A read
 * that timed out drops the connection (1.8.0): before, the next open first waited on the dead connection as well
 * (60 s instead of 30 s for every retry of the player). Needs `-Dfp.smb.pause=<container>` besides the SMB fixture;
 * prints the times for VERIFICATION.md.
 */
class SmbStallTest {
    private lateinit var container: String

    @Before fun setUp() {
        ProtocolFixture.require("fp.smb.host", "fp.smb.pause")
        container = ProtocolFixture.prop("fp.smb.pause")!!
    }

    @After fun tearDown() {
        if (::container.isInitialized) docker("unpause")
    }

    private fun docker(command: String) {
        ProcessBuilder("docker", command, container).redirectErrorStream(true).start().apply { waitFor(30, TimeUnit.SECONDS) }
    }

    private fun fs(): SmbFileSystem {
        val cfg = SourceConfig(
            id = "smb-stall", name = "smb-stall", type = SourceType.SMB, host = ProtocolFixture.prop("fp.smb.host")!!,
            port = ProtocolFixture.prop("fp.smb.port")?.toInt() ?: 445, share = "music", username = "alice",
        )
        return SmbFileSystem(cfg, InMemoryCredentialStore().apply { put(cfg.effectiveCredentialRef, "alicepass") })
    }

    private fun <T> timed(what: String, block: () -> T): Long {
        val start = System.nanoTime()
        val result = runCatching(block)
        val seconds = (System.nanoTime() - start) / 1_000_000
        println("STALL $what: ${seconds} ms -> ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok"}")
        return seconds
    }

    @Test fun aServerThatStopsAnsweringFailsTheReadAndTheNextOpens() {
        fs().use { smb ->
            val reader = smb.openRandomAccess(ProtocolFixture.TRICKY)
            val buf = ByteArray(1000)
            reader.read(0, buf, 0, buf.size)
            docker("pause")
            val read = timed("read while paused") { reader.read(2000, buf, 0, buf.size) }
            runCatching { reader.close() }
            val first = timed("first open while paused") { smb.openRead(ProtocolFixture.TRICKY).close() }
            timed("second open while paused") { smb.openRead(ProtocolFixture.TRICKY).close() }
            docker("unpause")
            timed("open after unpause") { smb.openRead(ProtocolFixture.TRICKY).close() }
            assertTrue("the read fails after one timeout, the dead connection is not closed politely: $read ms", read < 45_000)
            assertTrue("the first open after the failed read waits on one connection timeout, not two: $first ms", first < 45_000)
            smb.openRead(ProtocolFixture.TRICKY).close()
        }
    }
}
