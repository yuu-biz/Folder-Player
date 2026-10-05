package com.wing.folderplayer.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.wing.folderplayer.R
import com.wing.folderplayer.cast.CastController
import kotlin.math.abs

/**
 * Folder image first; if it cannot be loaded the embedded picture; otherwise the placeholder.
 * [requestPx]: fixed request size. Views of the same picture that use the same size share one memory-cache entry,
 * and a view that is scaled (not resized) while it moves never requests the picture again.
 * [onShown]: the picture, the fallback or the placeholder is there (not called while loading).
 */
@Composable
fun CoverImage(
    primary: Any?,
    fallback: ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    tag: String = "player_cover",
    placeholderIconSize: Dp = 80.dp,
    requestPx: Int? = null,
    crossfade: Boolean = true,
    onShown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val shown by rememberUpdatedState(onShown)
    if (primary == null && fallback == null) {
        CoverPlaceholder(modifier, placeholderIconSize)
        LaunchedEffect(Unit) { shown?.invoke() }
        return
    }
    fun request(data: Any) = ImageRequest.Builder(context).data(data).crossfade(crossfade)
        .apply { if (requestPx != null) size(requestPx) }.build()
    SubcomposeAsyncImage(
        model = request(primary ?: fallback!!),
        contentDescription = stringResource(R.string.player_cover),
        contentScale = contentScale,
        modifier = modifier.testTag(tag),
        onSuccess = { shown?.invoke() },
        error = {
            if (fallback != null && primary != null) {
                SubcomposeAsyncImage(
                    model = request(fallback),
                    contentDescription = null,
                    contentScale = contentScale,
                    onSuccess = { shown?.invoke() },
                    error = {
                        CoverPlaceholder(Modifier.fillMaxSize(), placeholderIconSize)
                        LaunchedEffect(Unit) { shown?.invoke() }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CoverPlaceholder(Modifier.fillMaxSize(), placeholderIconSize)
                LaunchedEffect(Unit) { shown?.invoke() }
            }
        },
    )
}

/**
 * The full player's cover: the thumbnail the mini player shows (same request, so it is there at once) under the large
 * picture. The large one is requested only here and only once per cover ([PlayerTransition.largeLoadedFor]); the
 * blurred background waits for it and then takes it from the memory cache.
 */
@Composable
fun FullCover(uiState: PlayerUiState, transition: PlayerTransition, modifier: Modifier = Modifier) {
    val key = uiState.coverUri to uiState.coverFallback
    DisposableEffect(key) {
        transition.fullCoverReady = false
        onDispose { transition.fullCoverReady = false }
    }
    Box(modifier) {
        CoverImage(
            uiState.coverUri, uiState.coverFallback, Modifier.fillMaxSize(), tag = "player_cover_thumb",
            requestPx = transition.thumbPx, crossfade = false, onShown = { transition.fullCoverReady = true },
        )
        CoverImage(
            uiState.coverUri, uiState.coverFallback, Modifier.fillMaxSize(),
            requestPx = transition.largePx, onShown = { transition.largeLoadedFor = key },
        )
    }
}

@Composable
fun CoverPlaceholder(modifier: Modifier, iconSize: Dp = 80.dp) {
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF111111)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(iconSize), tint = Color.White.copy(alpha = 0.2f))
    }
}

/**
 * Player background: public-main gradient (default), blurred cover, or black.
 * The blurred picture is requested at [requestPx] (the full cover's size, from the memory cache) once [pictureAllowed].
 */
@Composable
fun PlayerBackground(
    style: String,
    cover: Any?,
    fallback: ByteArray?,
    requestPx: Int,
    pictureAllowed: Boolean,
    modifier: Modifier = Modifier,
) {
    when (style) {
        "BLACK" -> Box(modifier.fillMaxSize().background(Color.Black))
        "BLUR" -> Box(modifier.fillMaxSize().background(Color.Black)) {
            if ((cover != null || fallback != null) && pictureAllowed) {
                val blurMod = if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier
                CoverImage(cover, fallback, Modifier.fillMaxSize().then(blurMod), tag = "player_background", requestPx = requestPx)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.45f else 0.75f)))
            }
        }
        else -> Box(
            modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f), Color(0xFF0F0F0F)))
            )
        )
    }
}

/**
 * Seek bar touch: a tap seeks; a drag that starts sideways moves the thumb ([onPreview]) and seeks on release; a drag
 * that starts vertically is taken and ignored, so it neither seeks nor moves the player or opens the playlist.
 */
