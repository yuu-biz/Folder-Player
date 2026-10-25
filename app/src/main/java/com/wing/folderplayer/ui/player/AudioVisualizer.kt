package com.wing.folderplayer.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.wing.folderplayer.R
import com.wing.folderplayer.data.artwork.SourceImageFetcher
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.playback.AudioTap
import com.wing.folderplayer.playback.Spectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the visualiser draws the music; a tap on it goes to the next one (kept). */
enum class VisualizerStyle(val label: Int) {
    /** The spectrum as one smooth shape, bass in the middle, mirrored left / right and up / down, with a glowing edge. */
    WAVE(R.string.visualizer_style_wave),
    /** Rounded bars standing on a line, their reflection under it and a cap at each bar's recent peak. */
    BARS(R.string.visualizer_style_bars),
    /** Spokes around a circle that swells with the bass. */
    RING(R.string.visualizer_style_ring);

    fun next(): VisualizerStyle = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(name: String): VisualizerStyle = values().firstOrNull { it.name == name } ?: WAVE
    }
}

/**
 * In the place of the lyrics when a song has none: the music that is playing, drawn as it is heard, in the colours of
 * the cover ([VisualizerColors]; the theme's [accent] for a grey cover or none). It follows the player's clock
 * ([AudioTap]), so a pause holds the picture; with nothing played here (a cast) it lies flat. Drawn only while
 * [visible]; the samples are only kept while it is shown. A tap changes the [VisualizerStyle].
 *
 * The levels rise quickly and fall back slowly (in time and against their neighbours), so the shape breathes with the
 * music instead of flickering with each block of samples.
 */
@Composable
fun AudioVisualizer(
    visible: Boolean,
    playing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    cover: Any? = null,
    coverFallback: ByteArray? = null,
) {
    DisposableEffect(visible) {
        if (visible) AudioTap.attach()
        onDispose { if (visible) AudioTap.detach() }
    }
    val context = LocalContext.current
    val prefs = remember { PlaybackPreferences(context) }
    var style by remember { mutableStateOf(VisualizerStyle.of(prefs.getVisualizerStyle())) }
    // The style's name shows for a moment after a tap.
    var labelShown by remember { mutableStateOf(false) }
    LaunchedEffect(style, labelShown) { if (labelShown) { delay(1_200); labelShown = false } }
    val labelAlpha by animateFloatAsState(if (labelShown) 1f else 0f, tween(300), label = "visualizer label")

    val secondary = MaterialTheme.colorScheme.tertiary
    val picked by rememberCoverColors(cover, coverFallback)
    val c1 by animateColorAsState(picked?.first ?: accent, tween(600), label = "visualizer colour 1")
    val c2 by animateColorAsState(picked?.second ?: secondary, tween(600), label = "visualizer colour 2")

    val samples = remember { FloatArray(FFT_SIZE) }
    val spectrum = remember { Spectrum(FFT_SIZE, BANDS, TILT_DB_PER_OCTAVE) }
    val levels = remember { FloatArray(BANDS) }
    val smooth = remember { FloatArray(BANDS) }
    val peaks = remember { FloatArray(BARS) }
    val peakHold = remember { FloatArray(BARS) }
    val peakFall = remember { FloatArray(BARS) }
    val paths = remember { VisualizerPaths() }
    var frame by remember { mutableIntStateOf(0) }
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        var lastNs = 0L
        while (true) {
            // An infinite animation: tests (and reduced-motion policies) can hold it, as they hold the other endless ones.
            val now = withInfiniteAnimationFrameNanos { it }
            val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
            lastNs = now
            val got = AudioTap.read(samples)
            if (got) spectrum.analyse(samples, AudioTap.rate)
            for (b in 0 until BANDS) {
                val target = if (got) spectrum.levels[b] else 0f
                // Up within a few frames, down over about two thirds of a second.
                levels[b] = if (target > levels[b]) levels[b] + (target - levels[b]) * min(1f, dt * ATTACK) else max(target, levels[b] - dt * RELEASE)
            }
            for (b in 0 until BANDS) {
                val l = levels[max(0, b - 1)]
                val r = levels[min(BANDS - 1, b + 1)]
                smooth[b] = 0.25f * l + 0.5f * levels[b] + 0.25f * r
            }
            // Bar caps: held for a moment at a peak, then falling faster and faster.
            for (i in 0 until BARS) {
                val v = barLevel(smooth, i)
                if (v >= peaks[i]) { peaks[i] = v; peakHold[i] = PEAK_HOLD_S; peakFall[i] = 0f }
                else if (peakHold[i] > 0f) peakHold[i] -= dt
                else { peakFall[i] += PEAK_GRAVITY * dt; peaks[i] = max(v, peaks[i] - peakFall[i] * dt) }
            }
            frame++
            // Paused: the picture holds; look again a few times a second only.
            if (!isPlaying) delay(250)
        }
    }
    val change = stringResource(R.string.visualizer_change)
    val styleName = stringResource(style.label)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = change, role = Role.Button) {
                    style = style.next()
                    prefs.saveVisualizerStyle(style.name)
                    labelShown = true
                }
                .semantics { contentDescription = styleName }
                .testTag("audio_visualizer")
        ) {
            frame // redrawn with each frame
            when (style) {
                VisualizerStyle.WAVE -> drawWave(smooth, c1, c2, paths)
                VisualizerStyle.BARS -> drawBars(smooth, peaks, c1, c2)
                VisualizerStyle.RING -> drawRing(smooth, c1, c2)
            }
        }
        Text(
            styleName,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).graphicsLayer { alpha = labelAlpha }.testTag("visualizer_style"),
        )
    }
}

