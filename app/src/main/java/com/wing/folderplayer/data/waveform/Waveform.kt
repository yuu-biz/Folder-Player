package com.wing.folderplayer.data.waveform

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loudness envelope of a whole audio file: one RMS level (0..1 of full scale) per [binMs] of audio, from the start of
 * the file. While the file is still being measured [complete] is false and [levels] covers only its beginning (read from
 * start to end) or holds NaN for the bins not measured yet (a long file sampled in a coarse-to-fine order).
 */
class Waveform(val binMs: Int, val levels: FloatArray, val durationMs: Long, val complete: Boolean) {

    /**
     * Bar heights (0..1) for [count] equal slices of the time range [startMs, startMs + lengthMs) (a CUE track is a
     * slice of its file). Each bar is the RMS of the bins it covers, relative to the loudest bar, compressed so that
     * quiet passages stay visible and loud scenes stand out. A bin that is NaN has not been measured yet (a long file is
     * measured in a coarse-to-fine order); a bar with no measured bin is NaN while measuring and silent once complete.
     */
    fun bars(startMs: Long, lengthMs: Long, count: Int): FloatArray {
        val out = FloatArray(max(count, 0))
        if (count <= 0 || lengthMs <= 0) return out
        var loudest = 0f
        for (b in 0 until count) {
            val t0 = startMs + lengthMs * b / count
            val t1 = startMs + lengthMs * (b + 1) / count
            val i0 = (t0 / binMs).toInt()
            val i1 = max(i0 + 1, ceil(t1.toDouble() / binMs).toInt())
            if (i0 >= levels.size) {
                out[b] = if (complete) 0f else Float.NaN
                continue
            }
            var power = 0.0
            var measured = 0
            for (i in i0 until min(i1, levels.size)) {
                val v = levels[i]
                if (v.isNaN()) continue
                power += v.toDouble() * v
                measured++
            }
            if (measured == 0) {
                out[b] = if (complete) 0f else Float.NaN
                continue
            }
            val rms = sqrt(power / measured).toFloat()
            out[b] = rms
            if (rms > loudest) loudest = rms
        }
        for (b in out.indices) {
            val v = out[b]
            if (v.isNaN()) continue
            out[b] = if (loudest <= 0f) 0f else (v / loudest).toDouble().pow(CURVE).toFloat()
        }
        return out
    }

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(binMs)
        out.writeLong(durationMs)
        out.writeInt(levels.size)
        val bytes = ByteBuffer.allocate(levels.size * 4).order(ByteOrder.BIG_ENDIAN)
        bytes.asFloatBuffer().put(levels)
        out.write(bytes.array())
    }

    companion object {
        private const val MAGIC = 0x46505746 // "FPWF"
        private const val VERSION = 1
        /** Upper bound of bins per file: about one per second for an hour, finer for shorter files. */
        const val MAX_BINS = 4000
        /** Sanity bound when reading a kept file (a file of unknown duration has 4 bins a second). */
        private const val MAX_STORED_BINS = 2_000_000
        private const val MIN_BIN_MS = 50
        /** Bin length when the duration is not known up front. */
        private const val DEFAULT_BIN_MS = 250
        /** Height = (rms / loudest)^CURVE: -6 dB is about 60 % of the loudest bar, -20 dB about 20 %. */
        private const val CURVE = 0.7

        /** Windows of a long file measured by sampling: about one per second, 100 to 300 (each costs a seek, a few ms). */
        fun windowsFor(durationMs: Long): Int = (durationMs / 1000).toInt().coerceIn(100, 300)

        /**
         * Bin indices in a coarse-to-fine order (bit reversal): the first few already spread over the whole file, so a
         * rough shape shows long before every bin is measured.
         */
        fun sampleOrder(n: Int): IntArray {
            if (n <= 1) return IntArray(max(n, 0))
            var bits = 0
            while ((1 shl bits) < n) bits++
            val out = IntArray(n)
            var k = 0
            for (i in 0 until (1 shl bits)) {
                val r = Integer.reverse(i) ushr (32 - bits)
                if (r < n) out[k++] = r
            }
            return out
        }

        fun binMsFor(durationMs: Long): Int {
            if (durationMs <= 0) return DEFAULT_BIN_MS
            val raw = ceil(durationMs.toDouble() / MAX_BINS).toInt()
            return max(MIN_BIN_MS, (raw + 9) / 10 * 10)
        }

        /** Reads what [write] wrote; a damaged or older file throws [IOException]. */
        fun read(input: DataInputStream): Waveform {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("not a waveform file")
            val binMs = input.readInt()
            val durationMs = input.readLong()
            val n = input.readInt()
            if (binMs <= 0 || n < 0 || n > MAX_STORED_BINS) throw IOException("bad waveform header")
            val bytes = ByteArray(n * 4).also { input.readFully(it) }
            val levels = FloatArray(n)
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(levels)
            return Waveform(binMs, levels, durationMs, complete = true)
        }
    }
}

/**
 * Turns decoded PCM (interleaved, any channel count) into per-bin RMS levels. Channels are averaged as power, so
 * out-of-phase stereo does not cancel out.
 */
class LevelAccumulator(sampleRate: Int, channels: Int, val binMs: Int) {
    private val samplesPerBin = max(1L, sampleRate.toLong() * binMs / 1000 * channels)
    private var sum = 0.0
    private var count = 0L
    private var levels = FloatArray(256)
    private var size = 0

    /** The first [count] samples of [samples]. */
    fun add(samples: FloatArray, count: Int) {
        for (i in 0 until count) add(samples[i])
    }

    fun add(sample: Float) {
        sum += sample.toDouble() * sample
        if (++count >= samplesPerBin) flushBin()
    }

    private fun flushBin() {
        if (size == levels.size) levels = levels.copyOf(size * 2)
        levels[size++] = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
        sum = 0.0
        count = 0
    }

    /** Levels so far; with [final] the last, partly filled bin is kept when it holds at least a quarter of a bin. */
    fun levels(final: Boolean = false): FloatArray {
        if (final && count > 0 && count >= samplesPerBin / 4) flushBin()
        return levels.copyOf(size)
    }

    val binCount: Int get() = size
}

/** Interleaved signed 16-bit little-endian PCM ([length] bytes) as samples in -1..1; returns the number of samples. */
fun pcm16ToFloats(bytes: ByteArray, length: Int, out: FloatArray): Int {
    val n = min(length / 2, out.size)
    for (i in 0 until n) {
        out[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
    }
    return n
}
