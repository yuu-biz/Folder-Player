package com.wing.folderplayer.ui.adaptive

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the app lays itself out follows the size of the window it has now (rotation, fold / unfold, split screen, freeform
 * resizing), never the kind of device. Two layouts:
 *
 * - narrow (the phone UI): browser, mini player over it, mini ⇄ full player;
 * - wide: browser and player side by side, the player docked in its own pane; the pane can be hidden so that the browser
 *   has the whole window (a mini player at the bottom then shows what plays, and a tap on it brings the pane back).
 */
object WindowLayout {
    /** Narrowest window that fits a browser column and a player column next to each other (each about a phone's width). */
    const val WideMinWidthDp = 640

    /** Lowest window that still gives the docked player room for cover, controls and lyrics (a phone in landscape does not). */
    const val WideMinHeightDp = 480

    fun isWide(widthDp: Int, heightDp: Int) = widthDp >= WideMinWidthDp && heightDp >= WideMinHeightDp

    /** Width of the browser pane of the wide layout: about two fifths of the window, between 320 and 480 dp. */
    fun browserPaneWidth(widthDp: Int): Dp = (widthDp * 0.4f).coerceIn(320f, 480f).dp

    /** The player pane of the wide layout was hidden by the user (kept across restarts; the narrow layout ignores it). */
    fun playerPaneHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_PANE_HIDDEN, false)
    fun setPlayerPaneHidden(context: Context, hidden: Boolean) = prefs(context).edit { putBoolean(KEY_PANE_HIDDEN, hidden) }

    private const val KEY_PANE_HIDDEN = "wide_player_pane_hidden"
    private fun prefs(context: Context) = context.getSharedPreferences("layout_prefs", Context.MODE_PRIVATE)
}

/** The window is wide enough for browser and player side by side (read from the window's own configuration). */
@Composable
fun isWideWindow(): Boolean {
    val c = LocalConfiguration.current
    return WindowLayout.isWide(c.screenWidthDp, c.screenHeightDp)
}
