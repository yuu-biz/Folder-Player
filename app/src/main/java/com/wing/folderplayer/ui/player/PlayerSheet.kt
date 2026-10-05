package com.wing.folderplayer.ui.player

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Where the player is between the mini player (0) and the full player (1).
 *
 * [fraction] changes every frame while the player moves: read it only in layout / draw lambdas (graphicsLayer,
 * drawWithContent), or through `derivedStateOf` for a threshold, never directly in composition. [expanded] is the
 * logical state (where the player is or is going): it is what Back, accessibility and a saved instance state follow,
 * so a recreation in the middle of a move always lands at one end.
 */
@Stable
class PlayerSheetState internal constructor(initiallyExpanded: Boolean) {
    var fraction by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
        private set
    var expanded by mutableStateOf(initiallyExpanded)
        private set
    /** A finger is moving the player. */
    var isDragging by mutableStateOf(false)
        private set
    /** The player is moving on its own towards [expanded]. */
    var isAnimating by mutableStateOf(false)
        private set

    /** Finger distance for 0 → 1: from the top of the mini player to the top of the screen. */
    internal var range: () -> Float = { 1f }
    private val rangePx get() = range().coerceAtLeast(1f)
    /** Release speed (px/s) above which the direction of the fling decides, not the position. */
    internal var flingVelocityPx = 1_500f
    internal var scope: CoroutineScope? = null

    private var job: Job? = null
    /** Changes whenever a drag or an animation is taken over; stale drags and animations then do nothing. */
    private var generation = 0

    /** Fully open and at rest: the pages underneath need not be drawn. */
    val isSettledFull: Boolean get() = expanded && fraction >= 1f && !isDragging && !isAnimating
    /** Something of the full player is on screen (or about to be). */
    val isOpen: Boolean get() = expanded || fraction > 0f || isDragging || isAnimating

    fun expand() = animateTo(true)
    fun collapse() = animateTo(false)

    /** Jumps to an end without animating (e.g. when another page replaces the browser). */
    fun snapTo(open: Boolean) {
        stop()
        expanded = open
        fraction = if (open) 1f else 0f
    }

    /** Moves to an end; [velocity] in fractions per second (positive = opening). Takes over any drag or animation. */
    fun animateTo(open: Boolean, velocity: Float = 0f) {
        stop()
        expanded = open
        val target = if (open) 1f else 0f
        val s = scope
        if (fraction == target) return
        if (s == null || !animationsEnabled()) {
            fraction = target
            return
        }
        val gen = generation
        isAnimating = true
        job = s.launch {
            try {
                animate(fraction, target, velocity, Spec) { v, _ -> if (gen == generation) fraction = v.coerceIn(0f, 1f) }
                if (gen == generation) fraction = target
            } finally {
                if (gen == generation) {
                    isAnimating = false
                    job = null
                }
            }
        }
    }

    private fun stop() {
        generation++
        job?.cancel()
        job = null
        isAnimating = false
        isDragging = false
    }

    /** Starts following a finger from the current position (an animation is stopped where it is). Returns the drag's token. */
    internal fun beginDrag(): Int {
        stop()
        isDragging = true
        return generation
    }

    /** Finger moved by [dyPx] (screen y: negative = up = opening). */
    internal fun dragBy(token: Int, dyPx: Float) {
        if (token != generation) return
        fraction = (fraction - dyPx / rangePx).coerceIn(0f, 1f)
    }

    /**
     * Finger lifted (or the gesture was cancelled). A fling decides by its direction, a slow release by the nearer end;
     * a touch that only stopped an animation lets it go on to where it was going.
     */
    internal fun endDrag(token: Int, velocityY: Float, moved: Boolean) {
        if (token != generation) return
        val open = when {
            !moved -> expanded
            velocityY < -flingVelocityPx -> true
            velocityY > flingVelocityPx -> false
            else -> fraction >= 0.5f
        }
        animateTo(open, if (moved) -velocityY / rangePx else 0f)
    }

    companion object {
        /** No bounce: the player must never overshoot past the screen top or below the mini player. */
        private val Spec = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.0005f)

        /** "Remove animations" / animator scale 0: jump instead of moving. */
        private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled()

