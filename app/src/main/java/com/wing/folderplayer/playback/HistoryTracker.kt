package com.wing.folderplayer.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.wing.folderplayer.data.history.History
import com.wing.folderplayer.data.history.HistoryEntry
import com.wing.folderplayer.data.repo.PlayerRepository

/**
 * When the current item counts as played: after [thresholdMs] of playing (pauses and seeks do not reset it), or when it
 * plays to its end first (a song shorter than that). Counted once per time an item becomes the current one ([begin]);
 * each call returns true exactly when the item counts. Times come from a monotonic clock.
 */
class PlayCounter(private val thresholdMs: Long = THRESHOLD_MS) {
    /** The current item, not counted yet; null without an item or once it has counted. */
    private var listening: Listening? = null

    private data class Listening(val playedMs: Long, val since: Long?) {
        fun total(now: Long) = playedMs + (since?.let { now - it } ?: 0)
    }

    /** A new item is current ([playing] or not); true when the one before counts ([previousEnded]: it played to its end). */
    fun begin(now: Long, playing: Boolean, previousEnded: Boolean): Boolean {
        val counts = settle(now, ended = previousEnded)
        listening = Listening(0, if (playing) now else null)
        return counts
    }

    fun setPlaying(playing: Boolean, now: Long): Boolean {
        val l = listening ?: return false
        if (settle(now, ended = false)) return true
        listening = if (playing) l.copy(since = l.since ?: now) else Listening(l.total(now), null)
        return false
    }

    /** The current item played to its end (the end of the queue). */
    fun end(now: Long): Boolean = settle(now, ended = true)

    /** The wait set by [dueInMs] is over. */
    fun tick(now: Long): Boolean = settle(now, ended = false)

    /** How long the current item has to go on playing to count; null while it is paused or has counted. */
    fun dueInMs(now: Long): Long? = listening?.takeIf { it.since != null }?.let { (thresholdMs - it.total(now)).coerceAtLeast(0) }

    private fun settle(now: Long, ended: Boolean): Boolean {
        val l = listening ?: return false
        if (!ended && l.total(now) < thresholdMs) return false
        listening = null
        return true
    }

    companion object {
        const val THRESHOLD_MS = 10_000L
    }
}

/**
 * Records the songs the service's player plays in the [History] (see [PlayCounter] for when one counts), with or
 * without a screen. A CUE track is recorded as its CUE sheet. Every change of the current item (a repeat of the same
 * song too) starts a new count.
 */
class HistoryTracker(
    private val player: Player,
    private val record: (HistoryEntry) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private val counter = PlayCounter()
    private val handler = Handler(player.applicationLooper)
    private val due = Runnable { if (counter.tick(clock())) entry?.let(record); schedule() }

    /** What the current item records, as known while it is current (the player names the next one at a track change). */
    private var entry: HistoryEntry? = null

    fun attach() = player.addListener(this)

    fun detach() {
        handler.removeCallbacks(due)
        player.removeListener(this)
    }

    private fun schedule() {
        handler.removeCallbacks(due)
        counter.dueInMs(clock())?.let { handler.postDelayed(due, it) }
    }

    private fun entryOf(item: MediaItem?, metadata: MediaMetadata): HistoryEntry? {
        item ?: return null
        val extras = item.mediaMetadata.extras
        return History.entryOf(
            item.mediaId, extras?.getString(PlayerRepository.EXTRA_CUE_PATH),
            metadata.title, metadata.artist, metadata.albumTitle,
            durationMs = player.duration.takeIf { it > 0 } ?: 0, size = extras?.getLong("file_size") ?: 0,
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = entry
        val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (counter.begin(clock(), player.isPlaying, ended)) previous?.let(record)
        entry = entryOf(mediaItem, mediaItem?.mediaMetadata ?: MediaMetadata.EMPTY)
        schedule()
    }

    // After the single callbacks: the tags read from the file, and the length once the item is prepared.
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) entry = entryOf(player.currentMediaItem, player.mediaMetadata)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (counter.setPlaying(isPlaying, clock())) entry?.let(record)
        schedule()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && counter.end(clock())) entry?.let(record)
        schedule()
    }
}
