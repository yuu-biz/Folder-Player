package com.wing.folderplayer

import androidx.compose.ui.test.assertIsEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Search network" button of the SMB editor: the search starts on the real NsdManager and the subnet probe, ends on
 * its own, says so when nothing is found, and closes without touching the editor. The emulator's own network has no SMB
 * server, so what is found on a real LAN is not checked here.
 */
@RunWith(AndroidJUnit4::class)
class SmbDiscoveryUiTest : UiTestBase() {
    @Test fun a50_searchRunsFinishesAndClosesWithoutChangingTheEditor() {
        toBrowser()
        click("btn_add_source")
        clickText("SMB")
        until(5_000, "editor") { exists("btn_discover") }
        val hostBefore = text("field_host")
        click("btn_discover")
        until(5_000, "picker") { exists("discover_list") }
        until(40_000, "search finished") { exists("discover_none") }
        assertFalse(textExists(str(R.string.source_discover_searching)))
        clickText(str(R.string.common_close))
        until(5_000, "picker closed") { !exists("discover_list") }
        assertTrue(exists("btn_test") && exists("btn_discover"))
        assertTrue("host untouched", text("field_host") == hostBefore)
    }

    /** Needs the Docker Samba fixture (run-suites.sh passes smb_host). */
    @Test fun a51_sharesAreListedForTheTypedLoginAndPickingOneFillsTheField() {
        Fx.require("smb_host")
        toBrowser()
        click("btn_add_source")
        clickText("SMB")
        until(5_000, "editor") { exists("btn_shares") }
        // No host yet: nothing to ask.
        assertFalse(runCatching { node("btn_shares").assertIsEnabled() }.isSuccess)
        type("field_host", Fx.arg("smb_host")!!)
        type("field_user", "alice")
        type("field_password", "wrongpass")
        click("btn_shares")
        until(20_000, "failure shown") { exists("shares_failed") }
        assertTrue(textExists(str(R.string.source_shares_auth_failed)))
        clickText(str(R.string.common_close))
        type("field_password", "alicepass")
        click("btn_shares")
        until(20_000, "shares listed") { exists("share_music") }
        assertTrue(exists("share_home") && exists("share_alice") && exists("share_public"))
        assertFalse(exists("share_IPC$"))
        click("share_music")
        until(5_000, "picker closed") { !exists("shares_list") }
        assertEquals("music", text("field_share"))
    }
}
