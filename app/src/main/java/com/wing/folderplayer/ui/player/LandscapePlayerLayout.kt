package com.wing.folderplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag

@Composable
fun LandscapePlayerLayout(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    onCollapse: () -> Unit,
    transition: PlayerTransition,
) {
    val configuration = LocalConfiguration.current
    val screenRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()

    // Determine split ratio
    val leftPanelWeight = when {
        screenRatio < 1.6f -> 0.50f   // 3:2
        screenRatio < 1.9f -> 0.46f   // 16:9
        screenRatio < 2.1f -> 0.43f   // 18:9
        else -> 0.40f                  // Ultra-wide
    }
    
    var showPlaylist by remember { mutableStateOf(false) }
    var showAlbumInfo by remember { mutableStateOf(false) }
    // The playlist overlay is not a window: Back closes it before it folds the player.
    androidx.activity.compose.BackHandler(enabled = showPlaylist) { showPlaylist = false }

    // Transparent: the player panel behind paints the background (it grows with the fraction).
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Down anywhere (outside the lyrics list and the playlist overlay) folds the player; no playlist here.
                .playerSheetDrag(transition.sheet)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Left Panel: Controls + Swipe Gesture for Playlist (up), fold (down)
                Box(
                    modifier = Modifier
                        .weight(leftPanelWeight)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Left))
                        .playerSheetDrag(transition.sheet) { showPlaylist = true }
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LandscapeLeftControlPanel(
                        uiState = uiState,
                        viewModel = viewModel,
                        transition = transition,
                        onAlbumClick = {
                            showAlbumInfo = true
                            viewModel.fetchAlbumInfo()
                        }
                    )
                }

                // Right Panel: Lyrics (No swipe gesture here requested)
                Box(
                    modifier = Modifier
                        .weight(1f - leftPanelWeight)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Right))
                        .padding(top = 16.dp, bottom = 16.dp, end = 24.dp)
                        .fullPlayerContent(transition)
                ) {
                    LandscapeLyricsPanel(
                        uiState = uiState,
                        viewModel = viewModel,
                    )
                }
            }
            
            // Playlist Overlay (Right Side)
            AnimatedVisibility(
                visible = showPlaylist,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(1f - leftPanelWeight)
                    .fillMaxHeight(0.95f) // Almost full height
                    .graphicsLayer { translationX = 0f }
            ) {
                 Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                // Detect downward swipe to close
                                if (dragAmount > 30) {
                                    showPlaylist = false
                                }
                            }
                        },
                    color = Color(0xFF1A1A1A).copy(alpha = 0.98f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 0.dp)
                ) {
                    PlaylistSheetContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onClose = { showPlaylist = false }
                    )
                }
            }
            
            if (showAlbumInfo) {
                AlbumInfoDialog(uiState, viewModel) { showAlbumInfo = false }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).fullPlayerContent(transition)) { CastAction(viewModel) }
            CollapsePlayerButton(
                onCollapse,
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Left)).padding(4.dp)
                    .fullPlayerContent(transition)
            )
        }
    }
}