fun Modifier.seekGestures(onPreview: (Float) -> Unit, onSeek: (Float) -> Unit): Modifier = composed {
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    pointerInput(Unit) {
        fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        awaitEachGesture {
            val down = awaitFirstDown()
            var sideways = false
            val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                sideways = abs(over.x) >= abs(over.y)
            }
            if (start == null) {
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (up != null && !up.pressed && !up.isConsumed) {
                    up.consume()
                    seek(at(up.position.x))
                }
                return@awaitEachGesture
            }
            if (sideways) {
                var last = at(start.position.x)
                preview(last)
                val lifted = drag(start.id) { c ->
                    c.consume()
                    last = at(c.position.x)
                    preview(last)
                }
                if (lifted) seek(last)
            } else {
                drag(start.id) { it.consume() }
            }
        }
    }
}

/** Shown under the lyrics: provenance label, AI action, error. */
@Composable
fun LyricsFooter(uiState: PlayerUiState, onAiLyrics: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (uiState.lyricsSource.isNotEmpty()) {
            Text(
                if (uiState.lyricsSource.startsWith("AI")) stringResource(R.string.player_lyrics_ai_generated, uiState.lyricsSource)
                else stringResource(R.string.player_lyrics_source, uiState.lyricsSource) + if (!uiState.lyricsSynced) " · " + stringResource(R.string.player_lyrics_unsynced) else "",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.testTag("lyrics_source")
            )
        }
        if (uiState.lyricsRequestRunning) {
            CircularProgressIndicator(Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = { onAiLyrics(uiState.lyricsSource.startsWith("AI")) }, modifier = Modifier.testTag("btn_ai_lyrics")) {
                Text(
                    stringResource(if (uiState.lyricsSource.startsWith("AI")) R.string.player_lyrics_ai_regenerate else R.string.player_lyrics_ai_get),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
    uiState.lyricsError?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().testTag("lyrics_error"),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Translation line for lyric [index] (same index, same timestamp) if a translation is available. */
fun PlayerUiState.translationFor(index: Int): String? = translatedLyrics.getOrNull(index)?.text?.takeIf { it.isNotBlank() }

@Composable
fun PlaybackErrorBanner(uiState: PlayerUiState) {
    val e = uiState.playbackError ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(e, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("playback_error"))
    }
}

@Composable
fun AlbumInfoDialog(uiState: PlayerUiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var showArtistDetail by remember { mutableStateOf(false) }
    if (uiState.nfoNeedsOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNfoSaveState() },
            title = { Text(stringResource(R.string.player_nfo_replace_title)) },
            text = { Text(stringResource(R.string.player_nfo_replace_text)) },
            confirmButton = { TextButton(onClick = { viewModel.saveAlbumInfoToNfo(true) }, modifier = Modifier.testTag("btn_nfo_replace")) { Text(stringResource(R.string.common_replace)) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissNfoSaveState() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    AlertDialog(
        onDismissRequest = { showArtistDetail = false; onDismiss() },
        title = {
            Text(
                stringResource(if (showArtistDetail) R.string.player_artist_discovery else R.string.player_album_discovery),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (showArtistDetail) uiState.currentArtist else uiState.currentFolderName,
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val sourceLabel = when {
                    uiState.isFetchingAlbumInfo -> null
                    uiState.albumInfoFromNfo -> stringResource(R.string.player_info_from_nfo)
                    uiState.albumInfoAiModel != null -> stringResource(R.string.player_info_from_ai, uiState.albumInfoAiModel) +
                        if (uiState.albumInfoFromCache) " · " + stringResource(R.string.player_info_cached) else ""
                    else -> null
                }
                sourceLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.testTag("album_info_source")) }
                if (uiState.isFetchingAlbumInfo) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
                } else {
                    Text(
                        (if (showArtistDetail) uiState.artistInfo else uiState.albumInfo) ?: stringResource(R.string.player_no_info),
                        style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp, modifier = Modifier.testTag("album_info_text")
                    )
                }
                when {
                    uiState.nfoSaveResult == "SAVED" -> Text(stringResource(R.string.player_nfo_saved), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("nfo_result"))
                    uiState.nfoSaveResult?.startsWith("READ_ONLY") == true -> Text(stringResource(R.string.player_nfo_read_only), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("nfo_result"))
                    uiState.nfoSaveResult?.startsWith("FAILED") == true -> Text(stringResource(R.string.player_nfo_failed, uiState.nfoSaveResult.substringAfter(':')), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("nfo_result"))
                }
                if (!uiState.isFetchingAlbumInfo && uiState.albumInfoAiModel != null && !uiState.albumInfoCanSave && !uiState.albumInfoFromNfo) {
                    Text(stringResource(R.string.player_nfo_cannot_save), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                }
            }
        },
        dismissButton = null,
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.End) {
                    if (!showArtistDetail && !uiState.artistInfo.isNullOrBlank() && !uiState.isFetchingAlbumInfo) {
                        TextButton(onClick = { showArtistDetail = true }) { Text(stringResource(R.string.player_learn_artist)) }
                    }
                    if (!uiState.isFetchingAlbumInfo) {
                        TextButton(onClick = { viewModel.fetchAlbumInfo(forceRegenerate = true) }, modifier = Modifier.testTag("btn_regenerate")) {
                            Text(stringResource(R.string.player_regenerate))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End) {
                    if (uiState.albumInfoCanSave && !uiState.isFetchingAlbumInfo) {
                        TextButton(onClick = { viewModel.saveAlbumInfoToNfo(false) }, modifier = Modifier.testTag("btn_save_nfo")) { Text(stringResource(R.string.player_save_nfo)) }
                    }
                    TextButton(onClick = { showArtistDetail = false; onDismiss() }) { Text(stringResource(R.string.common_close)) }
                }
            }
        },
        containerColor = Color(0xFF1E1E1E),
        textContentColor = Color.White,
        titleContentColor = Color.White
    )
}

/** Cast icon (only when DLNA is enabled in Settings) with renderer picker and session controls. */
@Composable
fun CastAction(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val cast = remember { CastController.get(context) }
    if (!cast.enabled) return
    val state by cast.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true; if (state.active == null) cast.startDiscovery() }, modifier = Modifier.testTag("btn_cast")) {
        Icon(if (state.active != null) Icons.Default.CastConnected else Icons.Default.Cast, contentDescription = stringResource(R.string.player_cast),
            tint = if (state.active != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f))
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.player_cast)) },
        text = {
            Column {
                val active = state.active
                if (active != null) {
                    Text(stringResource(R.string.player_cast_to, active.name), fontWeight = FontWeight.Bold)
                    Text(state.title, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.player_cast_renderer_state, state.rendererState ?: stringResource(R.string.player_cast_unknown)) +
                            "\n" + stringResource(R.string.player_cast_last_command, state.lastCommand ?: "—") +
                            if (state.progressAvailable) "\n" + formatTime(state.positionMs) + " / " + (if (state.durationMs > 0) formatTime(state.durationMs) else "--:--")
                            else "\n" + stringResource(R.string.player_cast_no_progress),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cast_state")
                    )
                    Row {
                        IconButton(onClick = { cast.play() }) { Icon(Icons.Default.PlayArrow, null) }
                        IconButton(onClick = { cast.pause() }) { Icon(Icons.Default.Pause, null) }
                        IconButton(onClick = { cast.stop() }, modifier = Modifier.testTag("btn_cast_stop")) { Icon(Icons.Default.Stop, null) }
                    }
                } else {
                    if (state.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.renderers.isEmpty() && !state.discovering) Text(stringResource(R.string.player_cast_none))
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(state.renderers) { r ->
                            ListItem(
                                headlineContent = { Text(r.name) },
                                supportingContent = { Text(listOf(r.model, r.address).filter { it.isNotBlank() }.joinToString(" · ")) },
                                modifier = Modifier.clickable {
                                    val item = viewModel.currentCastItem()
                                    if (item != null) {
                                        viewModel.pauseLocal()
                                        cast.cast(r, item.first, item.second, item.third)
                                    }
                                }.testTag("renderer_${r.name}")
                            )
                        }
                    }
                    TextButton(onClick = { cast.startDiscovery() }) { Text(stringResource(R.string.common_retry)) }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_close)) } },
    )
}

/** The ViewModel uses fixed placeholders; they are translated here. */
@Composable
fun displayTitle(raw: String): String = when (raw) {
    PlayerTitles.NONE -> stringResource(R.string.player_no_song)
    PlayerTitles.LOADING, "Loading...", "Loading.." -> stringResource(R.string.player_loading)
    PlayerTitles.SWITCHING -> stringResource(R.string.player_switching)
    else -> raw
}

@Composable
fun timerLabel(type: TimerType, value: Int): String =
    if (type == TimerType.TIME) pluralStringResource(R.plurals.player_timer_minutes, value, value) else pluralStringResource(R.plurals.player_timer_songs_count, value, value)

object PlayerTitles {
    const val NONE = "No Song Playing"
    const val LOADING = "Loading.."
    const val SWITCHING = "Switching Track.."
}