/** The two colours of [cover] (or of [fallback] when it cannot be read); null for none or a grey one. */
@Composable
private fun rememberCoverColors(cover: Any?, fallback: ByteArray?) = run {
    val context = LocalContext.current
    produceState<Pair<Color, Color>?>(null, cover, fallback) {
        value = null
        for (data in listOfNotNull(cover, fallback)) {
            val request = ImageRequest.Builder(context).data(data).size(COLOR_SAMPLE_PX).allowHardware(false)
                .setParameter(SourceImageFetcher.TIER_PARAM, SourceImageFetcher.TIER_ARTWORK).build()
            val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable ?: continue
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = drawable.toBitmap(COLOR_SAMPLE_PX, COLOR_SAMPLE_PX)
                    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                    VisualizerColors.pick(px)?.let { (a, b) -> Color(a) to Color(b) }
                }.getOrNull()
            }
            return@produceState
        }
    }
}

/** Paths kept between frames (rewound, not allocated, each frame). */
private class VisualizerPaths {
    val outer = Path()
    val inner = Path()
    val edge = Path()
}

/** Level of bar [i] of [BARS]: the loudest of the bands it covers. */
private fun barLevel(levels: FloatArray, i: Int): Float {
    val from = i * BANDS / BARS
    val to = max(from + 1, (i + 1) * BANDS / BARS)
    var v = 0f
    for (b in from until to) v = max(v, levels[b])
    return v
}

/**
 * The level at [t] (0 = middle, 1 = edge) across the width: bass in the middle, the highest band at the edges, faded to
 * nothing at the very edge so that the shape closes on the middle line.
 */
private fun waveLevel(levels: FloatArray, t: Float, closed: Boolean = true): Float {
    val x = t * (BANDS - 1)
    val i = x.toInt().coerceIn(0, BANDS - 2)
    val f = x - i
    val v = levels[i] * (1 - f) + levels[i + 1] * f
    return v * (if (closed) 1f - t * t * t else 1f - 0.5f * t * t * t)
}

/** A smooth curve through [n] points from x = 0 to [w] (or back, [reverse]) at heights [y] (midpoints joined by quadratic curves). */
private fun Path.curve(n: Int, w: Float, y: (Int) -> Float, start: Boolean, reverse: Boolean = false) {
    fun px(k: Int) = if (reverse) w * (n - 1 - k) / (n - 1) else w * k / (n - 1)
    if (start) moveTo(px(0), y(0)) else lineTo(px(0), y(0))
    for (k in 1 until n) {
        val x0 = px(k - 1); val y0 = y(k - 1)
        quadraticBezierTo(x0, y0, (x0 + px(k)) / 2, (y0 + y(k)) / 2)
    }
    lineTo(px(n - 1), y(n - 1))
}

