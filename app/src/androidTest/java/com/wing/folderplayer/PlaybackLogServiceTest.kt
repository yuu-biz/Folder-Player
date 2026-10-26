package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.playback.PlaybackDiagnostics
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

    // 1.8.0: a heartbeat with the position while playback is wanted, and a load error the player meets (here a file that is gone).
    @Test fun aHeartbeatShowsThePositionAndALoadErrorIsLogged() {
        PlaybackDiagnostics.heartbeatMs = 1_500
        try {
            val start = System.currentTimeMillis() - 1_000
            main { vm.playFolder(SourceRef(local, "/Music/fixture/Long"), null) }
            waitFor(15_000, "playing") { state.isPlaying && state.currentPosition > 1_000 }
            // The heartbeat is planned when playback is asked for; a pause and a play plan it with the short interval.
            main { vm.playPause() }
            waitFor(5_000, "paused") { !state.isPlaying }
            main { vm.playPause() }
            waitFor(10_000, "heartbeat") { since(start).has(PlaybackEventKind.HEARTBEAT, "READY", "playing=true", " at ", "buffered=") }

            val gone = MusicFile("gone.mp3", "/Music/fixture/Long/gone.mp3", false, sourceId = local)
            val errorAt = System.currentTimeMillis() - 100
            main { vm.playCustomList(listOf(gone), 0) }
            waitFor(15_000, "load error logged") { since(errorAt).has(PlaybackEventKind.LOAD_ERROR, "gone.mp3") }
            Fx.log("playback log:\n" + since(start).joinToString("\n") { "${it.kind} ${it.detail}" })
        } finally {
            PlaybackDiagnostics.heartbeatMs = 5 * 60_000L
            // The failed file leaves the player wanting to play: the next test's play would change nothing (no PLAY logged).
            val svc = com.wing.folderplayer.service.MusicService.current
            if (svc != null) main { svc.becomingNoisyReceiver.onReceive(svc, android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        }
    }
}
