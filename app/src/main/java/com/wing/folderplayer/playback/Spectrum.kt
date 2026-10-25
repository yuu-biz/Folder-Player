package com.wing.folderplayer.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frequency bands of a block of samples for the visualiser: a Hann-windowed FFT summed into [bands] bands spaced evenly
 * on a log scale from [LOW_HZ] up to [HIGH_HZ] (or half the rate), each as a level 0..1 over [FLOOR_DB] dB. [tiltDbPerOctave]
 * lifts the bands above 1 kHz (and lowers those below) by that much per octave: music has most of its energy in the bass,
 * and without it the upper half of the picture hardly moves.
 * One instance per visualiser (it keeps its buffers); not thread-safe.
 */
class Spectrum(private val size: Int, private val bands: Int, private val tiltDbPerOctave: Float = 0f) {
    init { require(size >= 16 && size and (size - 1) == 0) { "size must be a power of two" } }

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val bits = Integer.numberOfTrailingZeros(size)
    val levels = FloatArray(bands)

    /** Fills [levels] from the last [size] samples of [samples] at [rate] Hz. */
    fun analyse(samples: FloatArray, rate: Int) {
        val offset = samples.size - size
        for (i in 0 until size) { re[i] = samples[offset + i] * window[i]; im[i] = 0f }
        fft()
        val nyquist = rate / 2f
        val high = min(HIGH_HZ, nyquist)
        val binHz = rate.toFloat() / size
        val ratio = ln(high / LOW_HZ)
        for (b in 0 until bands) {
            val f0 = LOW_HZ * kotlin.math.exp(ratio * b / bands)
            val f1 = LOW_HZ * kotlin.math.exp(ratio * (b + 1) / bands)
            val i0 = max(1, (f0 / binHz).toInt())
            val i1 = max(i0 + 1, min(size / 2, (f1 / binHz).toInt() + 1))
            var power = 0f
            for (i in i0 until i1) power = max(power, re[i] * re[i] + im[i] * im[i])
            // Amplitude relative to a full-scale sine through the window (about size / 4).
            val amp = sqrt(power) / (size / 4f)
            val db = 20 * log10(max(amp, 1e-9f)) + tiltDbPerOctave * log2(sqrt(f0 * f1) / 1_000f)
            levels[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f).pow(1.4f)
        }
    }

    /** In-place radix-2 FFT of [re], [im]. */
    private fun fft() {
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len *= 2
        }
    }

    companion object {
        const val LOW_HZ = 40f
        const val HIGH_HZ = 16_000f
        const val FLOOR_DB = -60f
    }
}
