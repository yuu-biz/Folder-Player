package com.wing.folderplayer.ui.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The two colours of the visualiser, taken from the cover: the hue that covers most of it (weighted by how vivid each
 * pixel is) and a second one at least [MIN_HUE_APART] degrees away, or that hue turned by [MIN_HUE_APART] when the cover
 * has one hue only. Both are made bright and saturated enough to stand on the player's dark background. Grey covers
 * (black and white, a plain scan) give none: the theme's colours are used then. Pure Kotlin (no Android graphics).
 */
object VisualizerColors {
    private const val BINS = 24
    private const val MIN_HUE_APART = 40f
    /** Pixels less saturated or darker than this say nothing about the cover's colour. */
    private const val MIN_SATURATION = 0.18f
    private const val MIN_VALUE = 0.15f
    /** Share of the vivid weight a cover needs to have a colour at all. */
    private const val MIN_VIVID_SHARE = 0.04f

    /** Two ARGB colours from [pixels] (ARGB), or null for a grey cover. */
    fun pick(pixels: IntArray): Pair<Int, Int>? {
        if (pixels.isEmpty()) return null
        val weight = FloatArray(BINS)
        val hueSum = FloatArray(BINS)
        val satSum = FloatArray(BINS)
        var vivid = 0f
        val hsv = FloatArray(3)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            hsv(p, hsv)
            if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue
            val w = hsv[1] * hsv[2]
            val bin = ((hsv[0] / 360f) * BINS).toInt().coerceIn(0, BINS - 1)
            weight[bin] += w; hueSum[bin] += hsv[0] * w; satSum[bin] += hsv[1] * w
            vivid += w
        }
        if (vivid < pixels.size * MIN_VIVID_SHARE) return null
        // Neighbouring bins count together, so a hue that sits on a bin edge is not split in two.
        fun around(b: Int) = weight[b] + 0.5f * (weight[(b + 1) % BINS] + weight[(b + BINS - 1) % BINS])
        val first = (0 until BINS).maxByOrNull(::around)!!
        val h1 = hueSum[first] / weight[first]
        val second = (0 until BINS).filter { weight[it] > 0f && hueDistance(hueSum[it] / weight[it], h1) >= MIN_HUE_APART }
            .maxByOrNull(::around)?.takeIf { around(it) >= around(first) * 0.15f }
        val h2 = second?.let { hueSum[it] / weight[it] } ?: ((h1 + MIN_HUE_APART) % 360f)
        val s1 = satSum[first] / weight[first]
        val s2 = second?.let { satSum[it] / weight[it] } ?: s1
        return vividColor(h1, s1, 1f) to vividColor(h2, s2, 0.9f)
    }

    /** Distance of two hues on the circle, 0..180. */
    fun hueDistance(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

    private fun vividColor(h: Float, s: Float, v: Float): Int = argb(h, s.coerceIn(0.5f, 0.85f), v)

    fun hsv(argb: Int, out: FloatArray) {
        val r = (argb shr 16 and 0xff) / 255f
        val g = (argb shr 8 and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        out[0] = if (h < 0) h + 360f else h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    fun argb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
