package com.wing.folderplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audio the player is sending out, for the full player's visualiser: the last seconds as mono samples, each with its
 * place on the player's clock, and that clock. [TappingAudioSink] writes it from the playback thread, the visualiser reads
 * the samples that are being heard ([read]): the sink takes audio up to most of a second before it is heard, so the newest
 * samples are not the ones to show. Nothing is copied while no visualiser is attached ([attach]).
 *
 * The service and the screens run in one process, so this is a plain object. A cast (DLNA) plays elsewhere: nothing here.
 */
object AudioTap {
    private val viewers = AtomicInteger()

    /** A visualiser is shown: the sink starts keeping samples. */
    fun attach() { viewers.incrementAndGet() }
    fun detach() { if (viewers.decrementAndGet() <= 0) { viewers.set(0); clear() } }
    val active: Boolean get() = viewers.get() > 0

    /** Samples kept: about 2.7 s at the stored rate (at most [MAX_RATE]). */
    private const val CAPACITY = 1 shl 17
    /** Stored rate at most this: a 192 kHz file is kept at 48 kHz (every fourth frame), enough to draw. */
    private const val MAX_RATE = 48_000
    /** The clock is moved on from its last reading at most this far (a stalled sink does not run away). */
    private const val MAX_EXTRAPOLATE_US = 250_000L
    /** Less than this between where samples should follow and where they do is rounding, not a jump. */
    private const val JUMP_US = 50_000L

    private val lock = Any()
    private val ring = FloatArray(CAPACITY)
    /** Samples written since [clear]. */
    private var written = 0L
    /** Stored samples per second; 0 = nothing to show. */
    @Volatile var rate = 0
        private set
    /** The player time (µs, the sink's clock) of sample 0, and of the next sample expected. */
    private var startUs = Long.MIN_VALUE
    private var nextUs = Long.MIN_VALUE
    private var step = 1
    /** Frames skipped by the decimation, carried between buffers. */
    private var phase = 0

    @Volatile private var positionUs = Long.MIN_VALUE
    @Volatile private var positionAtNs = 0L
    @Volatile private var playing = false

    fun clear() {
        synchronized(lock) {
            written = 0; startUs = Long.MIN_VALUE; nextUs = Long.MIN_VALUE; phase = 0
        }
    }

    /** A new stream ([sampleRate] frames per second): what was kept belongs to the old one. */
    internal fun configure(sampleRate: Int) {
        synchronized(lock) {
            step = maxOf(1, (sampleRate + MAX_RATE - 1) / MAX_RATE)
            rate = if (sampleRate > 0) sampleRate / step else 0
            written = 0; startUs = Long.MIN_VALUE; nextUs = Long.MIN_VALUE; phase = 0
        }
    }

    internal fun onPosition(us: Long) { positionUs = us; positionAtNs = System.nanoTime() }
    internal fun onPlaying(now: Boolean) { if (!now) onPosition(currentUs()); playing = now }

    /** The sink's clock now: its last reading, moved on while playing. */
    private fun currentUs(): Long {
        val p = positionUs
        if (p == Long.MIN_VALUE || !playing) return p
        return p + minOf((System.nanoTime() - positionAtNs) / 1_000, MAX_EXTRAPOLATE_US)
    }

    /**
     * [frames] mono samples (a channel mix, -1..1) starting at [atUs] on the sink's clock. Times that do not follow the last
     * samples (encoder delay trimmed, a gapless change of track, a seek without a flush) move the clock of what is kept
     * instead of throwing it away, so the picture does not go blank.
     */
    internal fun write(frames: FloatArray, count: Int, atUs: Long, sourceRate: Int) {
        synchronized(lock) {
            if (rate == 0) return
            if (nextUs == Long.MIN_VALUE) { written = 0; phase = 0; startUs = atUs }
            else if (kotlin.math.abs(atUs - nextUs) > JUMP_US) startUs = atUs - written * 1_000_000L / rate
            var i = phase
            while (i < count) {
                ring[(written % CAPACITY).toInt()] = frames[i]
                written++
                i += step
            }
            phase = i - count
            nextUs = atUs + count * 1_000_000L / sourceRate
        }
    }

