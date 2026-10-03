package com.wing.folderplayer.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.wing.folderplayer.MainActivity
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.playback.NetworkRetryController
import com.wing.folderplayer.playback.PlaybackExportManager
import com.wing.folderplayer.playback.RoutingDataSource
import com.wing.folderplayer.playback.SourceBitmapLoader
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var retry: NetworkRetryController
    private var exporter: PlaybackExportManager? = null

    internal val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
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

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
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

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("MusicService", "Player Error: ${error.errorCodeName} (${error.errorCode}) ${error.message}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_BUFFERING -> android.util.Log.d("MusicService", "Buffering...")
                    androidx.media3.common.Player.STATE_READY -> android.util.Log.d("MusicService", "Ready to play")
                    androidx.media3.common.Player.STATE_ENDED -> android.util.Log.d("MusicService", "Playback ended")
                    else -> {}
                }
            }
        })

        retry = NetworkRetryController(player).also { it.attach() }
        exporter = PlaybackExportManager(applicationContext, player).also { it.attach() }

        mediaSession = MediaLibrarySession.Builder(this, player, object : MediaLibraryService.MediaLibrarySession.Callback {})
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(SourceBitmapLoader(DataSourceBitmapLoader(this)))
            .build()

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
            super.onUpdateNotification(session, startInForegroundRequired)
            return
        }
        pendingNotification = Runnable {
            pendingNotification = null
            lastNotificationAt = SystemClock.uptimeMillis()
            super.onUpdateNotification(session, startInForegroundRequired)
        }.also { notificationHandler.postDelayed(it, wait) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    /** For instrumentation tests (main thread): controllers bound to the session, e.g. the app's own one per activity. */
    internal val connectedControllerCount: Int get() = mediaSession.connectedControllers.size
    internal val isPlayingForTest: Boolean get() = player.isPlaying

    override fun onDestroy() {
        if (current === this) current = null
        pendingNotification?.let { notificationHandler.removeCallbacks(it) }
        unregisterReceiver(becomingNoisyReceiver)
        retry.detach()
        exporter?.detach()
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
