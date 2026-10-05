package com.wing.folderplayer.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Small motions on a track change: the cover and the title move a little sideways in the direction of the change
 * (next: from the right, previous: from the left), play / pause swaps with a short scale and fade.
 */

/** +1 when the queue moved forward (or the direction is unknown), -1 when it moved back. */
@Composable
fun rememberTrackDirection(viewModel: PlayerViewModel): State<Int> {
    val position by viewModel.queuePosition.collectAsState()
    val direction = remember { mutableIntStateOf(1) }
    var last by remember { mutableIntStateOf(position) }
    LaunchedEffect(position) {
        if (position >= 0 && last >= 0 && position != last) direction.intValue = if (position > last) 1 else -1
        last = position
    }
    return direction
}

/** The new picture comes in from the side by a short distance; the old one is simply replaced (one image view). */
fun Modifier.coverChange(key: Any?, direction: () -> Int): Modifier = composed {
    val offset = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key == shown) return@LaunchedEffect
        shown = key
        offset.snapTo(direction().toFloat())
        offset.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        val o = offset.value
        translationX = o * 28.dp.toPx()
        alpha = 1f - abs(o) * 0.5f
    }
}

/** Track texts (title, artist): the new text slides in from the side of the change while the old one fades. */
@Composable
fun <T> TrackText(target: T, direction: Int, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        transitionSpec = { trackTextTransform(direction) },
        label = "trackText",
        modifier = modifier,
    ) { content(it) }
}

private fun trackTextTransform(direction: Int): ContentTransform =
    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> direction * w / 8 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
        (slideOutHorizontally(tween(200)) { w -> -direction * w / 10 } + fadeOut(tween(140)))

/** Play / pause icon that swaps with a short scale and fade. */
@Composable
fun PlayPauseIcon(isPlaying: Boolean, contentDescription: (playing: Boolean) -> String?, tint: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = 0.6f))
        },
        label = "playPause",
    ) { playing ->
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = contentDescription(playing), tint = tint, modifier = modifier)
    }
}
