package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notification tap that creates a new activity (the old one is gone, the service still plays) with no cached track
 * and a slow controller connection: the "show the player" request must wait for the controller, not be dropped. With
 * no track at all the request ends on the browser and nothing starts playing.
 */
@RunWith(AndroidJUnit4::class)
class OpenPlayerColdStartTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val local = SourceRegistry.LOCAL_INTERNAL_ID

    private fun exists(tag: String) = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists(); true }.getOrDefault(false)
    private fun <T> onMain(block: () -> T): T {
        var r: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST") return r as T
    }
    private fun servicePlaying() = onMain { MusicService.current?.isPlayingForTest == true }
    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun openPlayerIntent() = Intent(Fx.ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    @After fun cleanup() {
        PlayerViewModel.controllerConnectDelayMsForTest = 0
        runCatching { onMain { MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) } } }
    }

    @Test fun a_newActivityWithoutCacheOpensThePlayerOnceTheControllerConnects() {
        // Playback started by an activity that is then closed; the service keeps playing.
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> ViewModelProvider(a)[PlayerViewModel::class.java].playFolder(SourceRef(local, "${Fx.FX}/Long"), null) }
            waitFor(20_000, "playing") { servicePlaying() }
            Thread.sleep(2_000)
        }
        Thread.sleep(1_000)
        assertTrue("service still plays without an activity", servicePlaying())

        PlaybackPreferences(Fx.ctx).clearSession() // no cached title / track id: nothing known before the controller connects
        PlayerViewModel.controllerConnectDelayMsForTest = 3_000
        ActivityScenario.launch<MainActivity>(openPlayerIntent()).use {
            Thread.sleep(1_000)
            assertFalse("no track known yet, so no player yet", exists("player_full"))
            waitFor(15_000, "full player once the controller is connected") { exists("player_full") }
            assertTrue(servicePlaying())
        }
    }

    @Test fun b_withoutAnyTrackTheRequestEndsOnTheBrowserAndNothingPlays() {
        // No session at all: nothing in the service, nothing saved.
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> ViewModelProvider(a)[PlayerViewModel::class.java].dismissSession() }
            Thread.sleep(1_000)
        }
        PlaybackPreferences(Fx.ctx).clearSession()
        PlayerViewModel.controllerConnectDelayMsForTest = 2_000
        ActivityScenario.launch<MainActivity>(openPlayerIntent()).use { sc ->
            Thread.sleep(5_000) // past the delayed connection
            assertFalse("no track: no player", exists("player_full"))
            assertTrue("browser shown", exists("source_list") || exists("file_list") || exists("file_grid"))
            assertFalse("nothing started", servicePlaying())
            // The request is finished, not left waiting: a song started later does not open the player by itself.
            sc.onActivity { a -> ViewModelProvider(a)[PlayerViewModel::class.java].playFolder(SourceRef(local, "${Fx.FX}/Long"), null) }
            waitFor(20_000, "playing") { servicePlaying() }
            Thread.sleep(1_000)
            assertFalse("the old request did not open the player", exists("player_full"))
        }
    }
}