@Composable
fun LandscapeLeftControlPanel(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    transition: PlayerTransition,
    onAlbumClick: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val direction by rememberTrackDirection(viewModel)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Cover - Maximize space
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f) // Take all available space
                .fillMaxWidth()
                // Above its siblings: while moving, the cover passes over the (fading) texts below it.
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            val size = minOf(maxHeight, maxWidth)

            // The cover is the one picture that moves between the mini and the full player.
            Box(
                modifier = Modifier
                    .size(size)
                    .aspectRatio(1f)
                    .coverChange(uiState.coverUri to uiState.coverFallback) { direction }
                    .sharedCover(transition, cornerPx = { with(density) { 12.dp.toPx() } }, elevationPx = { with(density) { 12.dp.toPx() } })
            ) {
                FullCover(uiState, transition, Modifier.fillMaxSize())
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Info - Compact
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fullPlayerContent(transition)) {
            TrackText(uiState.currentTitle, direction) { title ->
                TextCompressed(
                    text = displayTitle(title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.currentFolderName.isNotEmpty()) {
                TextCompressed(
                    text = uiState.currentFolderName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { onAlbumClick() }
                )
                 // Audio Info
                if (uiState.audioInfo.isNotEmpty()) {
                    Text(
                        text = uiState.audioInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // 3. Progress
        Column(modifier = Modifier.fillMaxWidth().fullPlayerContent(transition)) {
             var sliderPosition by remember { mutableStateOf(0f) }
             val actualPosition = if (uiState.duration > 0) uiState.currentPosition.toFloat() / uiState.duration else 0f
             val bufferedFraction = if (uiState.duration > 0) uiState.bufferedPosition.toFloat() / uiState.duration else 0f
             
             LaunchedEffect(uiState.currentPosition) {
                 sliderPosition = actualPosition
             }
             
             val infiniteTransition = rememberInfiniteTransition(label = "buffering")
             val spinnerRotation by infiniteTransition.animateFloat(
                 initialValue = 0f,
                 targetValue = 360f,
                 animationSpec = infiniteRepeatable(
                     animation = tween(1000, easing = LinearEasing),
                     repeatMode = RepeatMode.Restart
                 ),
                 label = "spinnerRotation"
             )

             Box(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f)))
                
                if (bufferedFraction > 0) {
                     Box(Modifier.fillMaxWidth(bufferedFraction.coerceIn(0f, 1f)).height(4.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
                }
                
                Box(Modifier.fillMaxWidth(sliderPosition.coerceIn(0f, 1f)).height(4.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(2.dp)).background(Color.White))
                
                 BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .testTag("seek_bar")
                        // (The callbacks are kept up to date: the duration is the current track's, not the first one's.)
                        .seekGestures(
                            onPreview = { sliderPosition = it },
                            onSeek = {
                                sliderPosition = it
                                viewModel.seekTo((it * uiState.duration).toLong())
                            },
                        )
                ) {
                    val thumbSize = if (uiState.isBuffering) 16.dp else 12.dp
                    val availableWidth = maxWidth - thumbSize
                    val thumbOffset = availableWidth * sliderPosition
                    
                    Box(modifier = Modifier.padding(start = thumbOffset.coerceAtLeast(0.dp)).align(Alignment.CenterStart)) {
                         if (uiState.isBuffering) {
                             Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = spinnerRotation }
                                    .drawBehind {
                                        drawArc(Color.White.copy(alpha = 0.6f), 0f, 270f, false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                                    }
                            )
                         } else {
                             Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                         }
                    }
                }
             }

             Row(
                 modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                 horizontalArrangement = Arrangement.SpaceBetween
             ) {
                 Text(formatTimeLs(uiState.currentPosition), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                 Text(formatTimeLs(uiState.duration), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
             }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // 4. Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).fullPlayerContent(transition),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.previous() }) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }

            Surface(
                onClick = { viewModel.playPause() },
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(72.dp),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PlayPauseIcon(uiState.isPlaying, { null }, Color.Black, Modifier.size(40.dp))
                }
            }

            IconButton(onClick = { viewModel.next() }) {
                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
fun LandscapeLyricsPanel(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
) {
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    if (uiState.lyrics.isNotEmpty()) {
        val listState = rememberLazyListState()
        
        LaunchedEffect(uiState.currentLyricIndex) {
            if (uiState.currentLyricIndex >= 0 && uiState.currentLyricIndex < uiState.lyrics.size) {
                listState.animateScrollToItem(
                    index = uiState.currentLyricIndex,
                    scrollOffset = 0 
                )
            }
        }
        
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewHeight = maxHeight
            
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = (viewHeight / 2 - 20.dp).coerceAtLeast(0.dp))
            ) {
                itemsIndexed(uiState.lyrics) { index, lyric ->
                    val isCurrentLine = index == uiState.currentLyricIndex
                    val colorAlpha by animateFloatAsState(targetValue = if (isCurrentLine) 0.9f else 0.4f, label = "alpha")
                    val scale by animateFloatAsState(targetValue = if (isCurrentLine) 1.2f else 1f, label = "scale")

                    val isLongLyric = lyric.text.length > 40
                    val minLineHeight by animateDpAsState(
                        targetValue = when {
                            isCurrentLine && isLongLyric -> 56.dp
                            isCurrentLine -> 40.dp
                            else -> 32.dp
                        },
                        animationSpec = tween(durationMillis = 200),
                        label = "height"
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = minLineHeight)
                            .wrapContentHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = lyric.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = colorAlpha),
                            fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = if (isCurrentLine) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                        )
                        uiState.translationFor(index)?.let { tr ->
                            Text(tr, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = colorAlpha * 0.8f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_no_lyrics), color = Color.White.copy(alpha = 0.3f))
        }
    }
    }
    LyricsFooter(uiState) { regen -> viewModel.requestAiLyrics(regen) }
    }
}

fun formatTimeLs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
