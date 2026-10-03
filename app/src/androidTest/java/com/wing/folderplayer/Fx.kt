package com.wing.folderplayer

import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.playback.RoutingDataSource
import org.junit.Assume
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Shared helpers for instrumentation tests. Endpoints come from `am instrument -e key value`. */
object Fx {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val args get() = InstrumentationRegistry.getArguments()
    fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }
    fun require(vararg names: String) = names.forEach { Assume.assumeTrue("arg $it missing", arg(it) != null) }

    /** Fixture pushed by scripts/emulator/run-instrumentation.sh to shared storage. */
    const val MUSIC_ROOT = "/storage/emulated/0/Music"
    const val FX = "/Music/fixture"
    const val TRICKY = "$FX/Album-A/01 曲 #1+%.flac"

    fun localFile(rel: String) = File("/storage/emulated/0$rel")

    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    fun log(msg: String) = android.util.Log.i("FpTest", msg)

    val sdk get() = Build.VERSION.SDK_INT

    data class PlayResult(val reachedReady: Boolean, val error: PlaybackException?, val durationMs: Long, val positionMs: Long, val ended: Boolean)

    /**
     * Plays [ref] through the production RoutingDataSource in a standalone ExoPlayer on the main thread and
     * reports whether audio actually advanced. [seekToMs] seeks after READY; [untilEnd] waits for STATE_ENDED.
     */
    fun play(ref: SourceRef, playMs: Long = 1500, seekToMs: Long? = null, untilEnd: Boolean = false, timeoutMs: Long = 20_000): PlayResult {
        val inst = InstrumentationRegistry.getInstrumentation()
        val playerRef = AtomicReference<ExoPlayer>()
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val error = AtomicReference<PlaybackException>()
        inst.runOnMainSync {
            val p = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(RoutingDataSource.Factory(ctx)))
                .build()
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                    if (state == Player.STATE_ENDED) { ready.countDown(); ended.countDown() }
                }
                override fun onPlayerError(e: PlaybackException) { error.set(e); ready.countDown(); ended.countDown() }
            })
            val uri = ref.toUriString()
            val item = MediaItem.Builder().setUri(uri).setMediaId(uri)
                .apply { com.wing.folderplayer.data.repo.PlayerRepository.mimeFor(com.wing.folderplayer.data.source.SourcePath.extension(ref.name))?.let { setMimeType(it) } }
                .build()
            p.setMediaItem(item)
            p.prepare()
            p.play()
            playerRef.set(p)
        }
        val gotReady = ready.await(timeoutMs, TimeUnit.MILLISECONDS) && error.get() == null
        var duration = 0L
        var pos = 0L
        if (gotReady) {
            if (seekToMs != null) {
                inst.runOnMainSync { playerRef.get().seekTo(seekToMs) }
            }
            if (untilEnd) ended.await(timeoutMs, TimeUnit.MILLISECONDS) else Thread.sleep(playMs)
            inst.runOnMainSync {
                duration = playerRef.get().duration
                pos = playerRef.get().currentPosition
            }
        }
        val didEnd = ended.count == 0L && error.get() == null
        inst.runOnMainSync { playerRef.get().release() }
        return PlayResult(gotReady, error.get(), duration, pos, didEnd)
    }
}
