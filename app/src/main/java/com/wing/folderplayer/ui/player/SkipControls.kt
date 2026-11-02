package com.wing.folderplayer.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import kotlinx.coroutines.delay

/** Back or forward by [seconds] (the setting), beside previous and next. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SkipButton(seconds: Int, forward: Boolean, onSkip: (Long) -> Unit, modifier: Modifier = Modifier, iconSize: Dp = 28.dp) {
    val label = pluralStringResource(if (forward) R.plurals.player_skip_forward else R.plurals.player_skip_back, seconds, seconds)
    IconButton(
        onClick = { onSkip(if (forward) seconds * 1000L else -seconds * 1000L) },
        modifier = modifier.semantics { contentDescription = label }.testTag(if (forward) "btn_skip_forward" else "btn_skip_back"),
    ) {
        SkipIcon(seconds, forward, Modifier.size(iconSize))
    }
}

/** Material's icon for 5, 10 and 30 s; for other steps its arrow with the number inside, drawn the same way. */
@Composable
private fun SkipIcon(seconds: Int, forward: Boolean, modifier: Modifier) {
    val icon = when (seconds) {
        5 -> if (forward) Icons.Default.Forward5 else Icons.Default.Replay5
        10 -> if (forward) Icons.Default.Forward10 else Icons.Default.Replay10
        30 -> if (forward) Icons.Default.Forward30 else Icons.Default.Replay30
        else -> null
    }
    if (icon != null) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Replay, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize().graphicsLayer { if (forward) scaleX = -1f })
        val size = with(LocalDensity.current) { (maxWidth * 0.3f).toSp() }
        Text(
            "$seconds", color = Color.White, fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = maxHeight * 0.1f),
        )
    }
}

/** Double taps on the cover: the side of the last one and the seconds skipped by the taps in a row. */
@Stable
class SkipTaps {
    var forward by mutableStateOf(false)
        private set
    var seconds by mutableIntStateOf(0)
        private set
    /** Counts the taps; each one shows the mark again for a moment. */
    var count by mutableIntStateOf(0)
        private set

    fun add(forward: Boolean, step: Int) {
        if (forward != this.forward) seconds = 0
        this.forward = forward
        seconds += step
        count++
    }

    fun clear() { seconds = 0 }
}

/** Double tap on the left half: back [step] seconds; on the right half: forward. A single tap does nothing. */
fun Modifier.doubleTapToSkip(taps: SkipTaps, step: () -> Int, onSkip: (Long) -> Unit): Modifier = composed {
    val currentStep = rememberUpdatedState(step)
    val skip = rememberUpdatedState(onSkip)
    pointerInput(taps) {
        detectTapGestures(onDoubleTap = { at ->
            val forward = at.x > size.width / 2f
            val n = currentStep.value()
            taps.add(forward, n)
            skip.value(if (forward) n * 1000L else -n * 1000L)
        })
    }
}

/** "−10 s" / "+10 s" on the side tapped; taps that follow within the moment it shows add up, as on YouTube. */
@Composable
fun BoxScope.SkipTapMark(taps: SkipTaps) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(taps.count) {
        if (taps.count == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(500)
        alpha.animateTo(0f, tween(300))
        taps.clear()
    }
    if (taps.seconds == 0) return
    Row(
        Modifier
            .align(if (taps.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(16.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("skip_tap_mark"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (taps.forward) Icons.Default.FastForward else Icons.Default.FastRewind, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            (if (taps.forward) "+" else "−") + stringResource(R.string.settings_seconds, taps.seconds),
            color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        )
    }
}

/** The full player's cover with its gestures: a sideways swipe for next / previous, a double tap to skip back / forward. */
@Composable
fun PlayerCover(uiState: PlayerUiState, viewModel: PlayerViewModel, transition: PlayerTransition) {
    val taps = remember { SkipTaps() }
    Box(Modifier.fillMaxSize()) {
        FullCover(
            uiState, transition,
            Modifier.fillMaxSize()
                .swipeToSkip(uiState.currentMediaId, onNext = { viewModel.next() }, onPrevious = { viewModel.previous() })
                .doubleTapToSkip(taps, { uiState.skipSeconds }) { viewModel.skipBy(it) },
        )
        SkipTapMark(taps)
        TrackNumberMark(viewModel, uiState.playlist.size)
    }
}

/**
 * "4 / 12" at the foot of the cover for a moment after the track changes: songs of one album share the picture, and
 * the number tells that the song moved on. Not shown when the player opens.
 */
@Composable
private fun BoxScope.TrackNumberMark(viewModel: PlayerViewModel, count: Int) {
    val position by viewModel.queuePosition.collectAsState()
    var shownFor by remember { mutableIntStateOf(position) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(position) {
        if (position < 0 || position == shownFor) return@LaunchedEffect
        shownFor = position
        alpha.animateTo(1f, tween(150))
        delay(1200)
        alpha.animateTo(0f, tween(400))
    }
    if (count < 2 || shownFor < 0) return
    Text(
        "${shownFor + 1} / $count",
        color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("track_number_mark"),
    )
}
