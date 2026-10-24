package com.wing.folderplayer.ui.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
import com.wing.folderplayer.data.artwork.SourceImageFetcher
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
    // Every player surface asks for the player artwork tier: one cached file (up to 2048 px) shared with the notification.
    fun request(data: Any) = ImageRequest.Builder(context).data(data).crossfade(crossfade)
        .setParameter(SourceImageFetcher.TIER_PARAM, SourceImageFetcher.TIER_ARTWORK)
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
    // Which cover the thumbnail shows. A memory-cache hit can report "shown" before this composition's effects run,
    // so readiness is derived from the shown cover rather than set and reset by events.
    var thumbShownFor by remember { mutableStateOf<Any?>(null) }
    val ready = thumbShownFor == key
    SideEffect { transition.fullCoverReady = ready }
    DisposableEffect(transition) { onDispose { transition.fullCoverReady = false } }
    Box(modifier) {
        CoverImage(
            uiState.coverUri, uiState.coverFallback, Modifier.fillMaxSize(), tag = "player_cover_thumb",
            requestPx = transition.thumbPx, crossfade = false, onShown = { thumbShownFor = key },
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

/** How the seek bar is being moved by the finger: [NORMAL] follows it across the whole track, [FINE] at [FineSeek.SPAN_MS] per bar width. */
enum class ScrubMode { NORMAL, FINE }

/** The fine drag of the seek bar (a press held still, then moved): the position moves by time per bar width, not by track share. */
object FineSeek {
    /** One bar width of a fine drag: half a minute... */
    const val SPAN_MS = 30_000L
    /** ...but never more than a quarter of the track (a short track would barely move otherwise). */
    private const val MAX_SHARE = 0.25f

    /** Position (0..1) after moving [dxShare] of the bar width from [anchor], for a track of [durationMs]. */
    fun position(anchor: Float, dxShare: Float, durationMs: Long): Float {
        val share = if (durationMs > 0) minOf(SPAN_MS.toFloat() / durationMs, MAX_SHARE) else MAX_SHARE
        return (anchor + dxShare * share).coerceIn(0f, 1f)
    }
}

/**
 * Seek bar touch: a tap seeks; a drag that starts sideways moves the thumb ([onPreview]) and seeks on release; a drag
 * that starts vertically is taken and ignored, so it neither seeks nor moves the player or opens the playlist. A press
 * held still for the long-press time starts a fine drag: the thumb stays where it was ([position]) and moves
 * [FineSeek.SPAN_MS] per bar width, for an exact place; let go without moving and nothing is seeked. [onScrub] says
 * which drag is going on (null: none), so the layouts keep the thumb from following playback meanwhile.
 */
fun Modifier.seekGestures(
    durationMs: Long,
    position: () -> Float,
    onPreview: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onScrub: (ScrubMode?) -> Unit = {},
): Modifier = composed {
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val now by rememberUpdatedState(position)
    val duration by rememberUpdatedState(durationMs)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(Unit) {
        fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        awaitEachGesture {
            val down = awaitFirstDown()
            var sideways = false
            var start: androidx.compose.ui.input.pointer.PointerInputChange? = null
            val heldStill = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                    change.consume()
                    sideways = abs(over.x) >= abs(over.y)
                }
            } == null
            if (heldStill) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                val anchor = now()
                val anchorX = currentEvent.changes.firstOrNull { it.id == down.id }?.position?.x ?: down.position.x
                var last = anchor
                var moved = false
                scrub(ScrubMode.FINE)
                preview(last)
                try {
                    val lifted = drag(down.id) { c ->
                        c.consume()
                        moved = true
                        last = FineSeek.position(anchor, (c.position.x - anchorX) / size.width.coerceAtLeast(1), duration)
                        preview(last)
                    }
                    if (lifted && moved) seek(last) else preview(anchor)
                } finally {
                    scrub(null)
                }
                return@awaitEachGesture
            }
            val begun = start
            if (begun == null) {
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (up != null && !up.pressed && !up.isConsumed) {
                    up.consume()
                    seek(at(up.position.x))
                }
                return@awaitEachGesture
            }
            if (sideways) {
                var last = at(begun.position.x)
                scrub(ScrubMode.NORMAL)
                preview(last)
                try {
                    val lifted = drag(begun.id) { c ->
                        c.consume()
                        last = at(c.position.x)
                        preview(last)
                    }
                    if (lifted) seek(last)
                } finally {
                    scrub(null)
                }
            } else {
                drag(begun.id) { it.consume() }
            }
        }
    }
}

/**
 * The time at the left end under the seek bar: the playback position, or while a finger moves the thumb the position it
 * points at (to a tenth of a second in a fine drag, which also says so).
 */
