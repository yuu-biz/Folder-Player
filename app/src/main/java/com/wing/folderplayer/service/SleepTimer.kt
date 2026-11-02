package com.wing.folderplayer.service

import android.content.Context
import androidx.core.content.edit
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import com.wing.folderplayer.playback.PlaybackEventKind
import com.wing.folderplayer.playback.PlaybackLog

/**
 * Sleep timer of the playback, kept by [MusicService]: it lives as long as the playback does, with or without a
 * screen, and there is only one. TIME pauses at a deadline; SONGS pauses when the given number of songs has ended
 * (automatic moves to the next track, a repeat of the same track, or the end of the queue). With "next folder" on, a
 * SONGS timer that has songs left goes on counting in the next folder. Screens start / cancel it with custom session
 * commands and show the state the session publishes in its extras ([state]).
 *
 * Options (remembered for the next start): "finish the song" turns a TIME timer at its deadline into "after this
 * song"; "fade out" lowers the player's volume over the last [FADE_MS] before the pause (of the deadline, or of the
 * last song when its length is known). The volume is back to full after the pause, on cancel or a new start, and
 * whenever playback is paused, so the next play is never silent. A DLNA cast plays on the renderer and is not faded.
 *
 * The timer is saved on every change and comes back when the service starts again after the process ended: a TIME
 * timer while its deadline (wall clock) is in the future, a SONGS timer within [RESTORE_SONGS_MS] of its last change.
 *
 * A deadline that passes while playback is paused ends the timer without effect (nothing to pause).
 */
