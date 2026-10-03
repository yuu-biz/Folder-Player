package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.wing.folderplayer.data.source.ConnectionOutcome
import com.wing.folderplayer.data.source.KeystoreCredentialStore
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Source registry on the device against the Docker fixture servers (hosts passed as -e smb_host etc.). */
@RunWith(AndroidJUnit4::class)
class SourceRegistryTest {
    @Before fun setUp() {
        SourceRegistry.init(Fx.ctx)
        SourceRegistry.savedSources().forEach { SourceRegistry.remove(it.id) }
    }

    private fun smb(name: String, user: String, share: String = "music", root: String? = null) =
        SourceConfig(name = name, type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = share, path = root, username = user)

    private fun ftp(name: String, tls: Boolean = false) =
        SourceConfig(name = name, type = SourceType.FTP, host = Fx.arg("ftp_host")!!, port = if (tls) 2124 else 2121, username = "alice",
            useTls = tls, tlsPinnedSha256 = if (tls) Fx.arg("ftps_pin") ?: "" else "")

    private fun dav(name: String) = SourceConfig(name = name, type = SourceType.WEBDAV, url = Fx.arg("webdav_url")!!, path = "/fixture", username = "alice")

    /** Re-reads the persisted list the way a fresh process does. */
    private fun restarted(): List<SourceConfig> = SourceRegistry.savedSources()

    @Test fun addEditDuplicateDeleteReorderPersistForEveryProtocol() {
        Fx.require("smb_host", "ftp_host", "webdav_url")
        val s = smb("NAS SMB", "alice"); val f = ftp("FTP"); val d = dav("DAV")
        SourceRegistry.upsert(s, "alicepass"); SourceRegistry.upsert(f, "alicepass"); SourceRegistry.upsert(d, "pa:ss/1")
        assertEquals(listOf("NAS SMB", "FTP", "DAV"), restarted().map { it.name })
        for (c in listOf(s, f, d)) assertEquals("${c.name}: ${SourceRegistry.fileSystem(c.id).testConnection()}", ConnectionOutcome.OK, SourceRegistry.fileSystem(c.id).testConnection().outcome)

        // Edit (rename + new root) bumps the revision and keeps the stored password.
        SourceRegistry.upsert(s.copy(name = "NAS renamed", path = "/fixture"), null)
        val edited = restarted().first { it.id == s.id }
        assertEquals("NAS renamed", edited.name)
        assertTrue(edited.revision > 0)
        assertTrue(SourceRegistry.fileSystem(s.id).list("/").any { it.name == "Album-A" })

        // Duplicate: new id, own credential slot; changing the copy's password does not affect the original.
        val copy = SourceRegistry.duplicate(s.id)!!
        assertNotEquals(s.id, copy.id)
        SourceRegistry.upsert(copy.copy(username = "bob"), "bobpass")
        assertEquals(ConnectionOutcome.OK, SourceRegistry.fileSystem(s.id).testConnection().outcome)
        assertEquals(ConnectionOutcome.OK, SourceRegistry.fileSystem(copy.id).testConnection().outcome)
        val store = KeystoreCredentialStore(Fx.ctx)
        assertEquals("alicepass", store.get(SourceRegistry.require(s.id).effectiveCredentialRef))
        assertEquals("bobpass", store.get(SourceRegistry.require(copy.id).effectiveCredentialRef))

        // Reorder and delete.
        SourceRegistry.move(d.id, -1); SourceRegistry.move(d.id, -1)
        assertEquals(d.id, restarted()[1].id)
        SourceRegistry.remove(f.id)
        assertFalse(restarted().any { it.id == f.id })

        // Nothing secret in the persisted JSON or in any app SharedPreferences except the encrypted store.
        val prefsDir = File(Fx.ctx.applicationInfo.dataDir, "shared_prefs")
        for (file in prefsDir.listFiles()!!.filter { it.name != "fp_credentials.xml" }) {
            val t = file.readText()
            assertFalse("${file.name} contains a password", t.contains("alicepass") || t.contains("bobpass") || t.contains("pa:ss/1"))
        }
        assertFalse(Gson().toJson(restarted()).contains("pass"))
    }

    @Test fun connectionTestReachesTheRootAndDistinguishesFailures() {
        Fx.require("smb_host", "ftp_host")
        fun outcome(c: SourceConfig, pw: String?) = SourceRegistry.create(c, pw).use { it.testConnection().outcome }
        assertEquals(ConnectionOutcome.OK, outcome(smb("a", "alice", root = "/fixture"), "alicepass"))
        assertEquals(ConnectionOutcome.AUTH_FAILED, outcome(smb("a", "alice"), "wrong"))
        assertEquals(ConnectionOutcome.SHARE_MISSING, outcome(smb("a", "alice", share = ""), "alicepass"))
        assertEquals(ConnectionOutcome.SHARE_NOT_FOUND, outcome(smb("a", "alice", share = "nope"), "alicepass"))
        assertEquals(ConnectionOutcome.ROOT_NOT_FOUND, outcome(smb("a", "alice", root = "/missing"), "alicepass"))
        assertEquals(ConnectionOutcome.PERMISSION_DENIED, outcome(smb("a", "alice", root = "/Locked"), "alicepass"))
        assertEquals(ConnectionOutcome.UNREACHABLE, outcome(SourceConfig(type = SourceType.SMB, host = "10.255.255.1", port = 1, share = "x", username = "a"), "x"))
        assertEquals(ConnectionOutcome.OK, outcome(ftp("f"), "alicepass"))
        assertEquals(ConnectionOutcome.AUTH_FAILED, outcome(ftp("f"), "bad"))
    }

    @Test fun sameHostAndPathDifferentAccountsNeverMix() {
        Fx.require("smb_host")
        val a = smb("Alice home", "alice", share = "home"); val b = smb("Bob home", "bob", share = "home")
        SourceRegistry.upsert(a, "alicepass"); SourceRegistry.upsert(b, "bobpass")
        val pool = Executors.newFixedThreadPool(6)
        val jobs = (0 until 30).map { i ->
            pool.submit<Pair<String, String>> {
                val id = if (i % 2 == 0) a.id else b.id
                id to SourceRegistry.fileSystem(id).readText("/Album/owner.txt").trim()
            }
        }
        jobs.forEach { val (id, owner) = it.get(60, TimeUnit.SECONDS); assertEquals(if (id == a.id) "alice" else "bob", owner) }
        pool.shutdown()
        // Image and playback go through the same per-source lookup.
        assertTrue(SourceRegistry.fileSystem(a.id).list("/Album").any { it.name == "cover.jpg" })
        assertTrue(SourceRegistry.fileSystem(b.id).list("/Album").any { it.name == "cover.png" })
        val playA = Fx.play(SourceRef(a.id, "/Album/track.flac"))
        val playB = Fx.play(SourceRef(b.id, "/Album/track.flac"))
        assertTrue("$playA $playB", playA.reachedReady && playB.reachedReady)
        val logcat = Fx.shell("logcat -d")
        assertFalse("password in logcat", logcat.contains("alicepass") || logcat.contains("bobpass"))
    }
}
