package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.playback.PlaybackEvent
import com.wing.folderplayer.playback.PlaybackEventKind
import com.wing.folderplayer.playback.PlaybackLog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The playback log on a device: a play, a pause from this app's controller and an unplugged headphone (the intent handed to
 * the service's receiver, as in PlaybackServiceTest) are recorded with their reason and the app that asked.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackLogServiceTest : ServiceTestBase() {
    private fun since(t: Long) = PlaybackLog.read().filter { it.time >= t }
    private fun List<PlaybackEvent>.has(kind: PlaybackEventKind, vararg words: String) =
        any { e -> e.kind == kind && words.all { e.detail.contains(it) } }

    @Test fun playPauseAndNoisyAreLoggedWithTheirReason() {
        val start = System.currentTimeMillis() - 1_000
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Long"), null) }
        waitFor(15_000, "playing") { state.isPlaying && state.currentPosition > 1_000 }
        waitFor(5_000, "play logged") { since(start).has(PlaybackEventKind.PLAY) }

        main { vm.playPause() }
        waitFor(5_000, "paused") { !state.isPlaying }
        waitFor(5_000, "pause logged") { since(start).has(PlaybackEventKind.PAUSE, "USER_REQUEST") }
        val events = since(start)
        Fx.log("playback log:\n" + events.joinToString("\n") { "${it.kind} ${it.detail}" })
        assertTrue("the pause names this app as the controller", events.has(PlaybackEventKind.COMMAND, Fx.ctx.packageName))
        assertTrue("the service start is logged", PlaybackLog.read().any { it.kind == PlaybackEventKind.SERVICE_START })

        main { vm.playPause() }
        waitFor(5_000, "playing again") { state.isPlaying }
        val noisyAt = System.currentTimeMillis() - 100
        val svc = com.wing.folderplayer.service.MusicService.current ?: error("service not running")
        main { svc.becomingNoisyReceiver.onReceive(svc, android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        waitFor(5_000, "paused by noisy") { !state.isPlaying }
        waitFor(5_000, "noisy logged before its pause") {
            val after = since(noisyAt)
            val n = after.indexOfFirst { it.kind == PlaybackEventKind.NOISY }
            n >= 0 && after.drop(n).any { it.kind == PlaybackEventKind.PAUSE }
        }
    }
}
