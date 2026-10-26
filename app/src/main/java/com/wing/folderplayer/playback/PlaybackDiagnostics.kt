package com.wing.folderplayer.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * What the playback log could not show before 1.8.0 (2026-10-26: a song on an SMB share went silent and nothing was
 * logged for minutes, until the process was killed): buffering that lasts, the load errors the player retries by itself
 * before it reports an error, and a heartbeat with the position while playback is wanted, so the log shows when the
 * music stopped moving. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackDiagnostics(
    private val player: ExoPlayer,
    private val where: () -> String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var bufferingSince = 0L
    private var bufferingLogged = false

    private val bufferingLog = Runnable {
        if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@Runnable
        bufferingLogged = true
        PlaybackLog.record(PlaybackEventKind.BUFFERING, "for ${seconds(bufferingSince)}s ${where()} ${buffered()}")
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            PlaybackLog.record(
                PlaybackEventKind.HEARTBEAT,
                "${stateName(player.playbackState)} playing=${player.isPlaying} ${where()} ${buffered()}",
            )
            handler.postDelayed(this, heartbeatMs)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingSince == 0L) {
                    bufferingSince = SystemClock.elapsedRealtime()
                    handler.postDelayed(bufferingLog, bufferingMs)
                }
                return
            }
            handler.removeCallbacks(bufferingLog)
            if (bufferingLogged) {
                PlaybackLog.record(PlaybackEventKind.BUFFERING, "ended after ${seconds(bufferingSince)}s: ${stateName(playbackState)} ${where()}")
            }
            bufferingSince = 0L
            bufferingLogged = false
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            handler.removeCallbacks(heartbeat)
            if (playWhenReady) handler.postDelayed(heartbeat, heartbeatMs)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val cause = error.cause?.let { "; cause ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            PlaybackLog.record(
                PlaybackEventKind.LOAD_ERROR,
                PlaybackLogFormat.withoutUserInfo(
                    "${error.javaClass.simpleName}: ${error.message}$cause ${where()} after ${loadEventInfo.loadDurationMs}ms " +
                        "${loadEventInfo.bytesLoaded / 1024}KB${if (wasCanceled) " canceled" else ""}",
                ),
            )
        }
    }

    fun attach() {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        if (player.playWhenReady) handler.postDelayed(heartbeat, heartbeatMs)
    }

    fun detach() {
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(bufferingLog)
    }

    private fun buffered() = "buffered=${player.bufferedPosition / 1000}s"

    private fun seconds(since: Long) = (SystemClock.elapsedRealtime() - since) / 1000

    companion object {
        /** A heartbeat every 5 minutes while playback is wanted; tests make it shorter. */
        @Volatile internal var heartbeatMs = 5 * 60_000L

        /** Buffering is logged once it has lasted this long (a track change buffers briefly every time). */
        @Volatile internal var bufferingMs = 3_000L

        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "STATE_$state"
        }
    }
}
