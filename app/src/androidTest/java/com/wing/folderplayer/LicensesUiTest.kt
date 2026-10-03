package com.wing.folderplayer

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Open source licenses shows the bundled notices (MIT upstream, FFmpeg LGPL, third-party licenses). */
@RunWith(AndroidJUnit4::class)
class LicensesUiTest : UiTestBase() {
    @Test fun noticesAreShownFromSettings() {
        toSettings()
        compose.onNodeWithTag("open_licenses", useUnmergedTree = true).performScrollTo()
        click("open_licenses")
        until(5_000, "licenses dialog") { exists("licenses_text") }
        val notices = compose.activity.assets.open("licenses/third_party_notices.txt").bufferedReader().readText()
        for (needle in listOf("Folder Player (MIT License)", "FFmpeg 7.1.5", "GNU Lesser General Public License", "jUPnP 3.0.5", "Apache License"))
            assertTrue("notice mentions $needle", notices.contains(needle))
        assertTrue(textExists("Folder Player Fork — open source notices"))
    }
}