        val Saver: Saver<PlayerSheetState, Boolean> = Saver(save = { it.expanded }, restore = { PlayerSheetState(it) })
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return rememberSaveable(saver = PlayerSheetState.Saver) { PlayerSheetState(false) }.also {
        it.scope = scope
        it.flingVelocityPx = with(density) { 600.dp.toPx() }
    }
}

/** Test hook: the sheet state of the player host (read through semantics, not per frame). */
val PlayerSheetKey = SemanticsPropertyKey<PlayerSheetState>("PlayerSheet")
private var SemanticsPropertyReceiver.playerSheet by PlayerSheetKey

/** Size of the mini player's cover; also the size its thumbnail is requested at by both players. */
val MiniCoverSize = 48.dp
private val MiniCoverCorner = 6.dp

/**
 * What the mini and the full player share while one turns into the other: the mini cover's place, the panel edge and
 * whether the full player's cover can already be shown. Positions are in root coordinates.
 */
@Stable
class PlayerTransition internal constructor(val sheet: PlayerSheetState) {
    /** The mini player's cover (measured while the bar is not swiped sideways). */
    var miniCover by mutableStateOf<Rect?>(null)
        internal set
    /** Top of the mini player bar. */
    var miniTop by mutableStateOf<Float?>(null)
        internal set
    internal var fallbackMiniTop by mutableFloatStateOf(0f)
    internal var miniCornerPx = 0f
    internal var miniCoverPx = 0f
    internal var contentOffsetPx = 0f
    internal var panelCornerPx = 0f

    /** The full player's cover shows the current picture (its thumbnail at least). */
    var fullCoverReady by mutableStateOf(false)
        internal set
    /** The full player's cover has been measured (until then it cannot be drawn at the right place). */
    internal var fullCoverPlaced by mutableStateOf(false)

    /** The moving cover is on screen: the mini player's own cover steps aside. */
    fun fullCoverShown(f: Float = sheet.fraction): Boolean = f > 0f && fullCoverReady && fullCoverPlaced
    /** Cover (key) whose large picture has been loaded once; others then take it from the memory cache. */
    var largeLoadedFor by mutableStateOf<Any?>(null)
        internal set
    /** Pixel size the large picture is requested at by everyone (cover, blurred background). */
    var largePx = 1024
        internal set
    var thumbPx = 144
        internal set

    private fun miniTopPx() = miniTop ?: fallbackMiniTop

    /** Top edge of the player panel. */
    fun panelTop(f: Float = sheet.fraction): Float = lerp(miniTopPx(), 0f, f)

    fun miniCoverRect(): Rect = miniCover ?: Rect(Offset(miniCoverPx / 6f, fallbackMiniTop + miniCoverPx / 4f), Size(miniCoverPx, miniCoverPx))

    /** The full player's controls, texts and lyrics: only in the second half of the move. */
    fun contentAlpha(f: Float = sheet.fraction): Float = ((f - 0.45f) / 0.55f).coerceIn(0f, 1f).let { it * it }

    fun contentOffset(f: Float = sheet.fraction): Float = (1f - ((f - 0.3f) / 0.7f).coerceIn(0f, 1f)) * contentOffsetPx

    /** The mini player's own texts and buttons fade out at the start of the move. */
    fun miniAlpha(f: Float = sheet.fraction): Float = (1f - f / 0.3f).coerceIn(0f, 1f)

    fun miniCornerPx() = miniCornerPx
}

/**
 * Moves the full player's cover between its own place (fraction 1) and the mini player's cover (fraction 0), as one
 * picture: size, position and corners follow the fraction; at 1 it is exactly the full player's cover (an identity
 * transform, so the full player's own layout animations stay as they are).
 */
fun Modifier.sharedCover(transition: PlayerTransition, cornerPx: () -> Float, elevationPx: () -> Float = { 0f }): Modifier = composed {
    var natural by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(transition) { onDispose { transition.fullCoverPlaced = false } }
    onGloballyPositioned { c ->
        natural = c.boundsIn()
        transition.fullCoverPlaced = true
    }
        .graphicsLayer {
            val f = transition.sheet.fraction
            val n = natural
            if (n == null || n.width <= 0f || n.height <= 0f) {
                alpha = 0f
                return@graphicsLayer
            }
            val m = transition.miniCoverRect()
            val sx = lerp(m.width / n.width, 1f, f)
            val sy = lerp(m.height / n.height, 1f, f)
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = sx
            scaleY = sy
            translationX = (m.left - n.left) * (1f - f)
            translationY = (m.top - n.top) * (1f - f)
            alpha = if (transition.fullCoverShown(f)) 1f else 0f
            shape = RoundedCornerShape(lerp(transition.miniCornerPx(), cornerPx(), f) / sx.coerceAtLeast(0.01f))
            clip = true
            shadowElevation = elevationPx() * f
        }
}

