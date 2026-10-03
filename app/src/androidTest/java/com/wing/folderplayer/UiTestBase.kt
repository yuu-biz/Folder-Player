package com.wing.folderplayer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Rule

/** Drives the real MainActivity UI (player ⇄ browser ⇄ settings pager) through Compose semantics. */
@OptIn(ExperimentalTestApi::class)
abstract class UiTestBase {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    protected fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    protected fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    protected fun exists(tag: String) = runCatching { node(tag).assertExists(); true }.getOrDefault(false)
    protected fun textExists(text: String) = runCatching { compose.onNodeWithText(text, useUnmergedTree = true).assertExists(); true }.getOrDefault(false)

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

    /** Clicks a node, scrolling it into view first when it sits in a scrollable container (dialogs, settings). */
    protected fun click(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }
    protected fun clickText(text: String) { compose.onNodeWithText(text, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    protected fun longClick(tag: String) { node(tag).performTouchInput { longClick() }; compose.waitForIdle() }

    /** Replaces the text of a field inside a (scrollable) dialog. */
    protected fun type(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }

    /** Pager page 0 = player, 1 = browser, 2 = settings. */
    protected fun toBrowser() {
        compose.waitForIdle()
        if (!exists("source_list") && !exists("file_list") && !exists("file_grid")) {
            // Page 2 (settings) is right of the browser, page 0 (player) left of it.
            if (exists("settings_column")) compose.onRoot().performTouchInput { swipeRight() } else compose.onRoot().performTouchInput { swipeLeft() }
        }
        until(10_000, "browser") { exists("source_list") || exists("file_list") || exists("file_grid") }
    }

    protected fun toSettings() {
        toBrowser()
        compose.onRoot().performTouchInput { swipeLeft() }
        until(10_000, "settings") { exists("settings_column") }
    }

    protected fun toPlayer() {
        // The pager may keep an adjacent page partly composed, so decide by the page that is showing.
        repeat(2) {
            if (exists("source_list") || exists("file_list") || exists("file_grid") || exists("settings_column") || exists("search_field")) {
                compose.onRoot().performTouchInput { swipeRight() }
                compose.waitForIdle()
            }
        }
        until(10_000, "player") { !exists("source_list") && !exists("file_list") && !exists("file_grid") && !exists("settings_column") }
        compose.waitForIdle()
    }
}