    /**
     * The [out.size] samples that end where playback is now; false when there are none (nothing playing here, or a moment
     * after a seek). Paused: the same picture each time.
     */
    fun read(out: FloatArray): Boolean {
        val now = currentUs()
        synchronized(lock) {
            if (rate == 0 || startUs == Long.MIN_VALUE || now == Long.MIN_VALUE) return false
            val end = (now - startUs) * rate / 1_000_000
            val begin = end - out.size
            val oldest = maxOf(0L, written - CAPACITY)
            if (end <= oldest || begin >= written) return false
            for (k in out.indices) {
                val s = begin + k
                out[k] = if (s < oldest || s >= written) 0f else ring[(s % CAPACITY).toInt()]
            }
            return true
        }
    }
}

/**
 * The player's audio sink with [AudioTap] on its input: what the sink takes from each buffer (it may take a buffer in
 * several calls) is mixed to mono and handed over with its time. Linear PCM only (8, 16, 24, 32 bit and float); for
 * anything else the tap is off. A failure in the tap is swallowed: it must never stop playback.
 */
@OptIn(UnstableApi::class)
class TappingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var frameBytes = 0
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = Long.MIN_VALUE
    private var bufferStart = 0
    private var mono = FloatArray(4096)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        try {
            val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
            encoding = if (pcm) inputFormat.pcmEncoding else C.ENCODING_INVALID
            val bytes = when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> 0
            }
            channels = inputFormat.channelCount
            sampleRate = inputFormat.sampleRate
            frameBytes = bytes * channels
            if (bytes == 0 || channels <= 0 || sampleRate <= 0) frameBytes = 0
            AudioTap.configure(if (frameBytes > 0) sampleRate else 0)
        } catch (t: Throwable) {
            frameBytes = 0
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // Where this buffer began, also while nobody looks: a viewer that comes in half-way through it gets the right time.
        if (buffer !== lastBuffer || presentationTimeUs != lastPts) {
            lastBuffer = buffer; lastPts = presentationTimeUs; bufferStart = buffer.position()
        }
        if (frameBytes == 0 || !AudioTap.active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val before = buffer.position()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val after = buffer.position()
        if (after > before) {
            try {
                tap(buffer, before, after, presentationTimeUs + (before - bufferStart).toLong() / frameBytes * 1_000_000L / sampleRate)
            } catch (t: Throwable) {
                frameBytes = 0
            }
        }
        if (handled) lastBuffer = null
        return handled
    }

    /** Bytes [from, to) of [buffer] (little-endian PCM, as Android decodes) mixed to mono. */
    private fun tap(buffer: ByteBuffer, from: Int, to: Int, atUs: Long) {
        val frames = (to - from) / frameBytes
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        val bytes = frameBytes / channels
        var p = from
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += sample(buffer, p, bytes)
                p += bytes
            }
            mono[f] = sum / channels
        }
        AudioTap.write(mono, frames, atUs, sampleRate)
    }

    private fun sample(b: ByteBuffer, i: Int, bytes: Int): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((b.get(i).toInt() and 0xff) - 128) / 128f
        C.ENCODING_PCM_16BIT -> ((b.get(i).toInt() and 0xff) or (b.get(i + 1).toInt() shl 8)).toShort() / 32768f
        C.ENCODING_PCM_24BIT -> (((b.get(i).toInt() and 0xff) or ((b.get(i + 1).toInt() and 0xff) shl 8) or (b.get(i + 2).toInt() shl 16))) / 8_388_608f
        C.ENCODING_PCM_32BIT -> le32(b, i) / 2_147_483_648f
        C.ENCODING_PCM_FLOAT -> java.lang.Float.intBitsToFloat(le32(b, i))
        else -> 0f
    }

    private fun le32(b: ByteBuffer, i: Int): Int =
        (b.get(i).toInt() and 0xff) or ((b.get(i + 1).toInt() and 0xff) shl 8) or ((b.get(i + 2).toInt() and 0xff) shl 16) or (b.get(i + 3).toInt() shl 24)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p != AudioSink.CURRENT_POSITION_NOT_SET) AudioTap.onPosition(p)
        return p
    }

    override fun play() { super.play(); AudioTap.onPlaying(true) }
    override fun pause() { super.pause(); AudioTap.onPlaying(false) }
    // A flush (seek) keeps the sink playing: only what was kept goes.
    override fun flush() { super.flush(); lastBuffer = null; AudioTap.clear() }
    override fun reset() { super.reset(); lastBuffer = null; AudioTap.onPlaying(false); AudioTap.clear() }
}