private fun LayoutCoordinates.boundsIn(): Rect = Rect(positionInRoot(), size.toSize())

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/** A part of the full player other than the cover: fades in (and rises a little) in the second half of the move. */
fun Modifier.fullPlayerContent(transition: PlayerTransition, offset: Boolean = true): Modifier = graphicsLayer {
    val f = transition.sheet.fraction
    alpha = transition.contentAlpha(f)
    if (offset) translationY = transition.contentOffset(f)
}

/**
 * Vertical drag of the player: follows the finger, settles at an end on release (by speed, else by position).
 * - At rest at full size, an upward swipe is [onSwipeUpWhenFull] (the playlist) instead, when given.
 * - While the player moves on its own a touch takes it over at once, and the parts underneath (fading buttons) get
 *   nothing: the down is taken before them.
 * - Children that take a vertical drag themselves (lyrics list, seek bar, playlist) keep it: the slop is checked after
 *   them, and a consumed move ends the player's drag.
 * The node carrying this must not move with the fraction (positions are local to it).
 */
fun Modifier.playerSheetDrag(sheet: PlayerSheetState, onSwipeUpWhenFull: (() -> Unit)? = null): Modifier = composed {
    val swipeUp = rememberUpdatedState(onSwipeUpWhenFull)
    pointerInput(sheet) {
        val playlistDistance = 32.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var token = -1
            var moved = false
            val tracker = VelocityTracker()
            try {
                if (sheet.isAnimating) {
                    // An outer handler took it already (landscape: root and panel both carry this).
                    if (down.isConsumed) return@awaitEachGesture
                    down.consume()
                    token = sheet.beginDrag()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var travelled = 0f
                    val lifted = follow(down.id, PointerEventPass.Initial) { change, dy ->
                        travelled += abs(dy)
                        if (travelled > viewConfiguration.touchSlop / 2) moved = true
                        sheet.dragBy(token, dy)
                        tracker.addPosition(change.uptimeMillis, change.position)
                    }
                    sheet.endDrag(token, if (lifted) tracker.calculateVelocity().y else 0f, moved)
                    token = -1
                    return@awaitEachGesture
                }

                // Let the down pass the children first (a clickable consumes it); the slop check starts with the moves.
                awaitPointerEvent(PointerEventPass.Main)
                var over = 0f
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, o ->
                    change.consume()
                    over = o
                } ?: return@awaitEachGesture

                val playlist = swipeUp.value
                if (playlist != null && over < 0f && sheet.isSettledFull) {
                    var up = -over
                    var fired = false
                    if (up > playlistDistance) { fired = true; playlist() }
                    follow(start.id, PointerEventPass.Main) { _, dy ->
                        up -= dy
                        if (!fired && up > playlistDistance) { fired = true; playlist() }
                    }
                    return@awaitEachGesture
                }

                token = sheet.beginDrag()
                moved = true
                tracker.addPosition(start.uptimeMillis, start.position)
                sheet.dragBy(token, over)
                val lifted = follow(start.id, PointerEventPass.Main) { change, dy ->
                    sheet.dragBy(token, dy)
                    tracker.addPosition(change.uptimeMillis, change.position)
                }
                sheet.endDrag(token, if (lifted) tracker.calculateVelocity().y else 0f, true)
                token = -1
            } finally {
                // Cancelled (the node left, e.g. the track ended): never stay half-open.
                if (token >= 0) sheet.endDrag(token, 0f, moved)
            }
        }
    }
}

/**
 * Follows pointer [id] until it is lifted (true) or taken by someone else / gone (false), consuming its moves.
 * In the Main pass a move already consumed by a child ends it.
 */
private suspend fun AwaitPointerEventScope.follow(
    id: PointerId,
    pass: PointerEventPass,
    onDelta: (PointerInputChange, Float) -> Unit,
): Boolean {
    while (true) {
        val event = awaitPointerEvent(pass)
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return true
        if (pass == PointerEventPass.Main && change.isConsumed) return false
        val dy = change.position.y - change.previousPosition.y
        change.consume()
        if (dy != 0f) onDelta(change, dy)
    }
}

