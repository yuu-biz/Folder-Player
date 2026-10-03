package com.wing.folderplayer.ui.player

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Height of the mini player row (without the system navigation bar below it). */
val MiniPlayerHeight = 66.dp

/** What the mini player shows; the position is left out so it does not recompose every second while playing. */
private data class MiniState(
    val title: String,
    val artist: String,
    val coverUri: Any?,
    val coverFallback: ByteArray?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val error: String?,
)

private fun PlayerUiState.mini() = MiniState(currentTitle, currentArtist, coverUri, coverFallback, isPlaying, isBuffering, playbackError)

/**
 * Bottom bar for the current track. Tapping it opens the full player; the buttons only control playback.
 * It reads the same [PlayerUiState] (title mode, resolved cover) as the full player and never reloads anything itself.
 */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by remember(viewModel) { viewModel.uiState.map { it.mini() }.distinctUntilChanged() }
        .collectAsState(viewModel.uiState.value.mini())

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
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
                        IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(48.dp).testTag("mini_next")) {
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
    val fraction by remember(viewModel) {
        viewModel.uiState.map { if (it.duration > 1) (it.currentPosition.toFloat() / it.duration).coerceIn(0f, 1f) else 0f }.distinctUntilChanged()
    }.collectAsState(0f)
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(2.dp).drawBehind { drawRect(color, size = Size(size.width * fraction, size.height)) })
}
