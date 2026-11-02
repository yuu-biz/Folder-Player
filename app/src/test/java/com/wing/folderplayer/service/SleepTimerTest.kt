package com.wing.folderplayer.service

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SleepTimerTest {
    private var now = 0L
    private var wallBase = 1_700_000_000_000L
    private var alarmAt: Long? = null
    private var alarmAction: Runnable? = null
    private val store = object : SleepTimer.Store {
        var saved = SleepTimer.Saved()
        override fun load() = saved
        override fun save(saved: SleepTimer.Saved) { this.saved = saved }
    }

    private var playing = true
    private var duration = C.TIME_UNSET
    private var position = 0L
    private var volume = 1f
    private var pauses = 0
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "isPlaying", "getPlayWhenReady" -> playing
            "getDuration" -> duration
            "getCurrentPosition" -> position
            "getPlaybackState" -> Player.STATE_READY
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getVolume" -> volume
            "setVolume" -> { volume = args[0] as Float; null }
            "pause" -> { pauses++; playing = false; null }
            else -> null
        }
    } as Player

    private fun timer() = SleepTimer(
        player,
        { delay, action -> alarmAt = delay?.let { now + it }; alarmAction = action },
        store,
        onChange = {},
        clock = { now },
        wallClock = { wallBase + now },
    ).also { it.attach() }

    /** Moves the clock, running the alarm whenever it is due on the way. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val at = alarmAt ?: break
            if (at > end) break
            now = at
            alarmAt = null
            alarmAction!!.run()
        }
        now = end
    }

    private fun SleepTimer.minutes(n: Int, finishSong: Boolean = false, fade: Boolean = false) =
        start(SleepTimer.TYPE_TIME, n, SleepTimer.Options(finishSong, fade))

    @Test fun finishSongWaitsForTheEndOfTheSongAfterTheDeadline() {
        val t = timer()
        t.minutes(1, finishSong = true)
        advance(61_000)
        assertEquals("still playing the song at the deadline", 0, pauses)
        assertEquals(SleepTimer.TYPE_SONGS, store.saved.timer?.type)
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, pauses)
        assertNull("done", store.saved.timer)
    }

    @Test fun deadlineWhilePausedEndsWithoutEffectAlsoWithFinishSong() {
        val t = timer()
        t.minutes(1, finishSong = true)
        playing = false
        t.onIsPlayingChanged(false)
        advance(61_000)
        assertNull(store.saved.timer)
        val before = pauses
        playing = true
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals("a later song end does not pause", before, pauses)
    }

    @Test fun fadeLowersTheVolumeBeforeTheDeadlineAndPutsItBack() {
        val t = timer()
        t.minutes(1, fade = true)
        advance(29_000)
        assertEquals("full volume before the last 30 s", 1f, volume)
        advance(16_000)
        assertEquals(0.5f, volume, 0.02f)
        advance(15_000)
        assertEquals(1, pauses)
        assertEquals("full volume for the next play", 1f, volume)
    }

    @Test fun pauseOrCancelDuringTheFadeGivesFullVolume() {
        val t = timer()
        t.minutes(1, fade = true)
        advance(45_000)
        assertTrue(volume < 0.6f)
        playing = false
        t.onIsPlayingChanged(false)
        assertEquals("paused by the user", 1f, volume)
        playing = true
        t.onIsPlayingChanged(true)
        assertEquals("resumed: the fade goes on where it is", 0.5f, volume, 0.02f)
        t.cancel()
        assertEquals(1f, volume)
        advance(60_000)
        assertEquals(0, pauses)
    }

    @Test fun lastSongFadesOutWhenItsLengthIsKnown() {
        duration = 100_000
        position = 80_000
        val t = timer()
        t.start(SleepTimer.TYPE_SONGS, 1, SleepTimer.Options(fade = true))
        assertEquals(20f / 30f, volume, 0.02f)
        position = 95_000
        advance(200)
        assertEquals(5f / 30f, volume, 0.02f)
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, pauses)
        assertEquals(1f, volume)
    }

    @Test fun songsTimerCountsRepeatsAndGoesOnAfterTheEndOfTheQueue() {
        val t = timer()
        t.start(SleepTimer.TYPE_SONGS, 3, SleepTimer.Options())
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals(2, store.saved.timer?.songsLeft)
        t.onPlaybackStateChanged(Player.STATE_ENDED) // the queue ended; "next folder" may start another one
        assertEquals(1, store.saved.timer?.songsLeft)
        assertEquals(0, pauses)
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals("a skip is not a song played", 1, store.saved.timer?.songsLeft)
        t.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, pauses)
    }

    @Test fun timerComesBackAfterTheProcessEndedWhileItsDeadlineIsAhead() {
        timer().minutes(30, fade = true)
        // A new process: the elapsed clock starts again, the wall clock went on by 10 minutes.
        wallBase += 10 * 60_000L
        now = 0
        alarmAt = null
        timer()
        assertEquals(SleepTimer.Options(fade = true), store.saved.options)
        advance(19 * 60_000L)
        assertEquals(0, pauses)
        advance(60_000L)
        assertEquals("paused at the original deadline", 1, pauses)
        assertNull(store.saved.timer)
    }

    @Test fun timerPastItsDeadlineIsNotBroughtBack() {
        timer().minutes(30)
        wallBase += 31 * 60_000L
        now = 0
        alarmAt = null
        timer()
        assertNull(store.saved.timer)
        assertNull(alarmAt)
        assertEquals(0, pauses)
    }

    @Test fun songsTimerComesBackOnlyShortlyAfter() {
        timer().start(SleepTimer.TYPE_SONGS, 2, SleepTimer.Options())
        wallBase += 10 * 60_000L
        timer()
        assertEquals(2, store.saved.timer?.songsLeft)
        wallBase += SleepTimer.RESTORE_SONGS_MS
        timer()
        assertNull("too old: a timer of last night", store.saved.timer)
    }
}