internal class SleepTimer(
    private val player: Player,
    private val alarm: Alarm,
    private val store: Store,
    private val onChange: (Bundle) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : Player.Listener {
    /** The timer's single wake-up: each call replaces the one before, null clears it. */
    fun interface Alarm { fun set(delayMs: Long?, action: Runnable) }

    interface Store {
        fun load(): Saved
        fun save(saved: Saved)
    }

    data class Options(val finishSong: Boolean = false, val fade: Boolean = false)

    /** [wallDeadline] for TIME, [songsLeft] for SONGS; [savedAt] is the wall-clock time of the last change. */
    data class SavedTimer(val type: String, val wallDeadline: Long, val songsLeft: Int, val savedAt: Long)

    data class Saved(val options: Options = Options(), val timer: SavedTimer? = null)

    private sealed interface Countdown {
        /** Ends at [deadline], a [clock] time. */
        data class Time(val deadline: Long) : Countdown
        /** Ends when [left] more songs have ended. */
        data class Songs(val left: Int) : Countdown
    }

    private var countdown: Countdown? = null
    private var options = store.load().options
    /** The volume was lowered by the fade and must be put back. */
    private var faded = false

    // The deadline is acted on here only, not from inside the player's callbacks.
    private val wake = Runnable { if ((countdown as? Countdown.Time)?.let { it.deadline <= clock() } == true) timeIsUp() else update() }

    /** Starts listening and brings back a timer saved before the process ended. */
    fun attach() {
        player.addListener(this)
        val saved = store.load().timer ?: return
        countdown = when (saved.type) {
            TYPE_TIME -> (saved.wallDeadline - wallClock()).takeIf { it > 0 }?.let { Countdown.Time(clock() + it) }
            TYPE_SONGS -> Countdown.Songs(saved.songsLeft).takeIf { it.left > 0 && wallClock() - saved.savedAt < RESTORE_SONGS_MS }
            else -> null
        }
        if (countdown != null) PlaybackLog.record(PlaybackEventKind.SLEEP_TIMER, "brought back after a restart")
        changed()
    }

    fun detach() {
        alarm.set(null, wake)
        restoreVolume()
        player.removeListener(this)
    }

    fun start(type: String?, value: Int, options: Options) {
        if (value <= 0) { cancel(); return }
        countdown = when (type) {
            TYPE_TIME -> Countdown.Time(clock() + value * MINUTE_MS)
            TYPE_SONGS -> Countdown.Songs(value)
            else -> return
        }
        this.options = options
        restoreVolume()
        changed()
    }

    fun cancel() {
        val wasActive = countdown != null
        countdown = null
        if (wasActive) changed()
    }

    fun state(): Bundle = Bundle().apply {
        val c = countdown
        putBoolean(KEY_ACTIVE, c != null)
        putString(KEY_TYPE, when (c) { is Countdown.Time -> TYPE_TIME; is Countdown.Songs -> TYPE_SONGS; null -> null })
        putLong(KEY_DEADLINE, (c as? Countdown.Time)?.deadline ?: 0L)
        putInt(KEY_SONGS_LEFT, (c as? Countdown.Songs)?.left ?: 0)
        putBoolean(KEY_FINISH_SONG, options.finishSong)
        putBoolean(KEY_FADE, options.fade)
    }

    private fun changed() {
        update()
        val c = countdown
        store.save(Saved(options, when (c) {
            is Countdown.Time -> SavedTimer(TYPE_TIME, wallClock() + (c.deadline - clock()), 0, wallClock())
            is Countdown.Songs -> SavedTimer(TYPE_SONGS, 0L, c.left, wallClock())
            null -> null
        }))
        onChange(state())
    }

    /**
     * Sets the fade and the next wake-up (at once for a TIME timer that is due). Handler delays stop while the device sleeps
     * (only possible while paused): the deadline is what counts, not when the wake-up runs.
     */
    private fun update() {
        when (val c = countdown) {
            null -> { alarm.set(null, wake); restoreVolume() }
            is Countdown.Time -> {
                val left = c.deadline - clock()
                if (left <= 0) { alarm.set(0L, wake); return }
                val fading = options.fade && !options.finishSong
                fade(if (fading) left else null)
                alarm.set(if (!fading) left else if (left > FADE_MS) left - FADE_MS else minOf(left, FADE_STEP_MS), wake)
            }
            is Countdown.Songs -> {
                val left = if (options.fade && c.left == 1 && player.isPlaying) msLeftInSong() else null
                fade(left)
                alarm.set(left?.let { if (it > FADE_MS) minOf(it - FADE_MS, SONG_POLL_MS) else FADE_STEP_MS }, wake)
            }
        }
    }

    private fun timeIsUp() {
        if (options.finishSong && player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            PlaybackLog.record(PlaybackEventKind.SLEEP_TIMER, "time is up, finishing the song")
            countdown = Countdown.Songs(1)
            changed()
        } else finish("time is up, pausing")
    }

    private fun finish(why: String) {
        PlaybackLog.record(PlaybackEventKind.SLEEP_TIMER, why)
        // Ended before the pause: the player calls back (onIsPlayingChanged) from inside pause().
        countdown = null
        player.pause()
        changed()
    }

    /** Playing time left in the current song, or null while its length is not known (e.g. a stream). */
    private fun msLeftInSong(): Long? {
        val duration = player.duration
        if (duration == C.TIME_UNSET) return null
        return ((duration - player.currentPosition) / player.playbackParameters.speed).toLong().coerceAtLeast(0)
    }

    /** Lowers the volume with [msLeft] under [FADE_MS]; full volume otherwise and while not playing. */
    private fun fade(msLeft: Long?) {
        if (msLeft == null || msLeft >= FADE_MS || !player.isPlaying) { restoreVolume(); return }
        player.volume = msLeft.toFloat() / FADE_MS
        faded = true
    }

    private fun restoreVolume() {
        if (!faded) return
        player.volume = 1f
        faded = false
    }

    private fun songEnded(why: String) {
        val c = countdown as? Countdown.Songs ?: return
        if (c.left <= 1) finish(why) else { countdown = Countdown.Songs(c.left - 1); changed() }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val c = countdown
        // Resumed after the deadline passed during a pause (and a device sleep delayed the check): it has run out.
        if (isPlaying && c is Countdown.Time && clock() >= c.deadline) cancel() else update()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            songEnded("last song ended, pausing")
        } else update()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) update()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The last song of the queue ended (no transition follows). A TIME timer is about the clock: with "next folder"
        // on, the next folder starts at once and the timer must still stop it; if nothing plays at the deadline, it
        // ends unused.
        if (playbackState == Player.STATE_ENDED) songEnded("queue ended, timer done") else update()
    }

    /** Keeps the timer in its own preferences file. */
    class PrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)

        override fun load(): Saved = Saved(
            Options(prefs.getBoolean("finish_song", false), prefs.getBoolean("fade", false)),
            prefs.getString("type", null)?.let {
                SavedTimer(it, prefs.getLong("wall_deadline", 0L), prefs.getInt("songs_left", 0), prefs.getLong("saved_at", 0L))
            },
        )

        override fun save(saved: Saved) {
            prefs.edit {
                putBoolean("finish_song", saved.options.finishSong)
                putBoolean("fade", saved.options.fade)
                val t = saved.timer
                if (t == null) remove("type").remove("wall_deadline").remove("songs_left").remove("saved_at")
                else putString("type", t.type).putLong("wall_deadline", t.wallDeadline).putInt("songs_left", t.songsLeft).putLong("saved_at", t.savedAt)
            }
        }
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val FADE_MS = 30_000L
        private const val FADE_STEP_MS = 200L
        /** How often the end of the last song is checked before its fade (a seek moves it). */
        private const val SONG_POLL_MS = 5_000L
        const val RESTORE_SONGS_MS = 60 * MINUTE_MS
        const val TYPE_TIME = "TIME"
        const val TYPE_SONGS = "SONGS"

        fun handlerAlarm(handler: Handler) = Alarm { delayMs, action ->
            handler.removeCallbacks(action)
            if (delayMs != null) handler.postDelayed(action, delayMs)
        }

        const val ACTION_START = "com.wing.folderplayer.sleep_timer.START"
        const val ACTION_CANCEL = "com.wing.folderplayer.sleep_timer.CANCEL"
        const val ACTION_GET = "com.wing.folderplayer.sleep_timer.GET"
        // Lazy: JVM tests have no Bundle.EMPTY (android.jar stubs).
        val COMMAND_START by lazy { SessionCommand(ACTION_START, Bundle.EMPTY) }
        val COMMAND_CANCEL by lazy { SessionCommand(ACTION_CANCEL, Bundle.EMPTY) }
        val COMMAND_GET by lazy { SessionCommand(ACTION_GET, Bundle.EMPTY) }

        /** START arguments: [TYPE_TIME] (minutes) or [TYPE_SONGS] (songs), and the options. */
        const val ARG_TYPE = "type"
        const val ARG_VALUE = "value"
        const val ARG_FINISH_SONG = "finish_song"
        const val ARG_FADE = "fade"

        /** State (session extras / command result); the options are there also while no timer runs. */
        const val KEY_ACTIVE = "sleep_timer_active"
        const val KEY_TYPE = "sleep_timer_type"
        const val KEY_DEADLINE = "sleep_timer_deadline_elapsed"
        const val KEY_SONGS_LEFT = "sleep_timer_songs_left"
        const val KEY_FINISH_SONG = "sleep_timer_finish_song"
        const val KEY_FADE = "sleep_timer_fade"
    }
}
