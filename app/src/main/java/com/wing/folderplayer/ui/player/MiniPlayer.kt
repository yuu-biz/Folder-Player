package com.wing.folderplayer.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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

/** Height of the mini player row (without the system navigation bar below it). */
val MiniPlayerHeight = 66.dp

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

fun PlayerUiState.mini() = MiniPlayerState(currentTitle, currentArtist, coverUri, coverFallback, isPlaying, isBuffering, playbackError, canSkipNext)

/**
 * Bottom bar for the current track. Tapping it opens the full player; the buttons only control playback.
 * It reads the same [PlayerUiState] (title mode, resolved cover) as the full player and never reloads anything itself.
 */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.miniState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cast by com.wing.folderplayer.cast.CastController.get(context).state.collectAsState()

    // Swiping sideways (either way) ends the session, but only while nothing plays or loads, here or on a renderer
    // (casting pauses the phone).
    val dismissable = !state.isPlaying && !state.isBuffering && !cast.sessionInProgress
    var dragX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }
    LaunchedEffect(dismissable) { if (!dismissable) dragX = 0f }
    val dismissLabel = stringResource(R.string.player_dismiss)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = dragX
                alpha = 1f - (abs(dragX) / widthPx).coerceIn(0f, 1f) * 0.8f
            }
            // Always takes sideways drags (so a swipe is never a tap that opens the player); when the session may not end
            // the bar only gives a little and springs back.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragX = if (dismissable) dragX + delta else (dragX + delta * 0.25f).coerceIn(-widthPx * 0.08f, widthPx * 0.08f)
                },
                onDragStopped = { velocity ->
                    val flung = abs(velocity) > 2_000f && sign(velocity) == sign(dragX)
                    if (dismissable && (abs(dragX) > widthPx * 0.35f || flung)) {
                        animate(dragX, sign(dragX) * widthPx, animationSpec = tween(150)) { v, _ -> dragX = v }
                        onDismiss()
                        // Still here: the session was not ended (e.g. a cast started meanwhile); never stay slid out.
                        kotlinx.coroutines.delay(300)
                        animate(dragX, 0f, animationSpec = tween(150)) { v, _ -> dragX = v }
                    } else {
                        animate(dragX, 0f, animationSpec = tween(150)) { v, _ -> dragX = v }
                    }
                },
            )
            .semantics {
                if (dismissable) customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
            },
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
                        .height(MiniPlayerHeight - 2.dp)
                        .clickable(onClickLabel = stringResource(R.string.player_open), role = Role.Button, onClick = onOpen)
                        .testTag("mini_player")
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                        CoverImage(state.coverUri, state.coverFallback, Modifier.fillMaxSize(), tag = "mini_cover", placeholderIconSize = 24.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayTitle(state.title),
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
                    Box(contentAlignment = Alignment.Center) {
                        IconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(48.dp).testTag("mini_play_pause")) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                            )
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

/** Thin progress line; only this part follows the playback position (and it only redraws). */
@Composable
private fun MiniProgress(viewModel: PlayerViewModel) {
    val fraction by viewModel.progressFraction.collectAsState()
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(2.dp).drawBehind { drawRect(color, size = Size(size.width * fraction, size.height)) })
}
