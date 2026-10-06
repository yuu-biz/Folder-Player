package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings structure (categories → settings → pages): one pane on a phone, two panes in a wide window; long texts, a
 * large font and the keyboard never make a control unreachable.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest : UiTestBase() {
    private val categories = listOf("playback", "display", "library", "lyrics", "storage", "about")
    private val density get() = compose.activity.resources.displayMetrics.density

    @After fun cleanup() {
        Fx.shell("wm size reset")
        Fx.shell("settings put system font_scale 1.0")
    }

    private fun windowDp(w: Int, h: Int) {
        Fx.shell("wm size ${(w * density).toInt()}x${(h * density).toInt()}")
        until(10_000, "window ${w}x$h dp") {
            val c = compose.activity.resources.configuration
            // The height reported is the window without the system bars (50 to 110 dp less).
            c.screenWidthDp in (w - 8)..(w + 8) && c.screenHeightDp in (h - 130)..(h + 8)
        }
        compose.waitForIdle()
    }

    private fun fontScale(scale: Float) {
        Fx.shell("settings put system font_scale $scale")
        until(10_000, "font scale $scale") { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < 0.01f }
        compose.waitForIdle()
    }

    /** Everything that can be clicked lies inside the window sideways (nothing is cut off at the edge). */
    private fun assertClickablesInsideWindow(what: String) {
        val root = compose.onNodeWithTag("settings_column").fetchSemanticsNode().boundsInRoot
        val nodes = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what: something to click", nodes.isNotEmpty())
        for (n in nodes) {
            val b = n.boundsInRoot
            assertTrue("$what: $b inside $root", b.left >= root.left - 1 && b.right <= root.right + 1)
        }
    }

    // Phone: the list of categories first; each opens its settings; the long lists are pages of their own.
    @Test fun s01_phoneNavigationCategoriesThenSettingsThenPages() {
        toSettings()
        assertEquals("one pane", false, exists("player_pane"))
        for (c in categories) assertTrue("category $c", exists("settings_cat_$c"))
        // Nothing of the long lists is spread over the top level.
        for (tag in listOf("lang_ja", "font_system", "thumbs_LOCAL", "cover_LARGE", "ai_model", "open_licenses")) assertFalse("$tag not at the top", exists(tag))

        click("settings_cat_display")
        until(5_000, "display") { exists("cover_LARGE") && !exists("settings_cat_display") }
        assertTrue(exists("settings_sub_language") && exists("settings_sub_font"))
        assertFalse("language list is a page of its own", exists("lang_ja"))
        click("settings_sub_language")
        until(5_000, "language page") { exists("lang_ja") && exists("lang_") }
        // The order of the list: system default, 简体中文, English, 日本語, 繁體中文, Français, Italiano.
        val tags = listOf("", "zh-CN", "en", "ja", "zh-TW", "fr", "it")
        val tops = tags.map { node("lang_$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals("language order", tops.sorted(), tops)

        // Back goes up one level at a time: page → category → category list → browser.
        pressBack()
        until(5_000, "category") { exists("cover_LARGE") && !exists("lang_ja") }
        pressBack()
        until(5_000, "category list") { exists("settings_cat_display") && !exists("cover_LARGE") }
        assertTrue(exists("settings_column"))
        pressBack()
        until(5_000, "browser") { browserShown() }
    }

    // Wide: categories on the left, the content on the right; choosing another category changes only the right side.
    @Test fun s02_wideWindowShowsCategoriesBesideTheirContent() {
        windowDp(780, 600)
        toSettings()
        until(5_000, "two panes") { exists("settings_categories") && exists("dlna_enabled") }
        val left = node("settings_categories").fetchSemanticsNode().boundsInRoot
        val right = node("dlna_enabled").fetchSemanticsNode().boundsInRoot
        assertTrue("categories ($left) left of the content ($right)", left.right <= right.left + 2)
        click("settings_cat_library")
        until(5_000, "library") { exists("grid_3") && !exists("dlna_enabled") }
        assertTrue("categories stay", exists("settings_cat_display"))
        click("settings_cat_display")
        click("settings_sub_font")
        until(5_000, "font page") { exists("font_system") }
        // The category is still highlighted and the list still there.
        assertTrue(exists("settings_cat_display"))
        pressBack()
        until(5_000, "category content") { exists("cover_LARGE") && !exists("font_system") }
        pressBack()
        until(5_000, "browser") { browserShown() || exists("player_pane") }
        assertFalse(exists("settings_column"))
    }

    // A phone in landscape is wide but low (about 720 x 360 dp): Settings stays one pane there, as the app itself stays narrow.
    @Test fun s05_wideButLowWindowKeepsSettingsInOnePane() {
        windowDp(720, 360)
        toSettings()
        for (c in categories) assertTrue("category $c", exists("settings_cat_$c"))
        assertFalse("no content pane beside the list", exists("dlna_enabled"))
        click("settings_cat_playback")
        until(5_000, "playback page") { exists("dlna_enabled") && !exists("settings_cat_playback") }
        pressBack()
        until(5_000, "category list") { exists("settings_cat_playback") && !exists("dlna_enabled") }
        // The same width with enough height (780 x 600 dp is wide) shows two panes: only the height decides.
        windowDp(780, 600)
        until(5_000, "two panes") { exists("settings_cat_playback") && exists("dlna_enabled") }
        windowDp(720, 360)
        until(5_000, "one pane again") { exists("settings_cat_playback") && !exists("dlna_enabled") }
    }

    // Narrow window, large font: every control of every page stays inside the window and can be scrolled to.
    @Test fun s03_narrowWindowAndLargeFontKeepEverythingReachable() {
        windowDp(320, 640)
        fontScale(2.0f)
        toSettings()
        assertClickablesInsideWindow("category list")
        for (c in categories) {
            click("settings_cat_$c")
            compose.waitForIdle()
            assertClickablesInsideWindow("category $c")
            pressBack()
            until(5_000, "category list") { exists("settings_cat_$c") }
        }
        // Pages below the categories.
        for ((c, sub) in listOf("display" to "language", "display" to "font", "library" to "thumbnails", "lyrics" to "ai")) {
            click("settings_cat_$c")
            click("settings_sub_$sub")
            compose.waitForIdle()
            assertClickablesInsideWindow("page $sub")
            pressBack(); pressBack()
            until(5_000, "category list") { exists("settings_cat_$c") }
        }
        // The last item of a long page can be reached by scrolling (the version, at the bottom of About).
        settingsReveal("about_version")
        node("about_version").assertIsDisplayed()
    }

    // Text fields: the keyboard does not hide the field being edited.
    @Test fun s04_keyboardDoesNotCoverTheFieldBeingEdited() {
        toSettings()
        click("settings_cat_lyrics")
        click("settings_sub_ai")
        until(5_000, "AI page") { exists("ai_model") }
        compose.onNodeWithTag("ai_model").performTextInput("test-model")
        compose.waitForIdle()
        val ime = runCatching { until(5_000, "keyboard") { imeTop() != null }; true }.getOrDefault(false)
        if (ime) {
            val field = node("ai_model").fetchSemanticsNode().boundsInRoot
            val top = imeTop()!!
            assertTrue("field ($field) above the keyboard (top $top)", field.bottom <= top + 1)
        } else Fx.log("no on-screen keyboard on this device: keyboard step not checked")
        compose.onNodeWithTag("ai_model").performTextInput("") // keep the text; just make sure the field takes input
        pressBack() // hides the keyboard, or goes up one level
    }

    private fun imeTop(): Float? = runCatching {
        var r: Float? = null
        compose.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                r = (compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).toFloat()
            }
        }
        r
    }.getOrNull()
}
