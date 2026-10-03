package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.KeystoreCredentialStore
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Source editor UI against the fixture servers: add SMB/FTP/FTPS/WebDAV with the
 * connection test (each failure kind), edit (password kept), duplicate (own credential), reorder, delete, and
 * — in the second method, a new process — the restored list. Methods run in order (instrument.sh run-each).
 */
@RunWith(AndroidJUnit4::class)
class SourceUiTest : UiTestBase() {
    private val expectFile get() = File(Fx.ctx.filesDir, "ui-sources-expected.txt")
    private val creds by lazy { KeystoreCredentialStore(Fx.ctx) }

    private fun saved(name: String): SourceConfig? = SourceRegistry.savedSources().firstOrNull { it.name == name }

    private fun openAdd(menuLabel: String) {
        toBrowser()
        click("btn_add_source")
        clickText(menuLabel)
        until(5_000, "editor") { exists("btn_test") }
    }

    private fun current() = runCatching { text("test_result") }.getOrElse { "<no result>" }

    private fun testConnection(expected: String) {
        click("btn_test")
        try {
            until(30_000, "test result '$expected'") { exists("test_result") && text("test_result") == expected }
        } catch (e: AssertionError) {
            throw AssertionError("expected '$expected' but the dialog shows '${current()}'", e)
        }
        Fx.log("connection test: $expected")
    }

    private fun testOk() {
        click("btn_test")
        try {
            until(30_000, "test OK") { exists("test_result") && text("test_result").startsWith("Connected") }
        } catch (e: AssertionError) {
            throw AssertionError("expected OK but the dialog shows '${current()}'", e)
        }
        Fx.log("connection test: ${current()}")
    }

    private fun secretsInPrefs(vararg secrets: String): List<String> {
        val dir = File(Fx.ctx.applicationInfo.dataDir, "shared_prefs")
        // SharedPreferences rewrite files atomically (a transient .bak); skip files that vanish while we look.
        return dir.listFiles().orEmpty().flatMap { f ->
            val t = runCatching { f.readText() }.getOrNull() ?: return@flatMap emptyList()
            secrets.filter { t.contains(it) }.map { "${f.name}:$it" }
        }
    }

