package com.wing.folderplayer

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.playback.NativePcmDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The JNI FFmpeg/DSD decoder on the device ABI. Records ABI and page size; decodes each fixture format,
 * checks the decoded tone frequency, plays through Media3 with seek and EOF, and checks DST / corrupt handling.
 */
@RunWith(AndroidJUnit4::class)
class NativeDecodeTest {
    private lateinit var local: String
    private fun ref(name: String) = SourceRef(local, "/Formats/$name")

    @Before fun setUp() {
        SourceRegistry.init(Fx.ctx)
        // MediaProvider does not classify DSF/DFF as audio, so in shared storage they are only reachable via SAF.
        // The decoder is exercised on a copy in the app's own external files directory (a LOCAL source root).
        val dir = java.io.File(Fx.ctx.getExternalFilesDir(null), "Formats")
        if (!java.io.File(dir, "sample.dsf").exists()) {
            dir.mkdirs()
            val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            for (name in assets.list("Formats")!!) {
                assets.open("Formats/$name").use { input -> java.io.File(dir, name).outputStream().use { input.copyTo(it) } }
            }
        }
        val cfg = SourceRegistry.savedSources().firstOrNull { it.name == "native-test" }
            ?: com.wing.folderplayer.data.source.SourceConfig(name = "native-test", type = com.wing.folderplayer.data.source.SourceType.LOCAL,
                url = dir.parentFile!!.absolutePath).also { SourceRegistry.upsert(it, null) }
        local = cfg.id
        Fx.log("formats copied: ${dir.list()?.sorted()}")
        val page = Os.sysconf(OsConstants._SC_PAGESIZE)
        Fx.log("ABI=${Build.SUPPORTED_ABIS.joinToString()} pageSize=$page native=${NativeDecoder.isAvailable()} ${NativeDecoder.version()} loadError=${NativeDecoder.loadFailure()}")
        assertTrue("libfpnative must load: ${NativeDecoder.loadFailure()}", NativeDecoder.isAvailable())
    }

    /** Decodes ~1 s of PCM starting at [startSec] and estimates per-channel frequency by zero crossings. */
    private fun toneHz(name: String, startSec: Double = 1.0): Pair<Double, Double> {
        val ds = NativePcmDataSource()
        val uri = android.net.Uri.parse(ref(name).toUriString())
        ds.open(DataSpec(uri))
        val header = ByteArray(44).also { var o = 0; while (o < 44) o += ds.read(it, o, 44 - o) }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt()
        val rate = bb.getInt(24)
        ds.close()
        val ds2 = NativePcmDataSource()
        val frameBytes = channels * 2
        val start = 44 + (startSec * rate).toLong() * frameBytes
        ds2.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
        val want = rate * frameBytes
        val pcm = ByteArray(want)
        var got = 0
        while (got < want) {
            val n = ds2.read(pcm, got, want - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        ds2.close()
        val s = ByteBuffer.wrap(pcm, 0, got).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fun crossings(ch: Int): Int {
            var c = 0; var prev = 0
            var i = ch
            while (i < s.limit()) { val v = s.get(i).toInt(); if ((prev < 0 && v >= 0)) c++; prev = v; i += channels }
            return c
        }
        val secs = got.toDouble() / frameBytes / rate
        return crossings(0) / secs to crossings(if (channels > 1) 1 else 0) / secs
    }

    private fun near(expected: Double, actual: Double) = abs(actual - expected) <= expected * 0.03 + 3

    /** Records which native build the process runs: lib/<abi> of the install, and whether ARM translation is active. */
    @Test fun loadedNativeLibraryAbiIsRecorded() {
        assertTrue(com.wing.folderplayer.playback.NativeDecoder.isAvailable())
        val dir = Fx.ctx.applicationInfo.nativeLibraryDir
        val maps = java.io.File("/proc/self/maps").readLines()
        val translated = maps.any { it.contains("libndk_translation") }
        Fx.log("process ABIs ${android.os.Build.SUPPORTED_ABIS.toList()} nativeLibraryDir=$dir ndk_translation=$translated " +
            "fpnative mapped by name=${maps.any { it.contains("libfpnative.so") }}")
        // Libraries are stored uncompressed and loaded straight from the APK (lib/<abi>/ inside base.apk), so the
        // directory name tells which ABI the package manager selected.
        // An arm64 install on an x86_64 device can only run through the translator.
        if (dir.endsWith("/arm64") && android.os.Build.SUPPORTED_ABIS.first() != "arm64-v8a") assertTrue("arm64 code runs translated", translated)
    }

    @Test fun decodedTonesMatchTheGeneratedFixtures() {
        val cases = mapOf(
            "sample.dsf" to (1000.0 to 1500.0),
            "sample.dff" to (1200.0 to 1800.0),
            "sample.ape" to (330.0 to 330.0),
            "sample.wma" to (880.0 to 880.0),
            "sample-alac.m4a" to (440.0 to 440.0),
        )
        for ((file, exp) in cases) {
            val (l, r) = toneHz(file)
            Fx.log("$file tone L=${"%.1f".format(l)}Hz R=${"%.1f".format(r)}Hz expected=$exp")
            assertTrue("$file left $l vs ${exp.first}", near(exp.first, l))
            assertTrue("$file right $r vs ${exp.second}", near(exp.second, r))
        }
        // Seeking lands on the same tone (exact frame seek inside the decoder).
        val (sl, _) = toneHz("sample.dsf", startSec = 2.5)
        assertTrue("DSF after seek $sl", near(1000.0, sl))
    }

    @Test fun playSeekAndEndThroughMedia3() {
        for ((file, dur) in listOf("sample.dsf" to 4_000L, "sample.dff" to 4_000L, "sample.ape" to 20_000L, "sample.wma" to 20_000L, "sample-alac.m4a" to 20_000L, "sample-aac.m4a" to 20_000L)) {
            val play = Fx.play(ref(file), playMs = 1200)
            Fx.log("$file play: $play")
            assertTrue("$file plays: $play", play.reachedReady && play.positionMs > 400)
            assertTrue("$file duration ${play.durationMs}", abs(play.durationMs - dur) < 600)
            val end = Fx.play(ref(file), seekToMs = dur - 1500, untilEnd = true, timeoutMs = 20_000)
            Fx.log("$file seek+end: $end")
            assertTrue("$file reaches end after seek: $end", end.ended)
        }
    }

    @Test fun dstIsExplicitlyUnsupportedAndCorruptDataIsAnError() {
        val dst = Fx.play(ref("unsupported-dst.dff"))
        Fx.log("DST: ${dst.error?.errorCodeName} ${dst.error?.cause?.message}")
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, dst.error?.errorCode)
        assertTrue(generateSequence<Throwable>(dst.error) { it.cause }.any { it.message?.contains("DST") == true })

        val corrupt = Fx.play(ref("corrupt.wma"), untilEnd = true, timeoutMs = 30_000)
        Fx.log("corrupt.wma: ended=${corrupt.ended} error=${corrupt.error?.errorCodeName} ${corrupt.error?.cause?.message}")
        // Damaged packets are skipped or reported; the process must survive and never report success silently on a fatal error.
        assertTrue(corrupt.ended || corrupt.error != null)
    }
}
