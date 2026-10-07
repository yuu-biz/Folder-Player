package com.wing.folderplayer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule

/** Drives the real MainActivity UI (browser; settings from its menu; mini / full player over it) through Compose semantics. */
@OptIn(ExperimentalTestApi::class)
abstract class UiTestBase {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    protected fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    protected fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    protected fun exists(tag: String) = runCatching { node(tag).assertExists(); true }.getOrDefault(false)
    protected fun textExists(text: String) = runCatching { compose.onNode(visibleText(text), useUnmergedTree = true).assertExists(); true }.getOrDefault(false)

    /**
     * Text on the front-most layer: while the full player is open the pages stay composed underneath it (hidden from
     * accessibility, but still in the unmerged test tree), so their texts are left out.
     */
    protected fun visibleText(text: String): SemanticsMatcher =
        if (exists("player_full")) hasText(text) and !hasAnyAncestor(hasTestTag("app_pages")) else hasText(text)

    protected fun until(timeoutMs: Long = 10_000, what: String = "", cond: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { runCatching(cond).getOrDefault(false) }
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    protected fun text(tag: String): String {
        val cfg = node(tag).fetchSemanticsNode().config
        val parts = cfg.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
            listOfNotNull(cfg.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text)
        return parts.joinToString("")
    }

    /**
     * Settings page that holds the control [tag]: category and (optional) page, as the tags of their rows name them
     * (`settings_cat_<category>`, `settings_sub_<page>`). Null: not a Settings control.
     */
    protected fun settingsRoute(tag: String): Pair<String, String?>? = when {
        tag.startsWith("lang_") -> "display" to "language"
        tag.startsWith("font_") -> "display" to "font"
        listOf("cover_", "bg_", "title_", "orientation_", "notch_").any { tag.startsWith(it) } -> "display" to null
        listOf("sort_", "sortdir_", "defview_", "grid_").any { tag.startsWith(it) } -> "library" to null
        tag.startsWith("thumbs_") -> "library" to "thumbnails"
        tag.startsWith("cache") || tag.startsWith("netstat") -> "library" to "imagecache"
        tag == "clear_image_cache" || tag.startsWith("perm_") -> "storage" to null
        tag == "ai_lyrics_auto" || tag.startsWith("lyrics_priority_") -> "lyrics" to null
        tag == "dlna_enabled" || tag == "auto_save" || tag == "native_decoder_status" -> "playback" to null
        tag == "about_version" || tag == "open_licenses" -> "about" to null
        else -> null
    }

    /** Opens the Settings page of the control [tag] (from wherever Settings is now) and scrolls the control into view. */
    protected fun settingsReveal(tag: String) {
        val (category, sub) = settingsRoute(tag) ?: error("not a Settings control: $tag")
        if (!exists(tag)) {
            var up = 0
            while (!exists("settings_cat_$category") && up++ < 4) { compose.onNodeWithTag("settings_up").performClick(); compose.waitForIdle() }
            // (Scrolled to first: with a large font a row can be below the visible part of the list.)
            for (row in listOfNotNull("settings_cat_$category", sub?.let { "settings_sub_$it" })) {
                if (row.startsWith("settings_sub_") && exists(tag)) break
                runCatching { compose.onNodeWithTag(row, useUnmergedTree = true).performScrollTo() }
                compose.onNodeWithTag(row, useUnmergedTree = true).performClick(); compose.waitForIdle()
            }
        }
        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
        compose.waitForIdle()
    }

    /** Clicks a node, scrolling it into view first when it sits in a scrollable container (dialogs, settings). */
    protected fun click(tag: String) {
        if (exists("settings_column") && settingsRoute(tag) != null) settingsReveal(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }
    protected fun clickText(text: String) { compose.onNode(visibleText(text), useUnmergedTree = true).performClick(); compose.waitForIdle() }
    protected fun longClick(tag: String) { node(tag).performTouchInput { longClick() }; compose.waitForIdle() }

    /** Replaces the text of a field inside a (scrollable) dialog. */
    protected fun type(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }

    /** System Back, through the same dispatcher the Compose back handlers use. */
    protected fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    protected fun playerOpen() = exists("player_full")
    protected fun browserShown() = !playerOpen() && !exists("settings_column") &&
        (exists("source_list") || exists("file_list") || exists("file_grid"))

    /** Browser is the base page: Settings and the full player are closed over it. */
    protected fun toBrowser() {
        compose.waitForIdle()
        if (playerOpen()) click("btn_collapse_player")
        // Settings has pages below its categories: one Back goes up one level.
        var backs = 0
        while (exists("settings_column") && backs++ < 4) pressBack()
        until(10_000, "browser") { browserShown() }
    }

    /** Settings is opened from the browser's ⋮ menu (on every browser level). */
    protected fun toSettings() {
        toBrowser()
        click("btn_overflow")
        click("menu_settings")
        until(10_000, "settings") { exists("settings_column") && !exists("btn_overflow") }
    }

    /** The full player is opened from the mini player (there must be a track). */
    protected fun toPlayer() {
        compose.waitForIdle()
        if (!playerOpen()) {
            toBrowser()
            until(10_000, "mini player") { exists("mini_player") }
            click("mini_player")
        }
        until(10_000, "player") { playerOpen() }
        compose.waitForIdle()
    }
}