    @Test fun a_addTestEditDuplicateReorderDeleteThroughUi() {
        val smbHost = Fx.require("smb_host", "ftp_host", "webdav_url", "ftps_pin").let { Fx.arg("smb_host")!! }
        SourceRegistry.init(Fx.ctx)
        SourceRegistry.savedSources().forEach { SourceRegistry.remove(it.id) }

        // ---- SMB: every connection-test outcome from the dialog ----
        openAdd("SMB")
        type("field_name", "ui-smb"); type("field_host", smbHost); type("field_share", "music"); type("field_path", "/fixture")
        type("field_user", "alice"); type("field_password", "wrong")
        testConnection(str(R.string.source_test_auth_failed))
        type("field_password", "alicepass"); type("field_share", "")
        testConnection(str(R.string.source_test_share_missing))
        type("field_share", "nosuchshare")
        testConnection(str(R.string.source_test_share_not_found))
        type("field_share", "music"); type("field_path", "/no/such/root")
        testConnection(str(R.string.source_test_root_not_found))
        type("field_path", "/Locked")
        testConnection(str(R.string.source_test_permission_denied))
        type("field_path", "/fixture"); type("field_host", "unreachable.invalid")
        testConnection(str(R.string.source_test_unreachable))
        type("field_host", smbHost)
        testOk()
        click("btn_save")
        until(5_000, "ui-smb saved") { saved("ui-smb") != null && exists("source_ui-smb") }

        // ---- FTP, FTPS (pinned certificate), WebDAV ----
        val ftpHost = Fx.arg("ftp_host")!!
        openAdd("FTP / FTPS")
        type("field_name", "ui-ftp"); type("field_host", ftpHost); type("field_port", "2121"); type("field_path", "/fixture")
        type("field_user", "alice"); type("field_password", "alicepass")
        testOk(); click("btn_save")
        openAdd("FTP / FTPS")
        type("field_name", "ui-ftps"); type("field_host", ftpHost); type("field_port", "2124"); type("field_path", "/fixture")
        type("field_user", "alice"); type("field_password", "alicepass")
        click("field_tls")
        type("field_pin", "00".repeat(32))
        testConnection(str(R.string.source_test_tls_error))
        type("field_pin", Fx.arg("ftps_pin")!!)
        testOk(); click("btn_save")
        openAdd("WebDAV")
        type("field_name", "ui-dav"); type("field_url", Fx.arg("webdav_url")!!); type("field_path", "/fixture")
        type("field_user", "alice"); type("field_password", "pa:ss/1")
        testOk(); click("btn_save")
        until(5_000, "4 sources") { listOf("ui-smb", "ui-ftp", "ui-ftps", "ui-dav").all { saved(it) != null } }
        assertTrue(saved("ui-ftps")!!.useTls)

        // Passwords live only in the Keystore-backed store, never in preferences/JSON.
        assertEquals(emptyList<String>(), secretsInPrefs("alicepass", "pa:ss/1"))
        assertFalse(SourceRegistry.savedSources().toString().contains("alicepass"))

        // ---- Edit: rename, password field left empty keeps the stored password ----
        longClick("source_ui-smb")
        clickText(str(R.string.common_edit))
        until(5_000, "edit dialog") { exists("btn_test") }
        type("field_name", "ui-smb-renamed")
        testOk()
        click("btn_save")
        until(5_000, "renamed") { saved("ui-smb-renamed") != null && saved("ui-smb") == null }

        // ---- Duplicate: new id, own credential slot; changing the copy does not touch the original ----
        val dav = saved("ui-dav")!!
        longClick("source_ui-dav")
        clickText(str(R.string.common_duplicate))
        until(5_000, "copy") { saved("ui-dav (Copy)") != null }
        val copy = saved("ui-dav (Copy)")!!
        assertNotEquals(dav.id, copy.id)
        assertNotEquals(dav.effectiveCredentialRef, copy.effectiveCredentialRef)
        longClick("source_ui-dav (Copy)")
        clickText(str(R.string.common_edit))
        until(5_000, "edit copy") { exists("btn_test") }
        type("field_user", "bob"); type("field_password", "bobpass")
        testOk(); click("btn_save")
        until(5_000, "copy saved as bob") { saved("ui-dav (Copy)")?.username == "bob" }
        assertEquals("pa:ss/1", creds.get(saved("ui-dav")!!.effectiveCredentialRef))
        assertEquals("bobpass", creds.get(saved("ui-dav (Copy)")!!.effectiveCredentialRef))
        assertEquals("alice", saved("ui-dav")!!.username)

        // ---- Reorder ----
        val before = SourceRegistry.savedSources().map { it.name }
        longClick("source_ui-ftp")
        clickText(str(R.string.common_move_up))
        until(5_000, "moved up") { SourceRegistry.savedSources().map { it.name }.indexOf("ui-ftp") == before.indexOf("ui-ftp") - 1 }
        val top = { n: String -> node("source_$n").fetchSemanticsNode().boundsInRoot.top }
        assertTrue("UI order follows", top("ui-ftp") < top("ui-smb-renamed"))

        // ---- Delete (credential removed) ----
        val ftps = saved("ui-ftps")!!
        longClick("source_ui-ftps")
        clickText(str(R.string.common_delete))
        // The removed lazy item may linger as a detached semantics node for a moment; what matters is that it is not shown.
        until(5_000, "deleted") { saved("ui-ftps") == null && runCatching { node("source_ui-ftps").assertIsDisplayed() }.isFailure }
        assertEquals(null, creds.get(ftps.effectiveCredentialRef))

        expectFile.writeText(SourceRegistry.savedSources().joinToString("\n") { "${it.id}\t${it.name}" })
        Fx.log("sources after UI flow: ${SourceRegistry.savedSources().map { it.name }}")
    }

    @Test fun b_listIsRestoredAfterRestartAndStillConnects() {
        val expected = expectFile.takeIf { it.exists() }?.readLines() ?: error("run a_ first")
        SourceRegistry.init(Fx.ctx)
        assertEquals(expected, SourceRegistry.savedSources().map { "${it.id}\t${it.name}" })
        toBrowser()
        for (line in expected) {
            val name = line.substringAfter('\t')
            until(5_000, "row $name") { exists("source_$name") }
        }
        for (src in SourceRegistry.savedSources()) {
            val r = SourceRegistry.create(src).use { it.testConnection() }
            Fx.log("restored ${src.name}: ${r.outcome} ${r.entries}")
            assertTrue("${src.name} connects after restart: $r", r.ok)
        }
        assertTrue(SourceRegistry.savedSources().none { it.type == SourceType.FTP && it.useTls })
    }
}