/**
 * The player over the pages: one panel that grows from the mini player into the full player and back.
 * Layers, bottom to top: panel background (mini colour → the player background, its top edge follows the fraction),
 * the mini bar (static, fades out), the full player (clipped to the panel; its parts fade in, its cover moves).
 * Only narrow parts of the player state are collected here; the full player is composed only while it is open.
 */
@Composable
fun PlayerSheetHost(
    sheet: PlayerSheetState,
    viewModel: PlayerViewModel,
    /** The mini player may be shown (there is a track and the browser is the page). */
    miniAllowed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val transition = remember(sheet) { PlayerTransition(sheet).also { t -> sheet.range = { t.miniTop ?: t.fallbackMiniTop } } }
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    with(density) {
        transition.miniCornerPx = MiniCoverCorner.toPx()
        transition.miniCoverPx = MiniCoverSize.toPx()
        transition.thumbPx = MiniCoverSize.roundToPx()
        transition.contentOffsetPx = 40.dp.toPx()
        transition.panelCornerPx = 24.dp.toPx()
        // The same for both orientations: turning the phone does not request the picture again.
        transition.largePx = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx().coerceAtLeast(1)
    }
    val miniBarPx = with(density) { MiniPlayerHeight.toPx() } + navBottom

    val open by remember(sheet) { derivedStateOf { sheet.isOpen } }
    val settledFull by remember(sheet) { derivedStateOf { sheet.isSettledFull } }
    val showMini = miniAllowed && !settledFull
    // Turned while the bar is not shown (full player open): its old place is for the other orientation; until it is
    // measured again the estimate is closer. (A shown bar measures itself.)
    val miniShown by rememberUpdatedState(showMini)
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        if (!miniShown) {
            transition.miniCover = null
            transition.miniTop = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> transition.fallbackMiniTop = c.size.height - miniBarPx }
            .semantics { playerSheet = sheet }
            .testTag("player_host")
    ) {
        if (open) {
            val backgroundStyle by viewModel.backgroundStyle.collectAsState()
            val cover by viewModel.cover.collectAsState()
            PanelBackground(transition, backgroundStyle, cover)
        }
        if (showMini) {
            MiniPlayer(viewModel, transition, onOpen = sheet::expand, onDismiss = onDismiss, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (open) {
            // Before the full player's own handlers (landscape playlist overlay), which therefore come first.
            SheetBackHandler(sheet)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent { clipRect(top = transition.panelTop()) { this@drawWithContent.drawContent() } }
            ) {
                MainPlayerScreen(viewModel = viewModel, onCollapse = sheet::collapse, transition = transition)
            }
        }
    }
}

/** Back folds the full player, also while a finger holds it part-way (own scope: drag start / end recompose only this). */
@Composable
private fun SheetBackHandler(sheet: PlayerSheetState) {
    BackHandler(enabled = sheet.expanded || sheet.isDragging) { sheet.collapse() }
}

/** Panel behind the player: a scrim over the pages above it, the mini colour turning into the player background. */
@Composable
private fun PanelBackground(transition: PlayerTransition, style: String, cover: Pair<Any?, ByteArray?>) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val path = remember { Path() }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                val f = transition.sheet.fraction
                val top = transition.panelTop(f)
                if (top > 0f) drawRect(Color.Black.copy(alpha = 0.5f * f), size = Size(size.width, top))
                val r = transition.panelCornerPx * (1f - f)
                path.reset()
                path.addRoundRect(
                    RoundRect(Rect(0f, top, size.width, size.height), topLeft = CornerRadius(r), topRight = CornerRadius(r))
                )
                clipPath(path) {
                    drawRect(base)
                    this@drawWithContent.drawContent()
                }
            }
    ) {
        PlayerBackground(
            style = style,
            cover = cover.first,
            fallback = cover.second,
            requestPx = transition.largePx,
            // The blurred picture waits for the cover's large picture, then comes from the memory cache (a second
            // request at the same time would fetch it from the source twice).
            pictureAllowed = transition.largeLoadedFor == cover,
            modifier = Modifier.graphicsLayer { alpha = (transition.sheet.fraction / 0.6f).coerceIn(0f, 1f) },
        )
    }
}
