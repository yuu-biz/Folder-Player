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
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.waveform.Waveform
import com.wing.folderplayer.data.waveform.WaveformRepository
import com.wing.folderplayer.playback.NativePcmDataSource
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Waveform on the seek bar: a file is measured through MediaCodec (WAV, AAC in .m4a, FLAC) or the native decoder
 * (WMA), loud and quiet parts come out as such, the result is kept, the switch turns it off, and the full player draws
 * it while the seek bar still seeks.
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
        // AAC in .m4a: the native decoder declines it, MediaCodec measures it. The others: native decoder (FLAC: w04).
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
}
