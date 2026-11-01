package com.wing.folderplayer.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import com.wing.folderplayer.playback.RetryPhase
import com.wing.folderplayer.playback.RetryStatus

/** What the player shows of the service's network retry. */
sealed interface RetryNotice {
    data class Waiting(val secondsLeft: Int) : RetryNotice
    object Trying : RetryNotice
    /** Shortly after playback came back. */
    object Reconnected : RetryNotice

    companion object {
        const val RECONNECTED_SHOWN_MS = 2_500L
    }
}

/** The notice at [now] (the retry controller's clock); null: nothing to show (no retry, or the error is shown instead). */
fun RetryStatus.notice(now: Long): RetryNotice? = when (phase) {
    RetryPhase.WAITING -> if (at > now) RetryNotice.Waiting(((at - now + 999) / 1000).toInt()) else RetryNotice.Trying
    RetryPhase.TRYING -> RetryNotice.Trying
    RetryPhase.RECOVERED -> RetryNotice.Reconnected.takeIf { now - at < RetryNotice.RECONNECTED_SHOWN_MS }
    RetryPhase.NONE, RetryPhase.GAVE_UP, RetryPhase.CANCELLED -> null
}

/** Follows the countdown and the end of "Reconnected" (both pass with time, not with a new status). */
@Composable
fun rememberRetryNotice(viewModel: PlayerViewModel): State<RetryNotice?> {
    val status by viewModel.retryStatus.collectAsState()
    return produceState(status.notice(SystemClock.elapsedRealtime()), status) {
        while (true) {
            value = status.notice(SystemClock.elapsedRealtime())
            if (value !is RetryNotice.Waiting && value !is RetryNotice.Reconnected) break
            kotlinx.coroutines.delay(250)
        }
    }
}

@Composable
private fun RetryNotice.text(): String = when (this) {
    is RetryNotice.Waiting -> stringResource(R.string.player_retry_waiting, secondsLeft)
    RetryNotice.Trying -> stringResource(R.string.player_retry_trying)
    RetryNotice.Reconnected -> stringResource(R.string.player_retry_reconnected)
}

/** The icon of a retry, pulsing while it waits or tries (still when the system's animations are off). */
@Composable
private fun RetryIcon(notice: RetryNotice, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val moving = notice !is RetryNotice.Reconnected && remember { ValueAnimator.areAnimatorsEnabled() }
    val pulse = if (moving) {
        rememberInfiniteTransition(label = "retry").animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "retryPulse",
        ).value
    } else 1f
    Icon(
        if (notice is RetryNotice.Reconnected) Icons.Default.CloudDone else Icons.Default.CloudSync,
        contentDescription = null, tint = tint,
        modifier = Modifier.size(size).graphicsLayer { alpha = pulse },
    )
}

/**
 * Below the title of the full player: a network retry while one is under way ("Retry now", "Stop"), "Reconnected" for a
 * moment after it worked, else the last playback error (also the one that ended the retries).
 */
@Composable
fun PlaybackStatusRow(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    val notice by rememberRetryNotice(viewModel)
    // The last notice stays on the row while it fades out.
    val last = remember { object { var notice: RetryNotice? = null } }
    notice?.let { last.notice = it }
    AnimatedVisibility(notice != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val n = last.notice ?: return@AnimatedVisibility
        Surface(
            color = Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("retry_status"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                RetryIcon(n, Color.White, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    n.text(), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp).testTag("retry_text"),
                )
                if (n is RetryNotice.Waiting) {
                    TextButton(onClick = { viewModel.retryNow() }, modifier = Modifier.testTag("btn_retry_now")) {
                        Text(stringResource(R.string.player_retry_now), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (n !is RetryNotice.Reconnected) {
                    TextButton(onClick = { viewModel.stopRetry() }, modifier = Modifier.testTag("btn_retry_stop")) {
                        Text(stringResource(R.string.player_retry_stop), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
    if (notice == null || notice == RetryNotice.Reconnected) PlaybackErrorBanner(uiState)
}

/** The mini player's second line while a retry is under way or has just worked. */
@Composable
fun MiniRetryLine(n: RetryNotice) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("mini_retry")) {
        RetryIcon(n, MaterialTheme.colorScheme.primary, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(if (n == RetryNotice.Reconnected) R.string.player_retry_reconnected else R.string.player_retry_trying),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
