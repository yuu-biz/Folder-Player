package com.wing.folderplayer.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import kotlin.math.abs
import kotlin.math.sign

/** Height of the mini player row (without the system navigation bar below it) at the normal font size. */
val MiniPlayerHeight = 66.dp

/**
 * Height of the mini player row at the font size in use: two lines of text (title, artist) must fit, so the bar grows
 * with a large font (by half of the extra size, at most 1.5 times). Everything that reserves the bar's place asks here.
 */
@Composable
fun miniPlayerHeight(): androidx.compose.ui.unit.Dp {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    return MiniPlayerHeight * (1f + (fontScale - 1f) * 0.5f).coerceIn(1f, 1.5f)
}

/** What the mini player shows; the position is left out so it does not recompose every second while playing. */
data class MiniPlayerState(
    val title: String,
    val artist: String,
    val coverUri: Any?,
    val coverFallback: ByteArray?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val error: String?,
    val canSkipNext: Boolean,
)

fun PlayerUiState.mini() =MiniPlayerState(currentTitle, currentArtist, coverUri, coverFallback, isPlaying, isBuffering, playbackError, canSkipNext)

/**
 * Bottom bar for the current track. Tapping it or dragging it up opens the full player; the buttons only control
 * playback. It reads the same [PlayerUiState] (title mode, resolved cover) as the full player and never reloads anything
 * itself. It does not move while the player opens: its texts and buttons fade out and the full player's cover takes
 * over from its cover (same thumbnail request).
 */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, transition: PlayerTransition, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val barHeight = miniPlayerHeight()
    val state by viewModel.miniState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cast by com.wing.folderplayer.cast.CastController.get(context).state.collectAsState()
    val sheet = transition.sheet

    // Swiping sideways (either way) ends the session, but only while nothing plays or loads, here or on a renderer
    // (casting pauses the phone).
    val dismissable = !state.isPlaying && !state.isBuffering && !cast.sessionInProgress
    var dragX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }
    LaunchedEffect(dismissable) { if (!dismissable) dragX = 0f }
    val dismissLabel = stringResource(R.string.player_dismiss)
    val playLabel = stringResource(R.string.player_play)
    val pauseLabel = stringResource(R.string.player_pause)
    val direction by rememberTrackDirection(viewModel)
    // While the full player is (partly) in front, the bar is not reachable (TalkBack, tests).
    val covered by remember(sheet) { derivedStateOf { sheet.isOpen } }

    val scope = rememberCoroutineScope()
    val canDismiss by rememberUpdatedState(dismissable)
    val dismiss by rememberUpdatedState(onDismiss)
    var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            // Before the layer: the bar's place without the sideways swipe.
            .onGloballyPositioned { transition.miniTop = it.positionInRoot().y }
            // Both gestures sit outside the layer that slides the bar, so the finger is measured on a still frame, and
            // each claims only its own axis: up opens the full player (following the finger), sideways is the swipe.
            .playerSheetDrag(sheet)
            // Always takes sideways drags (so a swipe is never a tap that opens the player); when the session may not end
            // the bar only gives a little and springs back.
            .horizontalSwipe(
                onStart = { settle?.cancel() },
                onDelta = { delta ->
                    dragX = if (canDismiss) dragX + delta else (dragX + delta * 0.25f).coerceIn(-widthPx * 0.08f, widthPx * 0.08f)
                },
                onStop = { velocity ->
                    settle = scope.launch {
                        val flung = abs(velocity) > 2_000f && sign(velocity) == sign(dragX)
                        if (canDismiss && (abs(dragX) > widthPx * 0.35f || flung)) {
                            animate(dragX, sign(dragX) * widthPx, animationSpec = tween(150)) { v, _ -> dragX = v }
                            dismiss()
                            // Still here: the session was not ended (e.g. a cast started meanwhile); never stay slid out.
                            kotlinx.coroutines.delay(300)
                            animate(dragX, 0f, animationSpec = tween(150)) { v, _ -> dragX = v }
                        } else {
                            animate(dragX, 0f, animationSpec = tween(150)) { v, _ -> dragX = v }
                        }
                    }
                },
            )
            .graphicsLayer {
                translationX = dragX
                alpha = (1f - (abs(dragX) / widthPx).coerceIn(0f, 1f) * 0.8f) * transition.miniAlpha()
            }
            .semantics {
                if (dismissable && !covered) customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
            }
            // Last in the chain: inserting it must not shift (and so restart) the gesture modifiers above while a
            // finger is opening the player.
            .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        // The bar is the bottom-most element: its background runs under the navigation bar, its content stays above it
        // and clear of a side cutout (landscape).
        Column(Modifier.navigationBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            MiniProgress(viewModel)
            BoxWithConstraints {
                // Next only where it fits without squeezing the title.
                val showNext = maxWidth >= 360.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight - 2.dp)
                        .clickable(onClickLabel = stringResource(R.string.player_open), role = Role.Button, onClick = onOpen)
                        .testTag("mini_player")
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(MiniCoverSize)
                            .onGloballyPositioned { if (dragX == 0f) transition.miniCover = Rect(it.positionInRoot(), it.size.toSize()) }
                            // Hidden once the full player's cover (the same picture) is there and moving.
                            .graphicsLayer { alpha = if (transition.fullCoverShown()) 0f else 1f }
                            .coverChange(state.coverUri to state.coverFallback) { direction }
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        CoverImage(
                            state.coverUri, state.coverFallback, Modifier.fillMaxSize(), tag = "mini_cover", placeholderIconSize = 24.dp,
                            requestPx = transition.thumbPx,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // A new title slides in; the line under it follows the current state.
                    TrackText(state.title, direction, Modifier.weight(1f)) { title ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                displayTitle(title),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("mini_title"),
                            )
                            val error = state.error
                            if (error != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mini_error"))
                                }
                            } else if (state.artist.isNotBlank()) {
                                Text(state.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mini_artist"))
                            }
                        }
                    }
                    Box(contentAlignment = Alignment.Center) {
                        IconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(48.dp).testTag("mini_play_pause")) {
                            PlayPauseIcon(state.isPlaying, { if (it) pauseLabel else playLabel }, LocalContentColor.current)
                        }
                        // Loading is shown around the button, not as "paused".
                        if (state.isBuffering) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(40.dp).testTag("mini_buffering"))
                        }
                    }
                    if (showNext) {
                        IconButton(onClick = { viewModel.next() }, enabled = state.canSkipNext, modifier = Modifier.size(48.dp).testTag("mini_next")) {
                            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                    }
                }
            }
        }
    }
}

/** Sideways drag, claimed only once the finger has moved sideways by the touch slop (vertical moves are left alone). */
private fun Modifier.horizontalSwipe(onStart: () -> Unit, onDelta: (Float) -> Unit, onStop: (velocity: Float) -> Unit): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val stop by rememberUpdatedState(onStop)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var over = 0f
            val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, o ->
                change.consume()
                over = o
            } ?: return@awaitEachGesture
            start()
            val velocity = ReleaseVelocity()
            velocity.moved(first)
            delta(over)
            // Called for sideways moves only; a pause before the lift is no fling (see ReleaseVelocity).
            val lifted = horizontalDrag(first.id) { change ->
                delta(change.positionChange().x)
                velocity.moved(change)
                change.consume()
            }
            val upAt = if (lifted) currentEvent.changes.firstOrNull { !it.pressed }?.uptimeMillis else null
            stop(velocity.atRelease(upAt).x)
        }
    }
}

/** Thin progress line; only this part follows the playback position (and it only redraws). */
@Composable
private fun MiniProgress(viewModel: PlayerViewModel) {
    val fraction by viewModel.progressFraction.collectAsState()
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(2.dp).drawBehind { drawRect(color, size = Size(size.width * fraction, size.height)) })
}
