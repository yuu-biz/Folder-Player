package com.wing.folderplayer

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.RandomAccessReader
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.waveform.Waveform
import com.wing.folderplayer.data.waveform.WaveformRepository
import com.wing.folderplayer.playback.NativePcmDataSource
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Waveform on the seek bar: a file is measured through the native decoder (MediaCodec when it declines one), loud and
 * quiet parts come out as such, a long file appears whole within seconds (also from a NAS-like source), the result is
 * kept, the switch turns it off, and the full player draws it while the seek bar still seeks.
 */
@RunWith(AndroidJUnit4::class)
class WaveformUiTest : UiTestBase() {
    private lateinit var formats: String
    private lateinit var formatsDir: File
    private val repo get() = WaveformRepository.get(Fx.ctx)
    private val cacheDir get() = File(Fx.ctx.cacheDir, WaveformRepository.CACHE_DIR)
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    @Before fun setUp() {
        SourceRegistry.init(Fx.ctx)
        val dir = File(Fx.ctx.getExternalFilesDir(null), "Formats")
        formatsDir = dir
        dir.mkdirs()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in assets.list("Formats")!!) {
            val f = File(dir, name)
            if (!f.exists()) assets.open("Formats/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        writeSteps(File(dir, "steps.wav"))
        val cfg = SourceRegistry.savedSources().firstOrNull { it.name == "native-test" }
            ?: SourceConfig(name = "native-test", type = SourceType.LOCAL, url = dir.parentFile!!.absolutePath).also { SourceRegistry.upsert(it, null) }
        formats = cfg.id
        cacheDir.deleteRecursively()
        repo.settings.enabled = true
        repo.settings.maxMinutes = 120
    }

    @After fun tearDown() {
        repo.settings.enabled = true
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MusicService.current?.let { it.becomingNoisyReceiver.onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
            }
        }
    }

    /** 8 kHz mono WAV: 2 s of a quiet 440 Hz tone, then 2 s of a loud one. */
    private fun writeSteps(file: File) {
        val rate = 8000
        val pcm = ByteBuffer.allocate(rate * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * 4) {
            val amp = if (i < rate * 2) 0.05 else 0.8
            pcm.putShort((amp * 32767 * sin(2 * PI * 440 * i / rate)).toInt().toShort())
        }
        file.outputStream().use {
            it.write(NativePcmDataSource.wavHeader(rate, 1, pcm.capacity().toLong()))
            it.write(pcm.array())
        }
    }

    /** All emissions of the waveform flow for [name] in the test formats folder; the last one is the result. */
    private fun measure(name: String): List<Waveform?> = runBlocking {
        withTimeout(60_000) { repo.waveform(SourceRef(formats, "/Formats/$name")).toList() }
    }

    private fun FloatArray.mean(from: Int, to: Int) = (from until to).map { this[it] }.average()

    @Test fun w01_quietAndLoudPartsOfAWavFile() {
        val runs = measure("steps.wav")
        assertNull("first emission clears the old track's waveform", runs.first())
        val w = runs.last()
        assertNotNull("measured", w)
        w!!
        Fx.log("steps.wav: ${w.levels.size} bins of ${w.binMs} ms, duration ${w.durationMs}")
        assertTrue(w.complete)
        assertEquals(4000.0, w.durationMs.toDouble(), 50.0)
        val half = w.levels.size / 2
        val quiet = w.levels.mean(2, half - 2)
        val loud = w.levels.mean(half + 2, w.levels.size - 2)
        Fx.log("quiet $quiet loud $loud")
        assertEquals(0.05 / Math.sqrt(2.0), quiet, 0.01)
        assertEquals(0.8 / Math.sqrt(2.0), loud, 0.03)
        val bars = w.bars(0, w.durationMs, 8)
        assertTrue("bars ${bars.toList()}", bars.take(3).all { it < 0.3f } && bars.drop(5).all { it > 0.9f })

        // Kept: the second request reads the file instead of decoding again.
        assertTrue(cacheDir.listFiles()!!.any { it.name.endsWith(".wf") })
        val again = measure("steps.wav")
        assertEquals("from the cache: null, then the result", 2, again.size)
        assertEquals(w.levels.toList(), again.last()!!.levels.toList())
    }

