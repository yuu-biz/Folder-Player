package com.wing.folderplayer.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure retry policy (unit tested): exponential delay, capped, inside a total window, only for transient errors. */
class RetryPolicy(
    val windowMs: Long = 5 * 60 * 1000L,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
) {
    fun isRetryable(errorCode: Int): Boolean = errorCode in RETRYABLE

    /** Delay before attempt [attempt] (1-based), or null when the window starting at [firstFailureAt] is used up. */
    fun nextDelay(attempt: Int, firstFailureAt: Long, now: Long): Long? {
        val delay = minOf(maxDelayMs, baseDelayMs shl (attempt - 1).coerceIn(0, 20))
        return if (now + delay - firstFailureAt > windowMs) null else delay
    }

    companion object {
        val RETRYABLE = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}

/** Where a network retry stands; the player shows it while playback is expected to come back by itself. */
enum class RetryPhase {
    /** No retry has been needed (or the error was not one that is retried). */
    NONE,
    /** Waiting for the next attempt, until [RetryStatus.at]. */
    WAITING,
    /** The player is preparing again. */
    TRYING,
    /** Playing again since [RetryStatus.at]. */
    RECOVERED,
    /** The window is used up, or the error is not one that is retried. */
    GAVE_UP,
    /** Paused by the user, or the track changed, while a retry was pending. */
    CANCELLED,
}

/** [at] is in the controller's clock (by default [SystemClock.elapsedRealtime]); [pendingDelayMs] is set while WAITING. */
data class RetryStatus(
    val attempt: Int = 0,
    val pendingDelayMs: Long = 0,
    val lastErrorCode: Int = 0,
    val phase: RetryPhase = RetryPhase.NONE,
    val at: Long = 0,
) {
    val gaveUp: Boolean get() = phase == RetryPhase.GAVE_UP
}

/**
 * When a network source fails with a transient I/O error, re-prepare at the same position with growing delays
 * for up to 5 minutes. Authentication / not-found / decoding errors are not retried. A pending retry is cancelled
 * when the user pauses/stops or the track changes.
 *
 * While a retry waits the player is IDLE and holds no wake or Wi-Fi lock of its own, so with the screen off the delayed
 * retry might only run when the device wakes: [keepAwake] is true exactly while a retry is pending.
 */
class NetworkRetryController(
    private val player: Player,
    private val policy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val keepAwake: (Boolean) -> Unit = {},
    private val isNetworkItem: (MediaItem?) -> Boolean = ::isNetworkSource,
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var attempt = 0
    private var firstFailureAt = 0L
    private var pending: Runnable? = null
        set(value) {
            val changed = (field == null) != (value == null)
            field = value
            if (changed) keepAwake(value != null)
        }
    private var resumePosition = 0L
    private var resumeIndex = 0

    fun attach() = player.addListener(this)
    fun detach() { cancel("detached"); player.removeListener(this) }

    /** Runs the pending attempt now instead of at the end of its wait (the user asked; nothing happens when none is pending). */
    fun retryNow() {
        val r = pending ?: return
        handler.removeCallbacks(r)
        PlaybackLog.record(PlaybackEventKind.RETRY, "#$attempt now, asked by the user")
        r.run()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (!policy.isRetryable(error.errorCode) || !isNetworkItem(player.currentMediaItem)) {
            if (attempt > 0) PlaybackLog.record(PlaybackEventKind.RETRY_GAVE_UP, "after $attempt retries: ${error.errorCodeName} is not retried")
            _status.value = RetryStatus(attempt, 0, error.errorCode, if (attempt > 0) RetryPhase.GAVE_UP else RetryPhase.NONE, clock())
            reset()
            return
        }
        val now = clock()
        if (attempt == 0) firstFailureAt = now
        attempt++
        val delay = policy.nextDelay(attempt, firstFailureAt, now)
        if (delay == null) {
            PlaybackLog.record(PlaybackEventKind.RETRY_GAVE_UP, "after ${attempt - 1} retries in ${(now - firstFailureAt) / 1000}s: ${error.errorCodeName}")
            _status.value = RetryStatus(attempt, 0, error.errorCode, RetryPhase.GAVE_UP, now)
            reset()
            return
        }
        resumePosition = player.currentPosition
        resumeIndex = player.currentMediaItemIndex
        val play = player.playWhenReady
        _status.value = RetryStatus(attempt, delay, error.errorCode, RetryPhase.WAITING, now + delay)
        android.util.Log.w(TAG, "network error ${error.errorCodeName}; retry #$attempt in ${delay}ms")
        PlaybackLog.record(PlaybackEventKind.RETRY, "#$attempt in ${delay}ms after ${error.errorCodeName}")
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            _status.value = _status.value.copy(pendingDelayMs = 0, phase = RetryPhase.TRYING, at = clock())
            player.seekTo(resumeIndex, resumePosition)
            player.prepare()
            // A paused player (the restore of the last session prepares without playing) stays paused.
            if (play) player.play()
            // Released only now: from here on the player holds its own locks (WAKE_MODE_NETWORK).
            pending = null
        }
        pending = r
        handler.postDelayed(r, delay)
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && attempt > 0 && pending == null) {
            android.util.Log.i(TAG, "playback recovered after $attempt retries")
            PlaybackLog.record(PlaybackEventKind.RETRY_RECOVERED, "after $attempt retries")
            _status.value = RetryStatus(attempt, 0, _status.value.lastErrorCode, RetryPhase.RECOVERED, clock())
            reset()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            cancel("paused")
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (pending != null || attempt > 0) {
            // Our own seekTo(resumeIndex) does not change the item; any other transition is a user/track change.
            if (player.currentMediaItemIndex != resumeIndex || reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) cancel("track changed")
        }
    }

    private fun cancel(why: String) {
        pending?.let {
            handler.removeCallbacks(it)
            PlaybackLog.record(PlaybackEventKind.RETRY_CANCELLED, "$why, retry #$attempt was pending")
        }
        pending = null
        if (attempt > 0) _status.value = _status.value.copy(pendingDelayMs = 0, phase = RetryPhase.CANCELLED, at = clock())
        reset()
    }

    private fun reset() {
        attempt = 0
        firstFailureAt = 0
    }

    companion object {
        private const val TAG = "NetworkRetry"
        private val _status = MutableStateFlow(RetryStatus())
        val status: StateFlow<RetryStatus> = _status.asStateFlow()
    }
}

private fun isNetworkSource(item: MediaItem?): Boolean {
    val ref = SourceUris.parse(item?.localConfiguration?.uri?.toString() ?: item?.mediaId) ?: return false
    return SourceRegistry.get(ref.sourceId)?.isNetwork == true
}
