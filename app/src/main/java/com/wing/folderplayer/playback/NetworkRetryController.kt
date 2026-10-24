package com.wing.folderplayer.playback

import android.os.Handler
import android.os.Looper
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

data class RetryStatus(val attempt: Int = 0, val pendingDelayMs: Long = 0, val gaveUp: Boolean = false, val lastErrorCode: Int = 0)

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
    private val clock: () -> Long = System::currentTimeMillis,
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

    override fun onPlayerError(error: PlaybackException) {
        if (!policy.isRetryable(error.errorCode) || !isNetworkItem(player.currentMediaItem)) {
            if (attempt > 0) PlaybackLog.record(PlaybackEventKind.RETRY_GAVE_UP, "after $attempt retries: ${error.errorCodeName} is not retried")
            _status.value = RetryStatus(attempt, 0, gaveUp = attempt > 0, lastErrorCode = error.errorCode)
            reset(keepStatus = true)
            return
        }
        val now = clock()
        if (attempt == 0) firstFailureAt = now
        attempt++
        val delay = policy.nextDelay(attempt, firstFailureAt, now)
        if (delay == null) {
            PlaybackLog.record(PlaybackEventKind.RETRY_GAVE_UP, "after ${attempt - 1} retries in ${(now - firstFailureAt) / 1000}s: ${error.errorCodeName}")
            _status.value = RetryStatus(attempt, 0, gaveUp = true, lastErrorCode = error.errorCode)
            reset(keepStatus = true)
            return
        }
        resumePosition = player.currentPosition
        resumeIndex = player.currentMediaItemIndex
        _status.value = RetryStatus(attempt, delay, false, error.errorCode)
        android.util.Log.w(TAG, "network error ${error.errorCodeName}; retry #$attempt in ${delay}ms")
        PlaybackLog.record(PlaybackEventKind.RETRY, "#$attempt in ${delay}ms after ${error.errorCodeName}")
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            player.seekTo(resumeIndex, resumePosition)
            player.prepare()
            player.play()
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
        if (attempt > 0) _status.value = _status.value.copy(pendingDelayMs = 0)
        reset(keepStatus = true)
    }

    private fun reset(keepStatus: Boolean = false) {
        attempt = 0
        firstFailureAt = 0
        if (!keepStatus) _status.value = RetryStatus()
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