    @Test fun w02_otherFormats() {
        // All through the native decoder (AAC in .m4a too, which playback leaves to Media3). FLAC: w04.
        for (name in listOf("sample-aac.m4a", "sample-alac.m4a", "sample.wma", "sample.ape", "sample.dsf")) {
            val w = measure(name).last()
            assertNotNull("$name measured", w)
            w!!
            Fx.log("$name: ${w.levels.size} bins of ${w.binMs} ms, duration ${w.durationMs}, mean ${w.levels.average()}")
            assertTrue("$name complete", w.complete)
            assertTrue("$name has a duration", w.durationMs > 1000)
            assertEquals("$name: bins cover the duration", w.durationMs.toDouble() / w.binMs, w.levels.size.toDouble(), 3.0)
            assertTrue("$name: a tone is not silent", w.levels.average() > 0.05)
        }
    }

    @Test fun w03_offMeansNoWaveform() {
        repo.settings.enabled = false
        assertEquals(listOf<Waveform?>(null), measure("steps.wav"))
        repo.settings.enabled = true
        repo.settings.maxMinutes = 30
        assertNotNull("a short file is under the limit", measure("steps.wav").last())
    }

    @Test fun w04_fullPlayerDrawsItAndTheSeekBarStillSeeks() {
        toBrowser()
        onUi { player.playFolder(SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "${Fx.FX}/Long"), null) }
        until(15_000, "playing") { player.uiState.value.isPlaying && player.uiState.value.currentMediaId?.contains("Long") == true }
        toPlayer()
        until(60_000, "waveform measured") { player.waveform.value?.waveform?.complete == true }
        until(5_000, "waveform drawn") { exists("seek_waveform") }
        val w = player.waveform.value!!.waveform
        Fx.log("long.flac: ${w.levels.size} bins of ${w.binMs} ms")
        assertEquals(600_000.0, w.durationMs.toDouble(), 1000.0)
        node("seek_bar").performTouchInput { click(Offset(width * 0.5f, centerY)) }
        until(5_000, "seeked to the middle (${player.uiState.value.currentPosition})") {
            player.uiState.value.currentPosition in 280_000..330_000
        }
        // Off: the plain bar comes back.
        repo.settings.enabled = false
        until(5_000, "waveform gone") { !exists("seek_waveform") }
    }

    /** 8 kHz mono WAV of 60 s with "scenes": quiet talk, a loud part, medium, a short loud peak, quiet again. */
    private fun writeScenes(file: File) {
        val rate = 8000
        val scenes = listOf(20 to 0.08, 10 to 0.7, 15 to 0.15, 5 to 0.9, 10 to 0.05)
        val frames = scenes.sumOf { it.first } * rate
        val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        for ((seconds, amp) in scenes) repeat(seconds * rate) {
            val wobble = 1 + 0.3 * sin(2 * PI * 0.7 * i / rate)
            pcm.putShort((amp * wobble / 1.3 * 32767 * sin(2 * PI * 330 * i / rate)).toInt().toShort())
            i++
        }
        file.outputStream().use {
            it.write(NativePcmDataSource.wavHeader(rate, 1, pcm.capacity().toLong()))
            it.write(pcm.array())
        }
    }

    @Test fun w05_loudScenesDrawTallBarsQuietOnesShortOnes() {
        writeScenes(File(formatsDir, "scenes.wav"))
        toBrowser()
        onUi { player.playFolder(SourceRef(formats, "/Formats"), "/Formats/scenes.wav") }
        until(15_000, "playing scenes.wav") { player.uiState.value.isPlaying && player.uiState.value.currentMediaId?.contains("scenes") == true }
        onUi { player.playPause() }
        toPlayer()
        until(30_000, "waveform measured") { player.waveform.value?.waveform?.complete == true }
        until(5_000, "waveform drawn") { exists("seek_waveform") }
        compose.waitForIdle()
        val screen = compose.onRoot().captureToImage().asAndroidBitmap()
        File(Fx.ctx.getExternalFilesDir(null), "waveform-player.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val bmp = node("seek_waveform").captureToImage().asAndroidBitmap()
        fun lum(x: Int, y: Int) = bmp.getPixel(x, y).let { ((it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff)) / 3 }
        val mid = bmp.height / 2
        val top = bmp.height / 8
        // Gaps between the bars show the background: the darkest pixel of a row is its background.
        val bgMid = (0 until bmp.width).minOf { lum(it, mid) }
        val bgTop = (0 until bmp.width).minOf { lum(it, top) }
        fun tallBars(from: Float, to: Float) = ((bmp.width * from).toInt() until (bmp.width * to).toInt())
            .count { x -> lum(x, mid) > bgMid + 30 && lum(x, top) > bgTop + 30 }
        val quiet = tallBars(0.03f, 0.30f)
        val loud = tallBars(0.36f, 0.48f)
        val peak = tallBars(0.77f, 0.81f)
        val end = tallBars(0.86f, 0.98f)
        Fx.log("tall bar columns: quiet $quiet loud $loud peak $peak end $end (bg mid $bgMid top $bgTop, ${bmp.width}x${bmp.height})")
        assertEquals("quiet talk reaches no higher than a low bar", 0, quiet)
        assertEquals("quiet end", 0, end)
        assertTrue("loud scene reaches the top", loud > 10)
        assertTrue("short loud peak reaches the top", peak > 3)
    }

    /** Measures [ref] from scratch; logs and returns the result, the time to the first partial waveform and the total time. */
    private fun timed(ref: SourceRef, what: String): Triple<Waveform, Long, Long> {
        cacheDir.deleteRecursively()
        val start = SystemClock.elapsedRealtime()
        var first = -1L
        val runs = runBlocking {
            withTimeout(180_000) { repo.waveform(ref).onEach { if (it != null && first < 0) first = SystemClock.elapsedRealtime() - start }.toList() }
        }
        val total = SystemClock.elapsedRealtime() - start
        val w = runs.last()
        assertNotNull("$what measured", w)
        Fx.log("$what: first waveform after $first ms, complete after $total ms, ${w!!.levels.size} bins of ${w.binMs} ms, ${w.levels.count { it.isNaN() }} not measured")
        return Triple(w, first, total)
    }

    /** The user waits for the waveform while the track plays: a 10-minute local FLAC must show within seconds. */
    @Test fun w06_aTenMinuteFileIsMeasuredWithinSeconds() {
        val (w, _, total) = timed(SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "${Fx.FX}/Long/long.flac"), "long.flac")
        assertTrue(w.complete)
        assertEquals(600_000.0, w.durationMs.toDouble(), 1000.0)
        assertTrue("every bin measured", w.levels.none { it.isNaN() })
        assertTrue("measured in $total ms (limit 5000)", total < 5_000)
    }

    /** 20 minutes: quiet for 8, loud for 4, quiet for 8 (8 kHz mono WAV, written in chunks). */
    private fun writeLongScenes(file: File) {
        val rate = 8000
        val scenes = listOf(480 to 0.08, 240 to 0.8, 480 to 0.08)
        val frames = scenes.sumOf { it.first } * rate
        file.outputStream().buffered(1 shl 20).use { out ->
            out.write(NativePcmDataSource.wavHeader(rate, 1, frames * 2L))
            val chunk = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            for ((seconds, amp) in scenes) repeat(seconds) {
                chunk.clear()
                repeat(rate) { chunk.putShort((amp * 32767 * sin(2 * PI * 330 * i++ / rate)).toInt().toShort()) }
                out.write(chunk.array())
            }
        }
    }

    @Test fun w07_aLongFileShowsItsLoudPartQuickly() {
        val file = File(formatsDir, "long-scenes.wav")
        if (file.length() != 44L + 1200 * 8000 * 2) writeLongScenes(file)
        val (w, _, total) = timed(SourceRef(formats, "/Formats/long-scenes.wav"), "long-scenes.wav")
        assertTrue(w.complete)
        assertEquals(1_200_000.0, w.durationMs.toDouble(), 1000.0)
        val bars = w.bars(0, w.durationMs, 10)
        Fx.log("long-scenes.wav bars ${bars.toList()}")
        assertTrue("quiet start ${bars.toList()}", bars.take(3).all { it < 0.3f })
        assertTrue("loud middle ${bars.toList()}", bars[4] > 0.9f && bars[5] > 0.9f)
        assertTrue("quiet end ${bars.toList()}", bars.drop(7).all { it < 0.3f })
        assertTrue("measured in $total ms (limit 5000)", total < 5_000)
    }

    /**
     * The user's case: a 30-minute MP3 took ~30 s and drew itself in scattered bars that kept changing height. It must
     * be done within seconds and appear whole: at most a coarse picture and the final one, neither with gaps. The file
     * (44.1 kHz stereo VBR MP3, pink noise, loud from minute 12 to 18) is too big for the test assets; it is made by
     * `instrument.sh <serial> push-long-mp3`.
     */
    @Test fun w08_aThirtyMinuteMp3AppearsWholeWithinSeconds() {
        val file = File(formatsDir, "long-scenes.mp3")
        assertTrue("$file missing: run instrument.sh <serial> push-long-mp3", file.length() > 1_000_000)
        cacheDir.deleteRecursively()
        val start = SystemClock.elapsedRealtime()
        val shown = mutableListOf<Pair<Long, Waveform>>()
        runBlocking {
            withTimeout(180_000) {
                repo.waveform(SourceRef(formats, "/Formats/long-scenes.mp3")).collect { w ->
                    if (w != null) shown += (SystemClock.elapsedRealtime() - start) to w
                }
            }
        }
        val total = SystemClock.elapsedRealtime() - start
        Fx.log("long-scenes.mp3: shown at ${shown.map { it.first }} ms, complete after $total ms, ${shown.lastOrNull()?.second?.levels?.size} bins")
        val w = shown.last().second
        assertTrue(w.complete)
        assertEquals(1_800_000.0, w.durationMs.toDouble(), 2000.0)
        for ((at, s) in shown) assertTrue("no gaps in the waveform shown at $at ms", s.bars(0, s.durationMs, 200).none { it.isNaN() })
        assertTrue("shown ${shown.size} times (a coarse picture and the final one at most)", shown.size <= 2)
        val bars = w.bars(0, w.durationMs, 10)
        Fx.log("long-scenes.mp3 bars ${bars.toList()}")
        assertTrue("quiet start ${bars.toList()}", bars.take(4).all { it < 0.3f })
        assertTrue("loud middle ${bars.toList()}", bars[4] > 0.9f && bars[5] > 0.9f)
        assertTrue("quiet end ${bars.toList()}", bars.drop(6).all { it < 0.3f })
        assertTrue("measured in $total ms (limit 5000)", total < 5_000)
    }

    /** A local file that answers each read after [delayMs], like a NAS on Wi-Fi (one request per read). */
    private class SlowReader(file: File, private val delayMs: Long) : RandomAccessReader {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = raf.length()
        override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            Thread.sleep(delayMs)
            raf.seek(position)
            return raf.read(buffer, offset, len)
        }
        override fun close() = raf.close()
    }

    /**
     * The NAS case ("almost no change" with 1.3.1): with 5 ms per read (one request each, like SMB on Wi-Fi) the
     * 30-minute MP3 and the 10-minute FLAC appear within seconds and right. The MediaCodec path of 1.3.1 (MediaExtractor
     * reads a few KB per request) is measured for comparison and only logged.
     */
    @Test fun w09_aSlowSourceStillGivesTheWaveformWithinSeconds() {
        val mp3 = File(formatsDir, "long-scenes.mp3")
        assertTrue("$mp3 missing: run instrument.sh <serial> push-long-mp3", mp3.length() > 1_000_000)
        fun run(file: File, native: Boolean): Pair<Waveform?, Long> {
            val start = SystemClock.elapsedRealtime()
            val w = runBlocking {
                withTimeoutOrNull(60_000) { repo.measureFile({ SlowReader(file, 5) }, file.extension, 0, network = true, native = native) }
            }
            return w to SystemClock.elapsedRealtime() - start
        }
        val (codec, codecMs) = run(mp3, native = false)
        Fx.log("long-scenes.mp3, MediaCodec path, 5 ms per read: ${if (codec == null) "not done after" else "done in"} $codecMs ms")
        val (w, ms) = run(mp3, native = true)
        Fx.log("long-scenes.mp3, native path, 5 ms per read: done in $ms ms")
        assertNotNull(w)
        val bars = w!!.bars(0, w.durationMs, 10)
        Fx.log("long-scenes.mp3 over a slow source: bars ${bars.toList()}")
        assertTrue("no gaps", w.bars(0, w.durationMs, 200).none { it.isNaN() })
        assertTrue("loud middle ${bars.toList()}", bars[4] > 0.9f && bars[5] > 0.9f)
        assertTrue("quiet start and end ${bars.toList()}", bars.take(4).all { it < 0.3f } && bars.drop(6).all { it < 0.3f })
        assertTrue("measured in $ms ms (limit 4000)", ms < 4_000)

        val (flac, flacMs) = run(Fx.localFile("${Fx.FX}/Long/long.flac"), native = true)
        Fx.log("long.flac, native path, 5 ms per read: done in $flacMs ms")
        assertNotNull(flac)
        assertEquals(600_000.0, flac!!.durationMs.toDouble(), 1000.0)
        assertTrue("long.flac is not silent", flac.levels.average() > 0.05)
        assertTrue("long.flac measured in $flacMs ms (limit 4000)", flacMs < 4_000)
    }
}
