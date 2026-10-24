package com.wing.folderplayer

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.playback.PlaybackEventKind
import com.wing.folderplayer.playback.PlaybackLog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Playback → Playback log lists the recorded events and clears them. */
@RunWith(AndroidJUnit4::class)
class PlaybackLogUiTest : UiTestBase() {
    @Test fun p01_theLogIsListedAndCleared() {
        PlaybackLog.init(Fx.ctx)
        PlaybackLog.clear()
        PlaybackLog.record(PlaybackEventKind.PAUSE, "reason=AUDIO_FOCUS_LOSS ui-test.flac at 12s")
        toSettings()
        settingsReveal("playback_log_list")
        until(10_000, "the event is listed") { compose.onAllNodesWithTag("playback_log_row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasText("reason=AUDIO_FOCUS_LOSS ui-test.flac at 12s", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        assertTrue(exists("playback_log_share"))
        click("playback_log_clear")
        until(10_000, "cleared") { exists("playback_log_empty") }
        assertTrue(PlaybackLog.read().isEmpty())
    }
}
