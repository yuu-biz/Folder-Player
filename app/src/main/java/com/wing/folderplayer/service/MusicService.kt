package com.wing.folderplayer.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wing.folderplayer.BuildConfig
import com.wing.folderplayer.MainActivity
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.playback.NetworkRetryController
import com.wing.folderplayer.playback.PlaybackEventKind
import com.wing.folderplayer.playback.PlaybackLog
import com.wing.folderplayer.playback.PlaybackLogFormat
import com.wing.folderplayer.playback.PlayerNames
import com.wing.folderplayer.playback.PlaybackExportManager
import com.wing.folderplayer.playback.RoutingDataSource
import com.wing.folderplayer.playback.SourceBitmapLoader
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var retry: NetworkRetryController
    private var exporter: PlaybackExportManager? = null
    private lateinit var sleepTimer: SleepTimer
    private lateinit var diagnostics: com.wing.folderplayer.playback.PlaybackDiagnostics

    /** Sleep timer commands (START / CANCEL / GET) from the app's screens, on top of the default commands. */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val accepted = super.onConnect(session, controller)
            val commands = accepted.availableSessionCommands.buildUpon()
                .add(SleepTimer.COMMAND_START).add(SleepTimer.COMMAND_CANCEL).add(SleepTimer.COMMAND_GET)
                .build()
            return MediaSession.ConnectionResult.accept(commands, accepted.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SleepTimer.ACTION_START -> sleepTimer.start(args.getString(SleepTimer.ARG_TYPE), args.getInt(SleepTimer.ARG_VALUE))
                SleepTimer.ACTION_CANCEL -> sleepTimer.cancel()
                SleepTimer.ACTION_GET -> Unit
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, sleepTimer.state()))
        }

        // Which app (system UI, Bluetooth, a car, this app's own controller) asked to play, pause or change the track.
        // Deprecated in Media3 1.2.0, but the only hook of this version that names the controller of a command.
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            PlayerNames.loggedCommand(playerCommand)?.let {
                PlaybackLog.record(PlaybackEventKind.COMMAND, "$it from ${controller.packageName} playWhenReady=${player.playWhenReady}")
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

    internal val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackLog.record(PlaybackEventKind.NOISY, "audio output lost, pausing ${where()}")
                player.pause()
            }
        }
    }

    /** The current file's name and position for the log (mediaIds are `fpsrc://` refs, without credentials). */
    private fun where(): String {
        val name = SourceUris.parse(player.currentMediaItem?.mediaId)?.name ?: "-"
        return "$name at ${player.currentPosition / 1000}s"
    }

    private var retryWakeLock: PowerManager.WakeLock? = null
    private var retryWifiLock: WifiManager.WifiLock? = null

    /** Held while [NetworkRetryController] waits to retry: the idle player holds no lock and the screen may be off. */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF, as ExoPlayer and the DLNA session use it
    private fun keepAwakeForRetry(on: Boolean) {
        if (on) {
            if (retryWakeLock == null) retryWakeLock = runCatching {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FolderPlayer:retry")
                    .apply { setReferenceCounted(false); acquire(6 * 60 * 1000L) }
            }.getOrNull()
            if (retryWifiLock == null) retryWifiLock = runCatching {
                applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FolderPlayer:retry")
                    ?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        } else {
            runCatching { retryWakeLock?.release() }; retryWakeLock = null
            runCatching { retryWifiLock?.release() }; retryWifiLock = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        PlaybackLog.init(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PlaybackLog.recordProcessExits(applicationContext)
        PlaybackLog.record(
            PlaybackEventKind.SERVICE_START,
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}",
        )
        SourceRegistry.init(applicationContext)

        // Notification / lock screen tap: the running activity (if any) shows the full player for the current track;
        // CLEAR_TOP drops a system picker left on top of it, SINGLE_TOP delivers the intent instead of a second activity.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // fpsrc:// is resolved per sourceId (Local/SAF/WebDAV/SMB/FTP); file://, content:// and http(s):// fall back
        // to Media3's DefaultDataSource. No credentials are attached to requests globally.
        val dataSourceFactory = RoutingDataSource.Factory(this)

        // Configure LoadControl with larger buffers for smoother network streaming
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000, // Min buffer 30s
                60000, // Max buffer 60s
                2500,  // Buffer for playback 2.5s
                5000   // Buffer for playback after rebuffer 5s
            )
            .build()

        // The audio sink is tapped for the full player's visualiser (see AudioTap) and swaps left / right when asked (before
        // the speed change); MediaCodec and FFmpeg renderers share it.
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                com.wing.folderplayer.playback.TappingAudioSink(
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(com.wing.folderplayer.playback.ChannelSwapAudioProcessor()))
                        .build(),
                )
        }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .setLoadControl(loadControl)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()

        player.volume = 1.0f
        PlaybackPreferences(applicationContext).let { prefs ->
            player.setPlaybackSpeed(prefs.getPlaybackSpeed())
            com.wing.folderplayer.playback.ChannelSwapAudioProcessor.enabled = prefs.getSwapChannels()
        }

        // Also the playback log: what started, paused or stopped playback and why.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("MusicService", "Player Error: ${error.errorCodeName} (${error.errorCode}) ${error.message}")
                val root = generateSequence<Throwable>(error) { it.cause }.take(10).last()
                val cause = if (root === error) "" else "; cause ${root.javaClass.name}: ${root.message}"
                PlaybackLog.record(
                    PlaybackEventKind.ERROR,
                    PlaybackLogFormat.withoutUserInfo("${error.errorCodeName} ${where()}: ${error.message}$cause"),
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> android.util.Log.d("MusicService", "Buffering...")
                    Player.STATE_READY -> android.util.Log.d("MusicService", "Ready to play")
                    Player.STATE_ENDED -> {
                        android.util.Log.d("MusicService", "Playback ended")
                        PlaybackLog.record(PlaybackEventKind.STATE, "ENDED ${where()}")
                    }
                    Player.STATE_IDLE -> PlaybackLog.record(PlaybackEventKind.STATE, "IDLE ${where()}")
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlaybackLog.record(
                    if (playWhenReady) PlaybackEventKind.PLAY else PlaybackEventKind.PAUSE,
                    "${PlayerNames.playWhenReadyReason(reason)} ${where()}",
                )
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                PlaybackLog.record(PlaybackEventKind.SUPPRESSED, "${PlayerNames.suppressionReason(playbackSuppressionReason)} ${where()}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                PlaybackLog.record(PlaybackEventKind.TRACK, "${PlayerNames.transitionReason(reason)} ${SourceUris.parse(mediaItem?.mediaId)?.name ?: "-"}")
            }
        })

        diagnostics = com.wing.folderplayer.playback.PlaybackDiagnostics(player, ::where).also { it.attach() }
        retry = NetworkRetryController(player, keepAwake = ::keepAwakeForRetry).also { it.attach() }
        exporter = PlaybackExportManager(applicationContext, player).also { it.attach() }

        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(SourceBitmapLoader(DataSourceBitmapLoader(this)))
            .build()
        sleepTimer = SleepTimer(player, Handler(Looper.getMainLooper()), { state -> mediaSession.setSessionExtras(state) }).also { it.attach() }

        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(this) {
            // A MediaItem only has a display title when tag titles are used and the file has no title tag.
            override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? = metadata.title ?: metadata.displayTitle
        })

        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    /**
     * A track change fires several player events within milliseconds and each one re-posts the notification. Android
     * sheds updates above ~5/s per package ("Package enqueue rate ... Shedding"), which can drop the *last* update and
     * leave the previous track's title/cover in the notification. Updates are therefore coalesced (trailing edge, so
     * the final state is always posted). Media3 passes startInForegroundRequired=true on every update while playing;
     * a delay of at most [MIN_NOTIFICATION_INTERVAL_MS] is far inside the foreground-service start deadline.
     */
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var pendingNotification: Runnable? = null
    private var lastNotificationAt = 0L

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        pendingNotification?.let { notificationHandler.removeCallbacks(it) }
        pendingNotification = null
        val wait = lastNotificationAt + MIN_NOTIFICATION_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait <= 0) {
            lastNotificationAt = SystemClock.uptimeMillis()
            updateNotificationSafely(session, startInForegroundRequired)
            return
        }
        pendingNotification = Runnable {
            pendingNotification = null
            lastNotificationAt = SystemClock.uptimeMillis()
            updateNotificationSafely(session, startInForegroundRequired)
        }.also { notificationHandler.postDelayed(it, wait) }
    }

    /**
     * Android 12+ refuses startForeground() from the background. A network retry (the player went IDLE after the error,
     * so Media3 left the foreground, and the retry starts it again with the screen off) can hit that; the exception
     * must not take the app down, playback goes on and the next update tries again.
     */
    private fun updateNotificationSafely(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: IllegalStateException) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                android.util.Log.w("MusicService", "foreground start not allowed in the background; notification not updated")
            } else throw e
        }
    }

    // Memory pressure before a kill (lmkd or a vendor killer); UI_HIDDEN only means the activity left the screen.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != TRIM_MEMORY_UI_HIDDEN) PlaybackLog.record(PlaybackEventKind.MEMORY, "trim level $level ${where()}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    /** For instrumentation tests (main thread): controllers bound to the session, e.g. the app's own one per activity. */
    internal val connectedControllerCount: Int get() = mediaSession.connectedControllers.size
    internal val isPlayingForTest: Boolean get() = player.isPlaying
    internal val playbackSpeedForTest: Float get() = player.playbackParameters.speed

    override fun onDestroy() {
        if (current === this) current = null
        pendingNotification?.let { notificationHandler.removeCallbacks(it) }
        unregisterReceiver(becomingNoisyReceiver)
        sleepTimer.detach()
        diagnostics.detach()
        retry.detach()
        keepAwakeForRetry(false)
        exporter?.detach()
        PlaybackLog.record(PlaybackEventKind.SERVICE_STOP, where())
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    internal companion object {
        const val MIN_NOTIFICATION_INTERVAL_MS = 300L

        /** Running instance, for instrumentation tests only (the shell cannot send AUDIO_BECOMING_NOISY on API 34+). */
        @Volatile internal var current: MusicService? = null
    }
}
