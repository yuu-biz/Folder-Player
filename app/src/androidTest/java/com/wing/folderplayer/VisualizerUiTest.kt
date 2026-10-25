package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.playback.AudioTap
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * A song without lyrics shows the music it plays in their place: the samples come from the player's own audio sink
 * (through the real service), follow its clock and hold still while paused. A song with lyrics shows the lyrics.
 */
@RunWith(AndroidJUnit4::class)
class VisualizerUiTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val ps get() = player.uiState.value
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    @After fun stop() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
            }
        }
    }

    private fun play(path: String) {
        toBrowser()
        onUi { player.playCustomList(listOf(MusicFile(path.substringAfterLast('/'), path, false, 0, 0, local)), 0) }
        until(15_000, "playing $path") { ps.isPlaying && com.wing.folderplayer.data.source.SourceUris.parse(ps.currentMediaId)?.path == path }
        toPlayer()
    }

    private fun rms(s: FloatArray) = sqrt(s.fold(0.0) { a, v -> a + v * v } / s.size)

    @Test fun v01_noLyricsShowsTheMusicPlayingAndPauseHoldsIt() {
        // No lyric API: it finds lyrics for a song named "track" (another song's).
        val lyricPrefs = com.wing.folderplayer.data.prefs.LyricPreferences(Fx.ctx)
        val api = lyricPrefs.getLyricApiUrl()
        lyricPrefs.setLyricApiUrl("")
        try { noLyrics() } finally { lyricPrefs.setLyricApiUrl(api) }
    }

    private fun noLyrics() {
        // Not the song restored from the last run: its lyrics are kept with it (and may come from the lyric API).
        val restored = com.wing.folderplayer.data.prefs.PlaybackPreferences(Fx.ctx).getLastMediaId()
        val song = listOf("$fx/Album-B/track.flac", "$fx/Album-C/track-with-embedded-art.mp3")
            .first { com.wing.folderplayer.data.source.SourceRef(local, it).toUriString() != restored }
        play(song)
        try { until(10_000, "lyrics looked up") { ps.lyrics.isEmpty() } } catch (e: AssertionError) { throw AssertionError("lyrics ${ps.lyrics.take(2)} from \"${ps.lyricsSource}\" api \"${com.wing.folderplayer.data.prefs.LyricPreferences(Fx.ctx).getLyricApiUrl()}\"", e) }
        until(5_000, "visualiser in place of the lyrics") { exists("audio_visualizer") && !exists("lyrics_list") }
        val block = FloatArray(2_048)
        until(5_000, "samples from the sink") { AudioTap.read(block) && rms(block) > 0.001 }
        assertTrue("sound (rms ${rms(block)})", rms(block) > 0.001)

        // A tap changes the style (kept), round to the one it was.
        val prefs = com.wing.folderplayer.data.prefs.PlaybackPreferences(Fx.ctx)
        val start = com.wing.folderplayer.ui.player.VisualizerStyle.of(prefs.getVisualizerStyle())
        var style = start
        repeat(com.wing.folderplayer.ui.player.VisualizerStyle.values().size) {
            click("audio_visualizer")
            style = style.next()
            assertEquals("style kept", style.name, prefs.getVisualizerStyle())
            assertTrue("its name shown", text("visualizer_style") == str(style.label))
        }
        assertEquals(start, style)

        onUi { player.pauseLocal() }
        until(5_000, "paused") { !ps.isPlaying }
        Thread.sleep(500)
        val a = FloatArray(2_048).also { AudioTap.read(it) }
        Thread.sleep(300)
        val b = FloatArray(2_048).also { AudioTap.read(it) }
        assertArrayEquals("paused: the same picture", a, b, 0f)

        // Folded away: nothing is kept any more.
        click("btn_collapse_player")
        until(5_000, "player folded") { !playerOpen() }
        until(5_000, "tap off") { !AudioTap.active }
    }

    @Test fun v02_lyricsAreShownWhereThereAreSome() {
        play("$fx/Album-A/01 曲 #1+%.flac")
        until(10_000, "lyrics") { ps.lyrics.size > 1 && exists("lyrics_list") }
        assertFalse("no visualiser", exists("audio_visualizer"))
    }
}
