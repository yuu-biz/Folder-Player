package com.wing.folderplayer.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the app lays itself out follows the size of the window it has now (rotation, fold / unfold, split screen, freeform
 * resizing), never the kind of device. Two layouts:
 *
 * - narrow (the phone UI): browser, mini player over it, mini ⇄ full player;
 * - wide: browser and player side by side, the player docked in its own pane.
 */
object WindowLayout {
    /** Narrowest window that fits a browser column and a player column next to each other (each about a phone's width). */
    const val WideMinWidthDp = 640

    /** Lowest window that still gives the docked player room for cover, controls and lyrics (a phone in landscape does not). */
    const val WideMinHeightDp = 480

    fun isWide(widthDp: Int, heightDp: Int) = widthDp >= WideMinWidthDp && heightDp >= WideMinHeightDp

    /** Width of the browser pane of the wide layout: about two fifths of the window, between 320 and 480 dp. */
    fun browserPaneWidth(widthDp: Int): Dp = (widthDp * 0.4f).coerceIn(320f, 480f).dp
}

/** The window is wide enough for browser and player side by side (read from the window's own configuration). */
@Composable
fun isWideWindow(): Boolean {
    val c = LocalConfiguration.current
    return WindowLayout.isWide(c.screenWidthDp, c.screenHeightDp)
}
