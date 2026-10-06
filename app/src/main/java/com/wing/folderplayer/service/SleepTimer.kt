package com.wing.folderplayer.service

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand

/**
 * Sleep timer of the playback, kept by [MusicService]: it lives as long as the playback does, with or without a
 * screen, and there is only one. TIME pauses at a deadline; SONGS pauses when the given number of songs has ended
 * (automatic moves to the next track). Both end when the queue ends. Screens start / cancel it with custom session
 * commands and show the state the session publishes in its extras ([state]). Like before, it is not kept when the app
 * process ends.
 *
 * A deadline that passes while playback is paused ends the timer without effect (nothing to pause).
 */
internal class SleepTimer(
    private val player: Player,
    private val handler: Handler,
    private val onChange: (Bundle) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private var type: String? = null
    /** [clock] time of a TIME timer's end. */
    private var deadline = 0L
    private var songsLeft = 0

    private val expire = Runnable { checkDeadline() }

    fun attach() = player.addListener(this)

    fun detach() {
        handler.removeCallbacks(expire)
        player.removeListener(this)
    }

    fun start(type: String?, value: Int) {
        handler.removeCallbacks(expire)
        if (value <= 0) { cancel(); return }
        when (type) {
            TYPE_TIME -> {
                this.type = TYPE_TIME
                deadline = clock() + value * MINUTE_MS
                songsLeft = 0
                handler.postDelayed(expire, value * MINUTE_MS)
            }
            TYPE_SONGS -> {
                this.type = TYPE_SONGS
                deadline = 0L
                songsLeft = value
            }
            else -> return
        }
        onChange(state())
    }

    fun cancel() {
        handler.removeCallbacks(expire)
        val wasActive = type != null
        type = null
        deadline = 0L
        songsLeft = 0
        if (wasActive) onChange(state())
    }

    fun state(): Bundle = Bundle().apply {
        putBoolean(KEY_ACTIVE, type != null)
        putString(KEY_TYPE, type)
        putLong(KEY_DEADLINE, deadline)
        putInt(KEY_SONGS_LEFT, songsLeft)
    }

    /** Handler delays stop while the device sleeps (only possible while paused): the deadline is what counts. */
    private fun checkDeadline() {
        if (type != TYPE_TIME) return
        val left = deadline - clock()
        if (left > 0) { handler.postDelayed(expire, left); return }
        player.pause()
        cancel()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Resumed after the deadline passed during a pause (and a device sleep delayed the check): it has run out.
        if (isPlaying && type == TYPE_TIME && clock() >= deadline) cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (type != TYPE_SONGS || reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
        songsLeft--
        if (songsLeft <= 0) {
            player.pause()
            cancel()
        } else onChange(state())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) cancel()
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val TYPE_TIME = "TIME"
        const val TYPE_SONGS = "SONGS"

        const val ACTION_START = "com.wing.folderplayer.sleep_timer.START"
        const val ACTION_CANCEL = "com.wing.folderplayer.sleep_timer.CANCEL"
        const val ACTION_GET = "com.wing.folderplayer.sleep_timer.GET"
        val COMMAND_START = SessionCommand(ACTION_START, Bundle.EMPTY)
        val COMMAND_CANCEL = SessionCommand(ACTION_CANCEL, Bundle.EMPTY)
        val COMMAND_GET = SessionCommand(ACTION_GET, Bundle.EMPTY)

        /** START arguments: [TYPE_TIME] (minutes) or [TYPE_SONGS] (songs). */
        const val ARG_TYPE = "type"
        const val ARG_VALUE = "value"

        /** State (session extras / command result). */
        const val KEY_ACTIVE = "sleep_timer_active"
        const val KEY_TYPE = "sleep_timer_type"
        const val KEY_DEADLINE = "sleep_timer_deadline_elapsed"
        const val KEY_SONGS_LEFT = "sleep_timer_songs_left"
    }
}
