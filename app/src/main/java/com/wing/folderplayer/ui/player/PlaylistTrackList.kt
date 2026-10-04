package com.wing.folderplayer.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wing.folderplayer.R
import com.wing.folderplayer.data.playlist.PlaylistItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A row of the list; [id] stays the same while the row is moved (entries can repeat, so the item is not a key). */
private class TrackRow(val id: Long, val item: PlaylistItem)

/**
 * Tracks of the shown playlist: tap plays, swipe left removes, drag the handle (or long-press the row and drag) to move
 * an entry. The move is stored when the finger is lifted; the list follows the stored playlist otherwise.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaylistTrackList(uiState: PlayerUiState, viewModel: PlayerViewModel, onPlayed: () -> Unit, modifier: Modifier = Modifier) {
    val source = uiState.activePlaylistItems
    val listId = uiState.activePlaylistId
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var nextId by remember { mutableLongStateOf(0L) }
    val rows = remember { mutableStateListOf<TrackRow>().apply { source.forEach { add(TrackRow(nextId++, it)) } } }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // Follow the stored list when not dragging. Rows keep their ids where the same entry is still there, so a move that
    // was just stored (same entries, same order) changes nothing on screen.
    LaunchedEffect(source, listId, draggingId == null) {
        if (draggingId != null || rows.map { it.item } == source) return@LaunchedEffect
        val unused = rows.toMutableList()
        val rebuilt = source.map { item ->
            val i = unused.indexOfFirst { it.item == item }
            if (i >= 0) unused.removeAt(i) else TrackRow(nextId++, item)
        }
        rows.clear()
        rows.addAll(rebuilt)
    }

    fun startDrag(id: Long) {
        dragFrom = rows.indexOfFirst { it.id == id }
        if (dragFrom < 0) return
        draggingId = id
        dragOffset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(dy: Float) {
        val id = draggingId ?: return
        dragOffset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        val target = visible.firstOrNull { it.key != id && center >= it.offset && center <= it.offset + it.size } ?: return
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0 || target.index !in rows.indices) return
        // Moving the first visible row would make the list scroll along with it; keep the viewport where it is.
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        rows.add(target.index, rows.removeAt(from))
        // The dragged row is now laid out in the target's place: keep it under the finger.
        dragOffset -= (target.offset - current.offset)
        if (from == firstIndex || target.index == firstIndex) {
            scope.launch { listState.scrollToItem(firstIndex, firstOffset) }
        }
    }

    fun endDrag() {
        val id = draggingId ?: return
        val to = rows.indexOfFirst { it.id == id }
        val from = dragFrom
        draggingId = null
        dragOffset = 0f
        if (from >= 0 && to >= 0 && from != to) viewModel.moveInActivePlaylist(from, to)
    }

    // Scroll while a row is held near the top or bottom edge.
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == draggingId }
            if (item != null) {
                val top = item.offset + dragOffset
                val bottom = top + item.size
                val edge = item.size.toFloat()
                val step = when {
                    bottom > info.viewportEndOffset - edge / 2 -> edge / 6
                    top < info.viewportStartOffset + edge / 2 -> -edge / 6
                    else -> 0f
                }
                if (step != 0f) {
                    val consumed = listState.scrollBy(step)
                    dragOffset += consumed
                    dragBy(0f)
                }
            }
            delay(16)
        }
    }

    val reorderLabel = stringResource(R.string.playlist_reorder)
    val moveUpLabel = stringResource(R.string.common_move_up)
    val moveDownLabel = stringResource(R.string.common_move_down)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("playlist_list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            val item = row.item
            val isCurrent = item.matchesMediaId(uiState.currentMediaId)
            val dragging = draggingId == row.id

            val dismissState = rememberDismissState(
                confirmValueChange = {
                    if (it == DismissValue.DismissedToStart) {
                        val i = rows.indexOfFirst { r -> r.id == row.id }
                        if (i >= 0) viewModel.removeFromActivePlaylist(i)
                        true
                    } else false
                }
            )

            Box(
                (if (dragging) Modifier.zIndex(1f) else Modifier.animateItemPlacement())
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        shadowElevation = if (dragging) 8.dp.toPx() else 0f
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction(moveUpLabel) { viewModel.moveInActivePlaylist(index, index - 1); true } else null,
                            if (index < rows.size - 1) CustomAccessibilityAction(moveDownLabel) { viewModel.moveInActivePlaylist(index, index + 1); true } else null,
                        )
                    }
            ) {
                SwipeToDismiss(
                    state = dismissState,
                    directions = if (draggingId == null) setOf(DismissDirection.EndToStart) else emptySet(),
                    background = {
                        val color = if (dismissState.dismissDirection == DismissDirection.EndToStart) Color.Red.copy(alpha = 0.3f) else Color.Transparent
                        Box(Modifier.fillMaxSize().background(color))
                    },
                    dismissContent = {
                        Surface(color = if (dragging) Color(0xFF2A2A2A) else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val i = rows.indexOfFirst { r -> r.id == row.id }
                                        if (i >= 0) viewModel.playPlaylistSong(listId, i)
                                        onPlayed()
                                    }
                                    // Inside the click: a long press followed by a drag (or a release) is not a tap.
                                    .reorderGesture(longPress = true, onStart = { startDrag(row.id) }, onDrag = ::dragBy, onEnd = ::endDrag)
                                    .padding(vertical = 4.dp)
                                    .testTag("playlist_row_${item.title}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                                    if (isCurrent) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    } else {
                                        Text(text = (index + 1).toString(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.3f))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val artist = item.artist ?: ""
                                    if (artist.isNotEmpty()) {
                                        Text(
                                            text = artist,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // Handle: drag at once, no long press needed.
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .reorderGesture(longPress = false, onStart = { startDrag(row.id) }, onDrag = ::dragBy, onEnd = ::endDrag)
                                        .semantics { contentDescription = reorderLabel }
                                        .testTag("playlist_handle_${item.title}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = null, tint = Color.White.copy(alpha = if (dragging) 0.9f else 0.4f))
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Vertical drag for reordering. Movement is measured in root coordinates: the dragged row is moved to other slots while
 * the finger is down, so positions local to it would jump at every move.
 */
private fun Modifier.reorderGesture(longPress: Boolean, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastY by remember { mutableFloatStateOf(0f) }
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    fun rootY(local: Offset): Float = coordinates?.takeIf { it.isAttached }?.localToRoot(local)?.y ?: local.y
    onGloballyPositioned { coordinates = it }.pointerInput(longPress) {
        val onDragStart: (Offset) -> Unit = { o -> lastY = rootY(o); start() }
        val onDragMove: (PointerInputChange, Offset) -> Unit = { change, _ ->
            change.consume()
            val y = rootY(change.position)
            drag(y - lastY)
            lastY = y
        }
        if (longPress) detectDragGesturesAfterLongPress(onDragStart, { end() }, { end() }, onDragMove)
        else detectDragGestures(onDragStart, { end() }, { end() }, onDragMove)
    }
}
