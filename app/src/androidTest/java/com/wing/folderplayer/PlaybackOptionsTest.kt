package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.playback.ChannelSwapAudioProcessor
import com.wing.folderplayer.service.MusicService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.0: the playback speed reaches the player at once (twice as fast: the position moves twice as far) and is kept;
 * left / right swapped is kept and playback goes on through the swapping audio processor. The swap itself:
 * `ChannelSwapAudioProcessorTest` (JVM).
 */
@RunWith(AndroidJUnit4::class)
class PlaybackOptionsTest : ServiceTestBase() {
    @Test fun speedAndSwappedChannelsApplyAndAreKept() {
        try {
            main { vm.playFolder(SourceRef(local, "${Fx.FX}/Long"), null) }
            waitFor(15_000, "playing") { state.isPlaying && state.currentPosition > 1_000 }
            main { vm.setPlaybackSpeed(2f); vm.setSwapChannels(true) }
            waitFor(5_000, "speed in the player") { main { MusicService.current?.playbackSpeedForTest } == 2f }
            assertTrue("swap on", ChannelSwapAudioProcessor.enabled)
            Thread.sleep(1_500)
            val p0 = state.currentPosition
            Thread.sleep(4_000)
            val moved = state.currentPosition - p0
            Fx.log("2x: moved $moved ms in 4 s")
            assertTrue("twice as fast: $moved ms in 4 s", moved in 6_500..9_500)
            assertTrue("still playing with the channels swapped", state.isPlaying)
            val prefs = PlaybackPreferences(Fx.ctx)
            assertEquals(2f, prefs.getPlaybackSpeed())
            assertTrue(prefs.getSwapChannels())
            assertEquals(2f, state.playbackSpeed)
        } finally {
            main { vm.setPlaybackSpeed(1f); vm.setSwapChannels(false); vm.playPause() }
        }
        waitFor(5_000, "normal speed again") { main { MusicService.current?.playbackSpeedForTest } == 1f }
    }
}
