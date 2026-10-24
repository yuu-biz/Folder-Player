package com.wing.folderplayer.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Vertical drag for reordering. Movement is measured in root coordinates: the dragged row is moved to other slots while
 * the finger is down, so positions local to it would jump at every move.
 */
internal fun Modifier.reorderGesture(longPress: Boolean, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = composed {
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