@Composable
fun ScrubTime(
    scrub: ScrubMode?,
    thumb: Float,
    positionMs: Long,
    durationMs: Long,
    style: androidx.compose.ui.text.TextStyle,
    format: (Long) -> String = ::formatTime,
) {
    val at = (thumb * durationMs).toLong()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (scrub) {
                null -> format(positionMs)
                ScrubMode.NORMAL -> format(at)
                ScrubMode.FINE -> format(at) + ".${(at % 1000) / 100}"
            },
            color = if (scrub == null) Color.White.copy(alpha = 0.75f) else Color.White,
            style = style,
            modifier = Modifier.testTag("seek_time"),
        )
        if (scrub == ScrubMode.FINE) {
            Text(
                stringResource(R.string.player_seek_fine),
                color = MaterialTheme.colorScheme.primary,
                style = style,
                modifier = Modifier.padding(start = 8.dp).testTag("seek_fine"),
            )
        }
    }
}

/** The folder (album) name of the player: a tap shows the folder in the browser, a long press the album information. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.folderNameGestures(onOpenFolder: () -> Unit, onAlbumInfo: () -> Unit): Modifier = composed {
    val openLabel = stringResource(R.string.player_open_folder)
    val infoLabel = stringResource(R.string.player_album_discovery)
    combinedClickable(onClickLabel = openLabel, onLongClickLabel = infoLabel, onClick = onOpenFolder, onLongClick = onAlbumInfo)
        .testTag("player_folder_name")
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

/**
 * The seek bar for TalkBack and other accessibility services: one adjustable control named "Playback position" whose
 * state reads "1:23 of 4:00" (it is drawn and dragged by hand, so it has no slider semantics of its own).
 */
fun Modifier.seekSemantics(positionMs: Long, durationMs: Long, onSeekFraction: (Float) -> Unit): Modifier = composed {
    val label = stringResource(R.string.player_seek)
    val state = stringResource(R.string.player_seek_state, formatTime(positionMs), formatTime(durationMs))
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seek by rememberUpdatedState(onSeekFraction)
    Modifier.semantics {
        contentDescription = label
        stateDescription = state
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        if (durationMs > 0) setProgress { v -> seek(v.coerceIn(0f, 1f)); true }
    }
}

/**
 * The seek bar's track drawn as the current track's waveform (loudness over time, so loud scenes can be found at a
 * glance): bars up to the playback position are bright, buffered ones dimmer, the rest faint. The bars grow out of the
 * plain track when the waveform arrives and move to their new heights when a coarse waveform is replaced by the full
 * one. [durationMs] is the track's (a CUE track shows its slice of the file).
 */
@Composable
fun WaveformTrack(wave: TrackWaveform, durationMs: Long, played: Float, buffered: Float, modifier: Modifier = Modifier, height: Dp = 36.dp) {
    val density = LocalDensity.current
    val barPx = with(density) { 3.dp.toPx() }
    val gapPx = with(density) { 1.5.dp.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    val count = if (widthPx > 0) ((widthPx + gapPx) / (barPx + gapPx)).toInt() else 0
    val bars = remember(wave, durationMs, count) { wave.waveform.bars(wave.startMs, durationMs, count) }
    // Heights move from [from] to [to] while [move] goes from 0 to 1.
    val move = remember { Animatable(1f) }
    var from by remember { mutableStateOf(FloatArray(0)) }
    var to by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(bars) {
        val p = move.value
        from = if (to.size == bars.size) FloatArray(bars.size) { lerpBar(from.getOrElse(it) { 0f }, to[it], p) } else FloatArray(bars.size)
        to = bars
        move.snapTo(0f)
        move.animateTo(1f, tween(BAR_MOVE_MS, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.fillMaxWidth().height(height).onSizeChanged { widthPx = it.width }.testTag("seek_waveform")) {
        if (count == 0) return@Canvas
        val p = move.value
        val step = if (count > 1) (size.width - barPx) / (count - 1) else 0f
        val minH = 2.dp.toPx()
        val mid = size.height / 2
        for (i in 0 until count) {
            val x = i * step
            val h = if (to.size == count) lerpBar(from.getOrElse(i) { 0f }, to[i], p) else 0f
            val at = (x + barPx / 2) / size.width
            val alpha = when {
                at <= played -> 1f
                at <= buffered -> 0.5f
                else -> 0.3f
            }
            val barH = maxOf(minH, h * size.height)
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(x, mid - barH / 2),
                size = Size(barPx, barH),
                cornerRadius = CornerRadius(barPx / 2),
            )
        }
    }
}

private const val BAR_MOVE_MS = 600

/** A bar not measured (NaN) is drawn flat. */
private fun lerpBar(a: Float, b: Float, t: Float): Float {
    val x = if (a.isNaN()) 0f else a
    val y = if (b.isNaN()) 0f else b
    return x + (y - x) * t
}

