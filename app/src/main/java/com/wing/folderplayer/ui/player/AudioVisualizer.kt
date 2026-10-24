package com.wing.folderplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.playback.AudioTap
import com.wing.folderplayer.playback.Spectrum
import kotlinx.coroutines.delay

/**
 * In the place of the lyrics when a song has none: the music that is playing, drawn as it is heard. Bars of its
 * frequency bands (mirrored around the middle, falling back slowly) under the waveform of the last few milliseconds.
 * It follows the player's clock ([AudioTap]), so a pause holds the picture; with nothing played here (a cast) it stays
 * a flat line. Drawn only while [visible] and the track [playing] or paused on screen; the samples are only kept while
 * it is shown.
 */
@Composable
fun AudioVisualizer(visible: Boolean, playing: Boolean, accent: Color, modifier: Modifier = Modifier) {
    DisposableEffect(visible) {
        if (visible) AudioTap.attach()
        onDispose { if (visible) AudioTap.detach() }
    }
    val samples = remember { FloatArray(FFT_SIZE) }
    val spectrum = remember { Spectrum(FFT_SIZE, BANDS) }
    val bars = remember { FloatArray(BANDS) }
    val wave = remember { FloatArray(WAVE_POINTS) }
    val wavePath = remember { Path() }
    var frame by remember { mutableIntStateOf(0) }
    var hasSound by remember { mutableStateOf(false) }
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        var lastNs = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
            lastNs = now
            val got = AudioTap.read(samples)
            if (got) {
                spectrum.analyse(samples, AudioTap.rate)
                // Up at once, down slowly: the bars fall back over about half a second.
                for (b in 0 until BANDS) bars[b] = maxOf(spectrum.levels[b], bars[b] - dt * 1.8f)
                // The waveform: the last WAVE_MS of the block, one point per step.
                val span = minOf(FFT_SIZE, (AudioTap.rate * WAVE_MS / 1000).coerceAtLeast(WAVE_POINTS))
                val from = FFT_SIZE - span
                for (i in 0 until WAVE_POINTS) wave[i] = samples[from + i * span / WAVE_POINTS]
            } else {
                for (b in 0 until BANDS) bars[b] = maxOf(0f, bars[b] - dt * 1.8f)
                wave.fill(0f)
            }
            hasSound = got
            frame++
            // Paused: the picture holds; look again a few times a second only.
            if (!isPlaying) delay(250)
        }
    }
    Canvas(modifier.testTag("audio_visualizer")) {
        frame // redrawn with each frame
        val w = size.width
        val h = size.height
        val mid = h / 2
        val gap = w / BANDS * 0.3f
        val barW = w / BANDS - gap
        val brush = Brush.verticalGradient(
            0f to accent.copy(alpha = 0.15f), 0.5f to accent.copy(alpha = 0.7f), 1f to accent.copy(alpha = 0.15f),
            startY = 0f, endY = h,
        )
        for (b in 0 until BANDS) {
            val bh = maxOf(2f, bars[b] * h * 0.9f)
            drawRoundRect(brush, topLeft = Offset(b * (barW + gap) + gap / 2, mid - bh / 2), size = Size(barW, bh), cornerRadius = CornerRadius(barW / 2))
        }
        val path = wavePath.apply { rewind() }
        val amp = h * 0.35f
        for (i in 0 until WAVE_POINTS) {
            val x = w * i / (WAVE_POINTS - 1)
            val y = mid - wave[i].coerceIn(-1f, 1f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White.copy(alpha = if (hasSound) 0.85f else 0.3f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private const val FFT_SIZE = 2048
private const val BANDS = 32
private const val WAVE_POINTS = 160
/** The waveform shows this much of the music (a few cycles of a low note). */
private const val WAVE_MS = 30
