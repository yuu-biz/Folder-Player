package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