private fun DrawScope.drawWave(levels: FloatArray, c1: Color, c2: Color, paths: VisualizerPaths) {
    val w = size.width
    val mid = size.height / 2
    val amp = size.height * 0.44f
    val n = WAVE_POINTS
    // t of point k: from the left edge (1) through the middle (0) to the right edge (1).
    fun t(k: Int) = kotlin.math.abs(k - (n - 1) / 2f) / ((n - 1) / 2f)
    val across = Brush.horizontalGradient(0f to c2, 0.5f to c1, 1f to c2)

    // The body: the curve above the line and its mirror below, filled with a fade towards the line's ends.
    fun body(path: Path, scale: Float) {
        path.rewind()
        path.curve(n, w, { k -> mid - amp * scale * waveLevel(levels, t(k)) }, start = true)
        path.curve(n, w, { k -> mid + amp * scale * waveLevel(levels, t(k)) }, start = false, reverse = true)
        path.close()
    }
    body(paths.outer, 1f)
    drawPath(paths.outer, across, alpha = 0.28f)
    body(paths.inner, 0.55f)
    drawPath(paths.inner, across, alpha = 0.45f)

    // The edge above and below, with a glow: wide faint strokes under a thin bright one.
    for (sign in listOf(-1f, 1f)) {
        paths.edge.rewind()
        paths.edge.curve(n, w, { k -> mid + sign * amp * waveLevel(levels, t(k)) }, start = true)
        drawPath(paths.edge, across, alpha = 0.10f, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(paths.edge, across, alpha = 0.22f, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(paths.edge, Brush.horizontalGradient(0f to lerp(c2, Color.White, 0.3f), 0.5f to lerp(c1, Color.White, 0.45f), 1f to lerp(c2, Color.White, 0.3f)),
            alpha = 0.95f, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawBars(levels: FloatArray, peaks: FloatArray, c1: Color, c2: Color) {
    val w = size.width
    val h = size.height
    val base = h * 0.74f
    val maxH = h * 0.66f
    val slot = w / BARS
    val barW = slot * 0.62f
    val cap = 3.dp.toPx()
    val gap = 3.dp.toPx()
    val up = Brush.verticalGradient(0f to lerp(c2, Color.White, 0.15f), 1f to c1, startY = base - maxH, endY = base)
    val reflection = Brush.verticalGradient(0f to c1.copy(alpha = 0.35f), 1f to Color.Transparent, startY = base + gap, endY = base + gap + maxH * 0.3f)
    for (i in 0 until BARS) {
        val x = i * slot + (slot - barW) / 2
        val bh = max(barW, barLevel(levels, i) * maxH)
        drawRoundRect(up, topLeft = Offset(x, base - bh), size = Size(barW, bh), cornerRadius = CornerRadius(barW / 2))
        drawRoundRect(reflection, topLeft = Offset(x, base + gap), size = Size(barW, bh * 0.3f), cornerRadius = CornerRadius(barW / 2))
        val py = base - max(barW, peaks[i] * maxH) - gap - cap
        drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(x, py), size = Size(barW, cap), cornerRadius = CornerRadius(cap / 2))
    }
}

private fun DrawScope.drawRing(levels: FloatArray, c1: Color, c2: Color) {
    val c = center
    val side = min(size.width, size.height)
    var bass = 0f
    for (b in 0 until 6) bass += levels[b]
    bass /= 6
    val r0 = side * 0.26f * (1f + 0.08f * bass)
    val reach = side * 0.2f
    // Spokes all around: bass at the top, the highest band at the bottom, mirrored left and right.
    val spokes = RING_SPOKES
    val stroke = (2 * PI.toFloat() * r0 / spokes) * 0.5f
    drawCircle(Brush.radialGradient(0f to c1.copy(alpha = 0.30f + 0.25f * bass), 1f to Color.Transparent, center = c, radius = r0 * 1.05f), radius = r0 * 1.05f, center = c)
    for (s in 0 until spokes) {
        val half = s.toFloat() / spokes * 2f
        val t = if (half <= 1f) half else 2f - half
        val v = waveLevel(levels, t.coerceIn(0f, 1f), closed = false)
        val a = -PI.toFloat() / 2 + 2 * PI.toFloat() * s / spokes
        val dx = cos(a); val dy = sin(a)
        val from = r0 + stroke * 1.5f
        val to = from + max(stroke * 0.4f, v * reach)
        drawLine(lerp(c1, c2, t), Offset(c.x + dx * from, c.y + dy * from), Offset(c.x + dx * to, c.y + dy * to), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    drawCircle(c1.copy(alpha = 0.14f), radius = r0, center = c, style = Stroke(width = 8.dp.toPx()))
    drawCircle(lerp(c1, Color.White, 0.4f).copy(alpha = 0.8f), radius = r0, center = c, style = Stroke(width = 1.5.dp.toPx()))
}

private const val FFT_SIZE = 2048
private const val BANDS = 48
private const val BARS = 28
private const val WAVE_POINTS = 2 * BANDS + 1
private const val RING_SPOKES = 96
/** Lifts the upper bands against the bass (see [Spectrum]). */
private const val TILT_DB_PER_OCTAVE = 3f
/** Per second: how fast a level rises to a louder one (a share of the way per second), and falls (level units). */
private const val ATTACK = 30f
private const val RELEASE = 1.5f
private const val PEAK_HOLD_S = 0.35f
private const val PEAK_GRAVITY = 2.5f
private const val COLOR_SAMPLE_PX = 48
