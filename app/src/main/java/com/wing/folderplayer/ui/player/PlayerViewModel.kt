package com.wing.folderplayer.ui.player

import com.wing.folderplayer.data.playlist.PlaylistManager
import com.wing.folderplayer.data.playlist.Playlist
import com.wing.folderplayer.data.playlist.PlaylistItem

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.repo.PlayerRepository
import com.wing.folderplayer.data.repo.TitleMode
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.readText
import com.wing.folderplayer.data.lyrics.LyricsRepository
import com.wing.folderplayer.data.lyrics.LyricsResult
import com.wing.folderplayer.data.ai.AlbumInfoRepository
import com.wing.folderplayer.utils.LyricLine
import com.wing.folderplayer.utils.CueParser
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import android.os.Environment
import java.io.File

enum class TimerType { TIME, SONGS }

data class PlayerUiState(
    val currentTitle: String = "No Song Playing",
    val currentArtist: String = "",
    /** Folder image URI (fpsrc) or embedded picture bytes. */
    val coverUri: Any? = null,
    /** Embedded picture used when the folder image cannot be displayed. */
    val coverFallback: ByteArray? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val duration: Long = 0L,
    val currentPosition: Long = 0L,
    val lyrics: List<LyricLine> = emptyList(),
    /** False for plain-text lyrics without timestamps (shown without line sync). */
    val lyricsSynced: Boolean = true,
    /** Where the lyrics came from, e.g. "LRC", "Embedded", "Lyric API", "AI (model)". */
    val lyricsSource: String = "",
    val translatedLyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1,
    val currentMediaId: String? = null,
    val currentFolderName: String = "",
    val audioInfo: String = "",
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,

    val playlist: List<androidx.media3.common.MediaItem> = emptyList(),
    val coverDisplaySize: String = "STANDARD", // STANDARD or LARGE
    val autoNextFolder: Boolean = false,
    val backgroundStyle: String = "GRADIENT",

    // Buffering state
    val isBuffering: Boolean = false,
    val bufferedPosition: Long = 0L,

    // Album Info (AI / NFO)
    val albumInfo: String? = null,
    val artistInfo: String? = null,
    val albumInfoCacheKey: String? = null, // To ensure cache consistency
    val isFetchingAlbumInfo: Boolean = false,
    val albumInfoFromCache: Boolean = false,

    // Playlist Management
    val activePlaylistId: String = "default",
    val activePlaylistName: String = "Default",
    val activePlaylistItems: List<PlaylistItem> = emptyList(),
    val allPlaylists: List<Playlist> = emptyList(),

    // Sleep Timer
    val sleepTimerActive: Boolean = false,
    val sleepTimerValue: Int = 0,
    val sleepTimerType: TimerType = TimerType.TIME,
    val sleepTimerLabel: String = "0 min",

    /** Last playback error shown to the user (e.g. "server cannot seek"). */
    val playbackError: String? = null,

    // NFO / AI album info
    val albumInfoFromNfo: Boolean = false,
    val albumInfoNfoTracks: List<com.wing.folderplayer.data.nfo.NfoTrack> = emptyList(),
    val albumInfoAiModel: String? = null,
    val albumInfoCanSave: Boolean = false,
    val nfoSaveResult: String? = null,
    val nfoNeedsOverwriteConfirm: Boolean = false,

    // Explicit AI lyrics request
    val lyricsRequestRunning: Boolean = false,
    val lyricsError: String? = null,

    /** Set by every play request, before the track has loaded (or failed): the player must stay reachable. */
    val playRequested: Boolean = false,

    /** There is a track to move to (follows repeat and shuffle). */
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
) {
    /** There is a track (playing, paused, loading, failed or restored from the last session) to show a player for. */
    val hasTrack: Boolean get() = currentMediaId != null || playRequested
}

class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Parts of uiState for screens that must not recompose with the position updates (about once a second).
    private fun <R> part(f: (PlayerUiState) -> R): StateFlow<R> =
        _uiState.map(f).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, f(_uiState.value))
    val hasTrack: StateFlow<Boolean> = part { it.hasTrack }
    val currentMediaId: StateFlow<String?> = part { it.currentMediaId }
    val allPlaylists: StateFlow<List<Playlist>> = part { it.allPlaylists }
    val coverDisplaySize: StateFlow<String> = part { it.coverDisplaySize }
    val backgroundStyle: StateFlow<String> = part { it.backgroundStyle }
    val miniState: StateFlow<MiniPlayerState> = part { it.mini() }
    val progressFraction: StateFlow<Float> = part { if (it.duration > 1) (it.currentPosition.toFloat() / it.duration).coerceIn(0f, 1f) else 0f }

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("PlayerViewModel", "Coroutine failure", throwable)
    }

    /** The controller connection has been handled (connected, failed, or never attempted in safe mode). */
    private val _controllerReady = MutableStateFlow(false)
    val controllerReady: StateFlow<Boolean> = _controllerReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var player: Player? = null

    private var repository: PlayerRepository? = null
    private var lyricsRepository: LyricsRepository? = null
    private var albumInfoRepository: AlbumInfoRepository? = null

    private var metadataJob: kotlinx.coroutines.Job? = null
    private var albumInfoJob: kotlinx.coroutines.Job? = null
    private val lyricsCache = mutableMapOf<String, LyricsResult>()

    /** Folder whose contents are in the queue (for next-folder playback and restore). */
    private var currentFolder: SourceRef? = null
    private var playbackPreferences: PlaybackPreferences? = null
    private var sourcePreferences: com.wing.folderplayer.data.prefs.SourcePreferences? = null
    private var lyricPreferences: com.wing.folderplayer.data.prefs.LyricPreferences? = null
    private var isRestoring = false
    private var pendingPlayIntent: String? = null
    private var lastMediaIdBeforeIntent: String? = null
    private var playlistManager: PlaylistManager? = null
    private val audioInfoCache = mutableMapOf<String, String>()
    private var appContext: Context? = null

    // Sleep Timer internals
    private var sleepTimerDeadlineMs: Long = 0L
    private var remainingSongsCount: Int = 0

    private fun repo(): PlayerRepository = repository!!

    fun initializeController(callerContext: Context) {
        // The ViewModel outlives the Activity (recreation, language change): never keep or bind with the Activity context.
        // A MediaController bound through a destroyed Activity crashes on release ("Service not registered") and leaks it.
        val context = callerContext.applicationContext
        appContext = context
        SourceRegistry.init(context.applicationContext)
        if (playbackPreferences == null) {
            playbackPreferences = PlaybackPreferences(context)
        }
        if (repository == null) {
            repository = PlayerRepository(context.applicationContext)
        }
        repo().titleMode = if (playbackPreferences?.getTitleMode() == "TAGS") TitleMode.TAGS else TitleMode.FILENAME
        if (sourcePreferences == null) {
            sourcePreferences = com.wing.folderplayer.data.prefs.SourcePreferences(context)
        }
        if (lyricPreferences == null) {
            lyricPreferences = com.wing.folderplayer.data.prefs.LyricPreferences(context)
        }
        if (lyricsRepository == null) {
            lyricsRepository = LyricsRepository(context.applicationContext, lyricPreferences!!)
        }
        if (albumInfoRepository == null) {
            albumInfoRepository = AlbumInfoRepository(context.applicationContext, lyricPreferences!!)
        }
        if (playlistManager == null) {
            playlistManager = PlaylistManager(context)

            // Sync initial playlist state (Async load)
            val activeId = playbackPreferences!!.getActivePlaylistId()
            val all = playlistManager!!.getAllPlaylists()
            val currentActual = all.find { it.id == activeId } ?: all.first()

            _uiState.value = _uiState.value.copy(
                activePlaylistId = currentActual.id,
                activePlaylistName = currentActual.name,
                activePlaylistItems = currentActual.items,
                allPlaylists = all
            )
        }
        if (mediaControllerFuture != null) return

        // Safety Check: Detect "Init" folder trigger
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val resetTrigger = File(musicDir, "Init")
        val resetTriggerLower = File(musicDir, "init")

        if (resetTrigger.exists() || resetTriggerLower.exists()) {
            android.util.Log.w("PlayerViewModel", "Safe Mode Trigger Detected! Clearing persistence.")
            playbackPreferences?.clearAll()
            sourcePreferences?.clearAll()
            lyricPreferences?.clear()
            // We do NOT delete the folder, user must do it manually to re-enable persistence.
            // We just skip restoration logic below.
            _controllerReady.value = true
            return
        }

        // Restore cached UI state immediately for instant feedback
        val lastMediaId = playbackPreferences?.getLastMediaId()
        playbackPreferences?.getCachedMetadata()?.let { cached ->
            _uiState.value = _uiState.value.copy(
                currentTitle = cached.title,
                currentArtist = cached.artist,
                currentFolderName = cached.folderName,
                audioInfo = cached.audioInfo,
                coverUri = cached.coverUri,
                lyrics = cached.lyrics.toList(),
                currentMediaId = lastMediaId
            )

            // Also seed the lyrics cache to prevent re-fetching
            if (lastMediaId != null && cached.lyrics.isNotEmpty()) {
                lyricsCache[lastMediaId] = LyricsResult(cached.lyrics.toList(), true, "")
            }
        }

        // Load initial cover size and auto next folder setting
        val size = playbackPreferences?.getCoverDisplaySize() ?: "STANDARD"
        val autoNext = playbackPreferences?.getAutoNextFolder() ?: false
        _uiState.value = _uiState.value.copy(
            coverDisplaySize = size,
            autoNextFolder = autoNext,
            backgroundStyle = playbackPreferences?.getBackgroundStyle() ?: "GRADIENT",
        )

        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        // Tests can delay the handling of the connection (a slow service bind) to check what the UI does meanwhile.
        val connectDelay = controllerConnectDelayMsForTest
        val onConnected: java.util.concurrent.Executor = if (connectDelay > 0) {
            java.util.concurrent.Executor { r -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, connectDelay) }
        } else MoreExecutors.directExecutor()
        mediaControllerFuture?.addListener({
            try {
                val controller = mediaControllerFuture?.get() ?: return@addListener
                player = controller
                setupPlayerListener()
                updatePlaybackState()

                currentFolder = playbackPreferences?.getLastFolder()

                // High-precision Restoration Detection
                val savedMediaId = playbackPreferences?.getLastMediaId()
                val playerMediaId = controller.currentMediaItem?.mediaId
                val playerState = controller.playbackState
                val isPlaying = controller.isPlaying

                if (savedMediaId != null && !userStartedPlayback) {
                    val isPlayerEmpty = controller.mediaItemCount == 0
                    val isMismatched = playerMediaId != savedMediaId
                    val isInterrupted = !isPlaying && playerState != Player.STATE_READY

                    if (isPlayerEmpty || (isMismatched && isInterrupted)) {
                        isRestoring = true
                        android.util.Log.d("PlayerViewModel", "Detected state drift or cold start. Restoring last known song")
                    }
                }

                // The current track is known from the controller at once, before its title / metadata are complete:
                // a screen may be waiting for "is there a track" (notification tap on a new activity without cache).
                if (playerMediaId != null && !isRestoring && _uiState.value.currentMediaId == null) {
                    _uiState.value = _uiState.value.copy(currentMediaId = playerMediaId)
                }

                updateMetadata()

                if (isRestoring) {
                    restoreLastState()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "MediaController Init Error", e)
                _error.value = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_player_init)
            } finally {
                _controllerReady.value = true
            }
        }, onConnected)
    }

    /** Restore of the last session; cancelled as soon as the user starts something else. */
    private var restoreJob: kotlinx.coroutines.Job? = null

    /** Set by any user play request; a controller that connects later must not restore the old session over it. */
    private var userStartedPlayback = false

    /**
     * The MediaController connects asynchronously; a play request issued before that (e.g. right after a cold start)
     * waits for it instead of being dropped.
     */
    private suspend fun awaitPlayer(): Player? {
        player?.let { return it }
        val future = mediaControllerFuture ?: return null
        val controller = kotlinx.coroutines.suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) {} }, MoreExecutors.directExecutor())
        }
        return player ?: controller
    }

    /** Incremented by every user play request; delayed work of an older request checks it before touching the player. */
    private var playRequestGeneration = 0

    private fun cancelRestore() {
        userStartedPlayback = true
        playRequestGeneration++
        restoreJob?.cancel()
        restoreJob = null
        isRestoring = false
    }

    private fun restoreLastState() {
        val prefs = playbackPreferences ?: return
        val folder = prefs.getLastFolder() ?: return
        val mediaId = prefs.getLastMediaId() ?: return
        if (SourceRegistry.get(folder.sourceId) == null) { isRestoring = false; return }

        restoreJob = viewModelScope.launch(exceptionHandler) {
            try {
                if (SourceUris.isCueTrackId(mediaId)) {
                    val audio = SourceUris.parse(mediaId) ?: return@launch
                    val cue = SourceRef(audio.sourceId, SourcePath.baseName(audio.path) + ".cue")
                    playCueSheetInternal(cue, mediaId, prefs.getLastPosition(), playWhenReady = false)
                } else if (mediaId.lowercase().endsWith(".cue")) {
                    playCueSheetInternal(SourceUris.parse(mediaId) ?: return@launch, null, prefs.getLastPosition(), playWhenReady = false)
                } else {
                    playFolderInternal(folder, SourceUris.parse(mediaId)?.path, prefs.getLastPosition(), playWhenReady = false)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "restore failed: ${e.message}")
                isRestoring = false
            }
        }
    }

    private fun setupPlayerListener() {
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // When we transition to a new item, cancel any existing metadata work
                // to prevent older song data from 'flickering' in
                _uiState.value = _uiState.value.copy(
                    // No item: the queue was emptied (session ended), not a track change.
                    currentTitle = if (mediaItem != null) "Switching Track.." else _uiState.value.currentTitle,
                    lyrics = emptyList(),
                    translatedLyrics = emptyList(),
                    currentLyricIndex = -1,
                    playbackError = null,
                )
                metadataJob?.cancel()
                aiLyricsJob?.cancel()
                _uiState.value = _uiState.value.copy(lyricsRequestRunning = false, lyricsError = null)
                updateMetadata()

                if (mediaItem == null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    checkAndPlayNextFolder()
                }

                // Sleep Timer: Songs
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _uiState.value.sleepTimerActive && _uiState.value.sleepTimerType == TimerType.SONGS) {
                    remainingSongsCount--
                    if (remainingSongsCount <= 0) {
                        player?.pause()
                        resetSleepTimer()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            sleepTimerValue = remainingSongsCount,
                            sleepTimerLabel = "$remainingSongsCount songs"
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    checkAndPlayNextFolder()
                    resetSleepTimer()
                }
                // Update buffering state
                val buffering = playbackState == Player.STATE_BUFFERING
                _uiState.value = _uiState.value.copy(isBuffering = buffering)
                updatePlaybackState()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _uiState.value = _uiState.value.copy(playbackError = PlaybackErrorText.describe(error))
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Media3 "plays" a file whose only audio track no decoder supports by advancing the clock in silence
                // (e.g. FLAC on Android 8.0, which has no FLAC decoder). Say so instead of pretending to play.
                val audio = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                    val mime = audio.first().getTrackFormat(0).sampleMimeType ?: "?"
                    _uiState.value = _uiState.value.copy(
                        playbackError = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_format_not_decodable, mime, android.os.Build.VERSION.RELEASE)
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
                if (isPlaying) {
                    _uiState.value = _uiState.value.copy(playbackError = null)
                    startProgressLoop()
                    // Re-check metadata after 1.5 seconds to catch bitrates that populate after buffering
                    viewModelScope.launch(exceptionHandler) {
                        kotlinx.coroutines.delay(1500)
                        updateMetadata()
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_TRACKS_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    updateMetadata()
                }
                // Whether next / previous have a target (also while paused).
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    updatePlaybackState()
                }
            }
        })
    }

    private var progressJob: kotlinx.coroutines.Job? = null

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch(exceptionHandler) {
            while (player?.isPlaying == true) {
                updatePlaybackState()
                checkSleepTimer()
                kotlinx.coroutines.delay(990) // Update approx every 1 second to reduce log spam
            }
        }
    }

    private fun checkSleepTimer() {
        val state = _uiState.value
        if (!state.sleepTimerActive || state.sleepTimerType != TimerType.TIME) return

        val now = System.currentTimeMillis()
        if (now >= sleepTimerDeadlineMs) {
            player?.pause()
            resetSleepTimer()
        } else {
            val remainingMins = ((sleepTimerDeadlineMs - now + 59999) / 60000).toInt()
            if (remainingMins != state.sleepTimerValue) {
                _uiState.value = _uiState.value.copy(
                    sleepTimerValue = remainingMins,
                    sleepTimerLabel = "$remainingMins min"
                )
            }
        }
    }

    fun startSleepTimer(type: TimerType, value: Int) {
        if (value <= 0) {
            resetSleepTimer()
            return
        }

        val label = if (type == TimerType.TIME) "$value min" else "$value songs"
        _uiState.value = _uiState.value.copy(
            sleepTimerActive = true,
            sleepTimerType = type,
            sleepTimerValue = value,
            sleepTimerLabel = label
        )

        if (type == TimerType.TIME) {
            sleepTimerDeadlineMs = System.currentTimeMillis() + (value * 60 * 1000L)
        } else {
            remainingSongsCount = value
        }
    }

    fun resetSleepTimer() {
        _uiState.value = _uiState.value.copy(
            sleepTimerActive = false,
            sleepTimerValue = 0,
            sleepTimerLabel = "0 min"
        )
        sleepTimerDeadlineMs = 0L
        remainingSongsCount = 0
    }

    private fun updateMetadata() {
        val p = player ?: return
        val currentMediaItem = p.currentMediaItem ?: return
        val metadata = p.mediaMetadata
        val mediaId = currentMediaItem.mediaId
        val titleFromMetadata = (metadata.title ?: metadata.displayTitle ?: currentMediaItem.mediaMetadata.displayTitle)?.toString()

        // Protection against metadata updates for the OLD song during transitions
        val pending = pendingPlayIntent
        if (pending != null) {
            val isChangeDetected = if (pending == "ANY_NEW") {
                mediaId != lastMediaIdBeforeIntent
            } else {
                mediaId == pending
            }

            if (!isChangeDetected) {
                // Still waiting for player to reach the new state or the target song.
                return
            }
            pendingPlayIntent = null // Target reached!
        }

        // Guard: If we have an item but NO title yet, the service is still parsing metadata.
        if (titleFromMetadata.isNullOrBlank()) {
            if (isRestoring || p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE) {
                return
            }
        }

        if (!titleFromMetadata.isNullOrBlank()) {
            isRestoring = false
        }

        val items = mutableListOf<MediaItem>()
        for (i in 0 until p.mediaItemCount) {
            items.add(p.getMediaItemAt(i))
        }

        val ref = SourceUris.parse(mediaId)
        val folderName = cleanFolderName(ref)

        // Reset album info if we changed folders
        if (folderName != _uiState.value.currentFolderName) {
            albumInfoJob?.cancel()
            _uiState.value = _uiState.value.copy(albumInfo = null, artistInfo = null, isFetchingAlbumInfo = false)
        }
        val rawTitle = titleFromMetadata ?: "No Song Playing"
        val cleanTitle = if (rawTitle.contains('.') && SourcePath.extension(rawTitle) in MediaTypes.AUDIO) {
            rawTitle.substringBeforeLast('.')
        } else {
            rawTitle
        }

        // Keep the folder context in sync with what the player is actually playing.
        if (!isRestoring && ref != null) {
            val parent = ref.parent
            if (parent != null && parent != currentFolder && currentMediaItem.mediaMetadata.extras?.getBoolean(PlayerRepository.EXTRA_IS_CUE_TRACK) != true) {
                currentFolder = parent
            }
        }

        val audioInfo = computeAudioInfo(p, mediaId)

        val currentArtistImmediate = metadata.artist?.toString() ?: ""
        val targetMediaId = currentMediaItem.mediaId

        val cached = lyricsCache[targetMediaId]

        _uiState.value = _uiState.value.copy(
            currentTitle = cleanTitle,
            currentArtist = currentArtistImmediate,
            currentFolderName = folderName,
            coverUri = metadata.artworkUri?.toString() ?: metadata.artworkData,
            coverFallback = if (metadata.artworkUri != null) metadata.artworkData else null,
            audioInfo = audioInfo,
            isPlaying = p.isPlaying,
            currentMediaId = targetMediaId,
            playlist = items,
            lyrics = cached?.lines ?: listOf(LyricLine(0L, ".. Loading Lyrics ..")),
            lyricsSynced = cached?.synced ?: true,
            lyricsSource = cached?.source ?: "",
            currentLyricIndex = -1
        )

        metadataJob?.cancel()
        metadataJob = viewModelScope.launch(exceptionHandler) {
            val lyrics = cached ?: run {
                val loaded = lyricsRepository?.load(currentMediaItem, cleanTitle, currentArtistImmediate, p.duration)
                    ?: LyricsResult(emptyList(), true, "")
                // Do not apply an answer for a song that is no longer playing.
                if (p.currentMediaItem?.mediaId != targetMediaId) return@launch
                // The user asked for AI lyrics while this automatic lookup was running: keep their result.
                if (targetMediaId in explicitAiLyrics) lyricsCache[targetMediaId]?.let { return@run it }
                lyricsCache[targetMediaId] = loaded
                loaded
            }

            if (p.currentMediaItem?.mediaId != targetMediaId) return@launch

            _uiState.value = _uiState.value.copy(
                lyrics = lyrics.lines,
                lyricsSynced = lyrics.synced,
                lyricsSource = lyrics.source,
                translatedLyrics = lyrics.translation,
                duration = p.duration.takeIf { it > 0 } ?: 1L,
                shuffleModeEnabled = p.shuffleModeEnabled,
                repeatMode = p.repeatMode
            )

            // Save for next restart (instant cache)
            playbackPreferences?.saveCachedMetadata(
                com.wing.folderplayer.data.prefs.CachedMetadata(
                    title = cleanTitle,
                    artist = currentArtistImmediate,
                    folderName = folderName,
                    audioInfo = audioInfo,
                    coverUri = metadata.artworkUri?.toString(),
                    lyrics = if (lyrics.synced) lyrics.lines.toTypedArray() else emptyArray()
                )
            )

            // Save playback state
            if (!isRestoring && p.playbackState != Player.STATE_IDLE) {
                playbackPreferences?.savePlaybackState(currentFolder, targetMediaId, p.currentPosition)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun computeAudioInfo(p: Player, mediaId: String): String {
        var audioInfo = ""
        for (groupIndex in 0 until p.currentTracks.groups.size) {
            val group = p.currentTracks.groups[groupIndex]
            if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            val format = group.getTrackFormat(0)
            val mime = format.sampleMimeType?.lowercase() ?: ""
            val extension = p.currentMediaItem?.mediaMetadata?.extras?.getString("file_ext") ?: ""
            val displayFormat = when {
                extension in MediaTypes.EXTRA_AUDIO -> extension.uppercase()
                mime.contains("flac") -> "FLAC"
                mime.contains("alac") || mime.contains("apple") && mime.contains("lossless") -> "ALAC"
                mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                mime.contains("ogg") || mime.contains("vorbis") -> "OGG"
                mime.contains("opus") -> "OPUS"
                mime.contains("aac") -> "AAC"
                mime.contains("mp4") || mime.contains("m4a") -> "M4A"
                mime.contains("raw") && extension == "m4a" -> "ALAC"
                else -> {
                    val fallback = extension.uppercase().take(4).ifEmpty { "AUDIO" }
                    if (mime.isNotEmpty()) "$fallback ($mime)" else fallback
                }
            }

            val cachedInfo = audioInfoCache[mediaId]
            if (cachedInfo != null && cachedInfo.contains("kbps")) return cachedInfo

            var bitrateStr = ""
            if (format.bitrate > 0) bitrateStr = snapBitrate(format.bitrate / 1000.0, extension)
            val sampleRate = format.sampleRate

            // Calculate from file size and duration
            if (bitrateStr.isEmpty() && p.duration > 0) {
                val fileSize = p.currentMediaItem?.mediaMetadata?.extras?.getLong("file_size", 0L) ?: 0L
                if (fileSize > 0) {
                    val durationSec = p.duration / 1000.0
                    bitrateStr = snapBitrate(((fileSize * 8) / durationSec) / 1000.0, extension)
                }
            }

            val samplerate = if (sampleRate > 0) {
                val rate = sampleRate / 1000.0
                if (rate % 1 == 0.0) "${rate.toInt()}kHz" else "${String.format("%.1f", rate)}kHz"
            } else ""

            audioInfo = listOfNotNull(displayFormat, bitrateStr.ifEmpty { null }, samplerate.ifEmpty { null }).joinToString(" | ")
            if (bitrateStr.isNotEmpty()) audioInfoCache[mediaId] = audioInfo
            break
        }
        return audioInfo
    }

    fun toggleShuffle() {
        player?.let { p ->
            p.shuffleModeEnabled = !p.shuffleModeEnabled
            _uiState.value = _uiState.value.copy(shuffleModeEnabled = p.shuffleModeEnabled)
        }
    }

    fun toggleRepeatMode() {
        player?.let { p ->
            val nextMode = when (p.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            p.repeatMode = nextMode
            _uiState.value = _uiState.value.copy(repeatMode = p.repeatMode)
        }
    }

    fun toggleAutoNextFolder() {
        val nextValue = !_uiState.value.autoNextFolder
        _uiState.value = _uiState.value.copy(autoNextFolder = nextValue)
        playbackPreferences?.saveAutoNextFolder(nextValue)
    }

    private fun checkAndPlayNextFolder() {
        if (!_uiState.value.autoNextFolder) return
        viewModelScope.launch(exceptionHandler) {
            moveNextFolder()
        }
    }

    private suspend fun moveNextFolder() {
        val folder = currentFolder ?: return
        val parent = folder.parent ?: return

        val siblings = try { repo().list(parent) } catch (e: Exception) { emptyList() }.filter { it.isDirectory }
        if (siblings.isEmpty()) return

        val sortOption = sourcePreferences?.getDirectorySort(parent) ?: sourcePreferences?.getDefaultSort()
            ?: com.wing.folderplayer.data.prefs.SourcePreferences.SortOption("NAME", true)
        val sortedSiblings = repo().sortFiles(siblings, sortOption.field, sortOption.ascending)

        val currentIndex = sortedSiblings.indexOfFirst { it.path == folder.path }
        if (currentIndex != -1 && currentIndex < sortedSiblings.size - 1) {
            val nextFolder = sortedSiblings[currentIndex + 1]
            playFolderInternal(nextFolder.ref, null, 0L, playWhenReady = true)
        }
    }

    private fun snapBitrate(bitrateKbps: Double, extension: String): String {
        if (bitrateKbps <= 0) return ""

        var capped = bitrateKbps
        if (extension.equals("mp3", ignoreCase = true) && capped > 320.5) {
            capped = 320.0
        }

        val standards = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)

        val closest = standards.minByOrNull { Math.abs(it - capped) } ?: 0
        val diff = Math.abs(closest.toDouble() - capped)
        if (diff < 10.0 || diff < (capped * 0.05)) {
            return "${closest}kbps"
        }

        return "${capped.toInt()}kbps"
    }

    private fun cleanFolderName(ref: SourceRef?): String {
        if (ref == null) return ""
        val parts = SourcePath.segments(ref.path)
        if (parts.size < 2) return SourceRegistry.get(ref.sourceId)?.name ?: "Root"
        val folderIndex = parts.size - 2
        val cleanFolder = cleanString(parts[folderIndex])
        // If the folder name is very short (e.g., "CD1"), prepend the parent folder
        if (cleanFolder.length <= 6 && folderIndex >= 1) {
            return "${cleanString(parts[folderIndex - 1])} - $cleanFolder"
        }
        return cleanFolder
    }

    private fun cleanString(input: String): String {
        return input
            .replace(Regex("\\{.*?\\}"), "") // Remove {...}
            .replace(Regex("\\[.*?\\]"), "") // Remove [...]
            .replace(Regex("(?i)\\s+flac"), "") // Remove " FLAC" (case insensitive)
            .replace(Regex("\\s+"), " ") // Collapse multiple spaces
            .trim()
    }

    /** Localized error text; unreadable local files point the user to SAF folders (Android 11+ storage rules). */
    private fun errorText(e: Throwable): String {
        val ctx = appContext
        if (ctx != null && e is com.wing.folderplayer.data.source.SourceException.PermissionDenied) {
            return ctx.getString(com.wing.folderplayer.R.string.player_error_local_needs_saf)
        }
        return PlaybackErrorText.describe(e)
    }

    /** Folder of the current track (album info / NFO lookups). */
    fun currentTrackFolder(): SourceRef? {
        val item = player?.currentMediaItem ?: return null
        val extras = item.mediaMetadata.extras
        val cuePath = extras?.getString(PlayerRepository.EXTRA_CUE_PATH)
        val ref = SourceUris.parse(item.mediaId) ?: return null
        return if (cuePath != null) SourceRef(ref.sourceId, cuePath).parent else ref.parent
    }

    fun fetchAlbumInfo(forceRegenerate: Boolean = false) {
        val state = _uiState.value
        val folderName = state.currentFolderName
        if (folderName.isEmpty() || folderName == "No Song Playing" || folderName == "Root") return
        val folder = currentTrackFolder()
        val repo = albumInfoRepository ?: return
        albumInfoJob?.cancel()
        _uiState.value = _uiState.value.copy(isFetchingAlbumInfo = true, albumInfo = null, artistInfo = null)
        albumInfoJob = viewModelScope.launch(exceptionHandler) {
            val r = repo.load(folder, folderName, state.currentArtist, forceRegenerate)
            if (currentTrackFolder() != folder) return@launch
            _uiState.value = _uiState.value.copy(
                isFetchingAlbumInfo = false,
                albumInfo = r.album,
                artistInfo = r.artist,
                albumInfoFromCache = r.fromCache,
                albumInfoFromNfo = r.nfo != null,
                albumInfoNfoTracks = r.nfo?.tracks.orEmpty(),
                albumInfoAiModel = r.model,
                albumInfoCanSave = r.aiGenerated && r.error == null && repo.canSave(folder),
                nfoSaveResult = null,
                nfoNeedsOverwriteConfirm = false,
            )
            lastAlbumInfo = r
        }
    }

    private var lastAlbumInfo: com.wing.folderplayer.data.ai.AlbumInfo? = null

    /** Writes the shown AI description to Info.nfo; asks for confirmation first if Info.nfo exists. */
    fun saveAlbumInfoToNfo(overwrite: Boolean) {
        val info = lastAlbumInfo ?: return
        val folder = currentTrackFolder() ?: return
        val repo = albumInfoRepository ?: return
        viewModelScope.launch(exceptionHandler) {
            val r = repo.saveToNfo(folder, _uiState.value.currentFolderName, info, overwrite)
            _uiState.value = _uiState.value.copy(
                nfoNeedsOverwriteConfirm = r is com.wing.folderplayer.data.nfo.NfoSaveResult.NeedsConfirmation,
                nfoSaveResult = when (r) {
                    com.wing.folderplayer.data.nfo.NfoSaveResult.Saved -> "SAVED"
                    com.wing.folderplayer.data.nfo.NfoSaveResult.NeedsConfirmation -> null
                    is com.wing.folderplayer.data.nfo.NfoSaveResult.NotWritable -> "READ_ONLY:" + r.reason
                    is com.wing.folderplayer.data.nfo.NfoSaveResult.Failed -> "FAILED:" + r.reason
                },
            )
        }
    }

    fun dismissNfoSaveState() {
        _uiState.value = _uiState.value.copy(nfoSaveResult = null, nfoNeedsOverwriteConfirm = false)
    }

    private var aiLyricsJob: kotlinx.coroutines.Job? = null

    /** Tracks whose shown lyrics come from an explicit "Get AI lyrics" request (not replaced by automatic lookups). */
    private val explicitAiLyrics = java.util.Collections.synchronizedSet(HashSet<String>())

    /** User asked for AI lyrics (works even if automatic lookup is off; never without endpoint/key/model). */
    fun requestAiLyrics(regenerate: Boolean) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val repo = lyricsRepository ?: return
        val mediaId = item.mediaId
        val state = _uiState.value
        aiLyricsJob?.cancel()
        _uiState.value = state.copy(lyricsRequestRunning = true, lyricsError = null)
        aiLyricsJob = viewModelScope.launch(exceptionHandler) {
            val r = repo.fetchAi(item, state.currentTitle, state.currentArtist, p.duration, regenerate)
            if (p.currentMediaItem?.mediaId != mediaId) return@launch // song changed meanwhile
            if (r.error != null) {
                _uiState.value = _uiState.value.copy(lyricsRequestRunning = false, lyricsError = r.error)
            } else {
                lyricsCache[mediaId] = r
                explicitAiLyrics.add(mediaId)
                _uiState.value = _uiState.value.copy(
                    lyricsRequestRunning = false, lyrics = r.lines, lyricsSynced = r.synced,
                    lyricsSource = r.source, translatedLyrics = r.translation,
                )
            }
        }
    }

    /** Current track for casting: source ref + display metadata. */
    fun currentCastItem(): Triple<SourceRef, String, String>? {
        val item = player?.currentMediaItem ?: return null
        if (item.mediaMetadata.extras?.getBoolean(PlayerRepository.EXTRA_IS_CUE_TRACK) == true) return null
        val ref = SourceUris.parse(item.mediaId) ?: return null
        return Triple(ref, _uiState.value.currentTitle, _uiState.value.currentArtist)
    }

    fun pauseLocal() { player?.pause() }

    /**
     * Ends the session (mini player swiped away; only while not playing): the queue is emptied, so the notification
     * goes away, and nothing is restored on the next start. Playlists, including "Default", are kept.
     */
    fun dismissSession() {
        val p = player
        if (p?.isPlaying == true) return
        cancelRestore()
        metadataJob?.cancel()
        aiLyricsJob?.cancel()
        progressJob?.cancel()
        pendingPlayIntent = null
        p?.stop()
        p?.clearMediaItems()
        currentFolder = null
        playbackPreferences?.clearSession()
        resetSleepTimer()
        _uiState.value = _uiState.value.copy(
            currentTitle = PlayerTitles.NONE, currentArtist = "", currentFolderName = "", audioInfo = "",
            coverUri = null, coverFallback = null,
            lyrics = emptyList(), translatedLyrics = emptyList(), currentLyricIndex = -1, lyricsSource = "",
            currentMediaId = null, playRequested = false, playlist = emptyList(),
            isPlaying = false, isBuffering = false, playbackError = null,
            progress = 0f, currentPosition = 0L, duration = 0L, bufferedPosition = 0L,
            canSkipNext = false, canSkipPrevious = false,
        )
    }

    fun updatePlaybackState() {
        player?.let { p ->
            val position = p.currentPosition
            val duration = p.duration.takeIf { it > 0 } ?: 1L
            val lyrics = _uiState.value.lyrics
            val index = if (_uiState.value.lyricsSynced) lyrics.indexOfLast { it.timeMs <= position } else -1

            _uiState.value = _uiState.value.copy(
                currentPosition = position,
                duration = duration,
                progress = position.toFloat() / duration,
                isPlaying = p.isPlaying,
                currentLyricIndex = index,
                bufferedPosition = p.bufferedPosition,
                canSkipNext = p.hasNextMediaItem(),
                canSkipPrevious = p.hasPreviousMediaItem(),
            )

            // Periodically save position (every 5 seconds)
            if (!isRestoring && Math.abs(position - (playbackPreferences?.getLastPosition() ?: 0L)) > 5000) {
                playbackPreferences?.savePosition(position)
            }
        }
    }

    fun playPause() {
        player?.let { p ->
            if (p.isPlaying) p.pause() else p.play()
            updatePlaybackState()
        }
    }

    // Without a track to move to (single track, first / last without repeat) next / previous do nothing: announcing a
    // change ("Loading…" + waiting for any new track) would leave the display waiting for a change that never comes.
    fun previous() {
        val p = player ?: return
        if (!p.hasPreviousMediaItem()) { updatePlaybackState(); return }
        _uiState.value = _uiState.value.copy(
            currentTitle = "Loading..",
            lyrics = emptyList(),
            currentLyricIndex = -1
        )
        lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
        pendingPlayIntent = "ANY_NEW"
        player?.seekToPreviousMediaItem()
    }

    fun next() {
        val p = player ?: return
        if (!p.hasNextMediaItem()) { updatePlaybackState(); return }
        _uiState.value = _uiState.value.copy(
            currentTitle = "Loading..",
            lyrics = emptyList(),
            currentLyricIndex = -1
        )
        lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
        pendingPlayIntent = "ANY_NEW"
        player?.seekToNextMediaItem()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        updatePlaybackState()
    }

    fun seekTo(position: Float) {
        player?.let { p ->
            val duration = p.duration
            if (duration > 0) {
                p.seekTo((position * duration).toLong())
                updatePlaybackState()
            }
        }
    }

    fun playAt(index: Int) {
        isRestoring = false
        _uiState.value = _uiState.value.copy(
            currentTitle = "Loading..",
            lyrics = emptyList(),
            currentLyricIndex = -1
        )
        lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
        pendingPlayIntent = "ANY_NEW"
        player?.seekTo(index, 0L)
        player?.play()
    }

    /** Plays a folder, optionally starting at [startPath] (source-relative path of a file in it). */
    fun playFolder(folder: SourceRef, startPath: String? = null) {
        cancelRestore()
        val pendingTitle = startPath?.let { SourcePath.baseName(SourcePath.name(it)) } ?: "Loading..."
        _uiState.value = _uiState.value.copy(
            currentTitle = pendingTitle,
            currentFolderName = SourcePath.name(folder.path),
            coverUri = null,
            lyrics = emptyList(),
            isBuffering = true,
            progress = 0f,
            currentPosition = 0L,
            duration = 0L,
            playbackError = null,
            playRequested = true,
        )
        lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
        pendingPlayIntent = startPath?.let { SourceUris.toUri(folder.sourceId, it) } ?: "ANY_NEW"

        viewModelScope.launch(exceptionHandler) {
            try {
                awaitPlayer()
                playFolderInternal(folder, startPath, 0L, playWhenReady = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBuffering = false, playbackError = errorText(e))
            }
        }
    }

    /** Plays [files] (already sorted as shown) starting at [startIndex]. */
    fun playCustomList(files: List<MusicFile>, startIndex: Int) {
        cancelRestore()
        val firstFile = files.getOrNull(startIndex) ?: return
        // Shown at once, also while the controller is still connecting after a cold start.
        _uiState.value = _uiState.value.copy(
            currentTitle = SourcePath.baseName(firstFile.name),
            currentArtist = "",
            currentFolderName = firstFile.ref.parent?.let { SourcePath.name(it.path) } ?: "",
            currentMediaId = firstFile.ref.toUriString(),
            lyrics = emptyList(),
            coverUri = null,
            isBuffering = true,
            playbackError = null,
            playRequested = true,
        )
        viewModelScope.launch(exceptionHandler) {
            awaitPlayer()
            currentFolder = firstFile.ref.parent
            lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
            pendingPlayIntent = firstFile.ref.toUriString()

            val mediaItems = repo().mediaItemsFor(files)
            player?.setMediaItems(mediaItems)
            if (startIndex in mediaItems.indices) {
                player?.seekTo(startIndex, 0L)
            }
            player?.prepare()
            player?.play()

            playbackPreferences?.savePlaybackState(currentFolder, firstFile.ref.toUriString(), 0L)

            // Sync to "Default" playlist
            val playlistItems = files.mapIndexed { i, file -> playlistItemFor(file, mediaItems.getOrNull(i)) }
            playlistManager?.savePlaylist(Playlist("default", "Default", playlistItems))

            _uiState.value = _uiState.value.copy(
                activePlaylistId = "default",
                activePlaylistName = "Default",
                activePlaylistItems = playlistItems
            )
            refreshPlaylists()

            kotlinx.coroutines.delay(1000)
            updateMetadata()
        }
    }

    private fun playlistItemFor(file: MusicFile, item: MediaItem?) = PlaylistItem(
        path = file.path,
        title = SourcePath.baseName(file.name),
        artist = "",
        sourceId = file.sourceId,
        artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
    )

    fun playCueSheet(cue: SourceRef) {
        cancelRestore()
        _uiState.value = _uiState.value.copy(
            currentTitle = SourcePath.baseName(cue.name),
            currentFolderName = cue.parent?.let { SourcePath.name(it.path) } ?: "",
            currentMediaId = cue.toUriString(),
            coverUri = null,
            lyrics = emptyList(),
            isBuffering = true,
            progress = 0f,
            currentPosition = 0L,
            duration = 0L,
            playbackError = null,
            playRequested = true,
        )
        lastMediaIdBeforeIntent = player?.currentMediaItem?.mediaId
        pendingPlayIntent = "ANY_NEW"

        viewModelScope.launch(exceptionHandler) {
            try {
                awaitPlayer()
                playCueSheetInternal(cue, null, 0L, playWhenReady = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBuffering = false, playbackError = errorText(e))
            }
        }
    }

    fun playPlaylistSong(playlistId: String, startIndex: Int) {
        cancelRestore()
        val manager = playlistManager ?: return
        val playlist = manager.getPlaylist(playlistId) ?: return
        val items = playlist.items
        if (items.getOrNull(startIndex) == null) return

        viewModelScope.launch(exceptionHandler) {
            awaitPlayer()
            val mediaItems = items.mapNotNull { pItem ->
                val ref = pItem.ref ?: return@mapNotNull null
                if (SourceRegistry.get(ref.sourceId) == null) return@mapNotNull null
                val file = MusicFile(ref.name, ref.path, false, 0, 0, ref.sourceId)
                if (pItem.cueStartMs != null || MediaTypes.isCue(ref.name)) {
                    null
                } else {
                    val built = repo().buildMediaItem(file, pItem.artworkUri, null)
                    if (pItem.title.isNotBlank() && repo().titleMode == TitleMode.FILENAME) {
                        built.buildUpon().setMediaMetadata(built.mediaMetadata.buildUpon().setTitle(pItem.title)
                            .setArtist(pItem.artist?.takeIf { it.isNotBlank() }).build()).build()
                    } else built
                }
            }
            if (mediaItems.isEmpty()) {
                _uiState.value = _uiState.value.copy(playbackError = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_playlist_unavailable))
                return@launch
            }
            val clicked = items[startIndex].ref?.toUriString()
            val index = mediaItems.indexOfFirst { it.mediaId == clicked }.coerceAtLeast(0)

            player?.setMediaItems(mediaItems)
            player?.seekTo(index, 0L)
            player?.prepare()
            player?.play()

            _uiState.value = _uiState.value.copy(
                activePlaylistId = playlistId,
                activePlaylistName = playlist.name,
                activePlaylistItems = items
            )
            refreshPlaylists()
        }
    }

    private suspend fun playCueSheetInternal(
        cue: SourceRef,
        startingMediaId: String?,
        positionMs: Long,
        playWhenReady: Boolean
    ) {
        val fs = SourceRegistry.fileSystem(cue)
        val cueContent = runInterruptible(Dispatchers.IO) { fs.readText(cue.path) }
        val (referencedFile, tracks) = CueParser.parse(cueContent)
        if (tracks.isEmpty()) return

        val parent = cue.parent ?: SourceRef(cue.sourceId, SourcePath.ROOT)
        val siblings = repo().list(parent)
        var audio: MusicFile? = null

        // 1. The file referenced in the CUE sheet (name only; directories inside FILE are ignored)
        if (referencedFile != null) {
            val name = referencedFile.replace('\\', '/').substringAfterLast('/')
            audio = siblings.firstOrNull { !it.isDirectory && it.name == name }
                ?: siblings.firstOrNull { !it.isDirectory && it.name.equals(name, true) }
        }
        // 2. Fallback: same base name as the .cue with a known audio extension
        if (audio == null) {
            val base = SourcePath.baseName(cue.name)
            for (ext in listOf("flac", "ape", "wav", "mp3", "m4a", "dsf", "dff", "wma")) {
                audio = siblings.firstOrNull { !it.isDirectory && it.name.equals("$base.$ext", true) }
                if (audio != null) break
            }
        }
        val audioFile = audio ?: return

        currentFolder = parent
        val cover = repo().coverFor(parent, siblings)

        val mediaItems = tracks.map { track ->
            repo().createCueMediaItem(
                audio = audioFile,
                trackTitle = track.title,
                performer = track.performer,
                startTimeMs = track.startTimeMs,
                endTimeMs = track.endTimeMs,
                coverUri = cover,
                cue = cue,
                trackNumber = track.number,
            )
        }

        player?.setMediaItems(mediaItems)

        if (!isRestoring) {
            val playlistItems = tracks.map { track ->
                PlaylistItem(
                    path = cue.path,
                    title = track.title,
                    artist = track.performer ?: "",
                    sourceId = cue.sourceId,
                    artworkUri = cover,
                    durationMs = (track.endTimeMs ?: 0L) - track.startTimeMs,
                    cueStartMs = track.startTimeMs,
                )
            }
            playlistManager?.savePlaylist(Playlist("default", "Default", playlistItems))

            _uiState.value = _uiState.value.copy(
                activePlaylistId = "default",
                activePlaylistName = "Default",
                activePlaylistItems = playlistItems
            )
            refreshPlaylists()
        }
        if (startingMediaId != null) {
            val index = mediaItems.indexOfFirst { it.mediaId == startingMediaId }
            if (index != -1) {
                player?.seekTo(index, positionMs)
            }
        } else {
            player?.seekTo(0, positionMs)
        }

        player?.prepare()
        if (playWhenReady) {
            player?.play()
        }

        playbackPreferences?.savePlaybackState(parent, startingMediaId ?: mediaItems.first().mediaId, positionMs)

        kotlinx.coroutines.delay(1000)
        updateMetadata()
    }

    private suspend fun playFolderInternal(
        folder: SourceRef,
        startPath: String?,
        positionMs: Long,
        playWhenReady: Boolean
    ) {
        currentFolder = folder
        val generation = playRequestGeneration

        val sortPref = sourcePreferences?.getDirectorySort(folder) ?: sourcePreferences?.getDefaultSort()

        val items = repo().getMediaItemsInFolder(
            folder = folder,
            sortField = sortPref?.field ?: "NAME",
            sortAscending = sortPref?.ascending ?: true
        )
        player?.setMediaItems(items)

        if (!isRestoring) {
            val playlistItems = items.map { item ->
                val ref = SourceUris.parse(item.mediaId)
                PlaylistItem(
                    path = ref?.path ?: "",
                    title = item.mediaMetadata.displayTitle?.toString() ?: "Unknown",
                    artist = item.mediaMetadata.artist?.toString() ?: "",
                    sourceId = ref?.sourceId ?: "",
                    artworkUri = item.mediaMetadata.artworkUri?.toString()
                )
            }
            playlistManager?.savePlaylist(Playlist("default", "Default", playlistItems))

            _uiState.value = _uiState.value.copy(
                activePlaylistId = "default",
                activePlaylistName = "Default",
                activePlaylistItems = playlistItems
            )
            refreshPlaylists()
        }

        val target = startPath?.let { SourceUris.toUri(folder.sourceId, it) }
        val index = if (target != null) items.indexOfFirst { it.mediaId == target } else 0
        if (index > 0 || positionMs > 0) player?.seekTo(index.coerceAtLeast(0), positionMs)

        player?.prepare()
        if (playWhenReady) {
            player?.play()
        }

        // High reliability seek for restoration
        if (positionMs > 0) {
            viewModelScope.launch(exceptionHandler) {
                var attempts = 0
                while (player?.playbackState == Player.STATE_IDLE && attempts < 15) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                kotlinx.coroutines.delay(200)
                // The user may have started something else meanwhile; never seek their new queue.
                if (generation == playRequestGeneration && index != -1) player?.seekTo(index.coerceAtLeast(0), positionMs)
            }
        }

        viewModelScope.launch(exceptionHandler) {
            kotlinx.coroutines.delay(1000)
            updateMetadata()
        }

        playbackPreferences?.savePlaybackState(folder, target ?: items.firstOrNull()?.mediaId, positionMs)
    }

    fun setCoverDisplaySize(size: String) {
        playbackPreferences?.saveCoverDisplaySize(size)
        _uiState.value = _uiState.value.copy(coverDisplaySize = size)
    }

    fun setBackgroundStyle(style: String) {
        playbackPreferences?.saveBackgroundStyle(style)
        _uiState.value = _uiState.value.copy(backgroundStyle = style)
    }

    fun setTitleMode(mode: TitleMode) {
        playbackPreferences?.saveTitleMode(mode.name)
        repository?.titleMode = mode
    }

    /** Clears lyric results so a changed AI/lyrics setting takes effect for the current song. */
    fun invalidateLyrics() {
        lyricsCache.clear()
        explicitAiLyrics.clear()
        lyricsRepository?.clearMemory()
        updateMetadata()
    }

    // --- Playlist Management Methods ---

    fun switchPlaylist(id: String) {
        val manager = playlistManager ?: return
        val playlist = manager.getPlaylist(id) ?: return

        playbackPreferences?.saveActivePlaylistId(id)

        _uiState.value = _uiState.value.copy(
            activePlaylistId = id,
            activePlaylistName = playlist.name,
            activePlaylistItems = playlist.items,
            shuffleModeEnabled = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            autoNextFolder = false
        )
        player?.shuffleModeEnabled = false
        player?.repeatMode = Player.REPEAT_MODE_OFF

        refreshPlaylists()
    }

    private fun refreshPlaylists() {
        val manager = playlistManager ?: return
        val all = manager.getAllPlaylists()
        val currentId = _uiState.value.activePlaylistId
        val currentItems = manager.getPlaylist(currentId)?.items ?: emptyList<PlaylistItem>()

        _uiState.value = _uiState.value.copy(
            allPlaylists = all,
            activePlaylistItems = currentItems
        )
    }

    fun createPlaylist(name: String) {
        val id = playlistManager?.createPlaylist(name)
        if (id != null) {
            refreshPlaylists()
        }
    }

    fun renamePlaylist(id: String, newName: String) {
        playlistManager?.renamePlaylist(id, newName)
        if (id == _uiState.value.activePlaylistId) {
            _uiState.value = _uiState.value.copy(activePlaylistName = newName)
        }
        refreshPlaylists()
    }

    fun deletePlaylist(id: String) {
        if (id == "default") return
        playlistManager?.deletePlaylist(id)
        if (id == _uiState.value.activePlaylistId) {
            switchPlaylist("default")
        } else {
            refreshPlaylists()
        }
    }

    fun removeFromActivePlaylist(index: Int) {
        val currentId = _uiState.value.activePlaylistId

        // If it's the default list, we also need to remove it from the PLAYER queue
        if (currentId == "default") {
            player?.removeMediaItem(index)
        }

        playlistManager?.removeFromPlaylist(currentId, index)
        refreshPlaylists()
    }

    fun addFilesToPlaylist(targetPlaylistId: String, files: List<MusicFile>) {
        viewModelScope.launch(exceptionHandler) {
            val allItems = mutableListOf<PlaylistItem>()
            for (file in files) {
                if (file.isDirectory) {
                    val mediaItems = repo().getMediaItemsInFolder(file.ref)
                    mediaItems.forEach { item ->
                        val ref = SourceUris.parse(item.mediaId) ?: return@forEach
                        allItems.add(PlaylistItem(
                            path = ref.path,
                            title = item.mediaMetadata.displayTitle?.toString() ?: "Unknown",
                            artist = item.mediaMetadata.artist?.toString() ?: "",
                            sourceId = ref.sourceId,
                            artworkUri = item.mediaMetadata.artworkUri?.toString(),
                        ))
                    }
                } else {
                    val parent = file.ref.parent
                    val cover = parent?.let { repo().coverFor(it) }
                    allItems.add(PlaylistItem(
                        path = file.path,
                        title = SourcePath.baseName(file.name),
                        artist = "",
                        sourceId = file.sourceId,
                        artworkUri = cover,
                    ))
                }
            }

            playlistManager?.appendToPlaylist(targetPlaylistId, allItems)
            refreshPlaylists()
        }
    }

    companion object {
        /** Instrumentation tests only: delays handling the controller connection by this many ms. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var controllerConnectDelayMsForTest = 0L
    }

    override fun onCleared() {
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

fun PlaylistItem.matchesMediaId(mediaId: String?): Boolean {
    if (mediaId == null) return false
    val r = ref ?: return false
    if (cueStartMs != null) {
        return SourceUris.isCueTrackId(mediaId) && SourceUris.cueStartMs(mediaId) == cueStartMs &&
            SourceUris.parse(mediaId)?.sourceId == r.sourceId
    }
    return mediaId == r.toUriString()
}

/** User-facing text for playback failures (kept short; details go to logcat). */
object PlaybackErrorText {
    fun describe(t: Throwable): String {
        var c: Throwable? = t
        while (c != null) {
            when (c) {
                is com.wing.folderplayer.data.source.SourceException.SeekUnsupported -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_seek_unsupported)
                is com.wing.folderplayer.data.source.SourceException.AuthFailed -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_auth_failed)
                is com.wing.folderplayer.data.source.SourceException.PermissionDenied -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_permission_denied, c.message ?: "")
                is com.wing.folderplayer.data.source.SourceException.NotFound -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_not_found)
                is com.wing.folderplayer.data.source.SourceException.Unreachable -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_unreachable)
                is com.wing.folderplayer.data.source.SourceException.TlsFailure -> return com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_tls)
                is com.wing.folderplayer.playback.NativeDecoderException ->
                    return if (c.unsupported) com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_unsupported_format_detail, c.message ?: "") else com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_decoding_failed, c.message ?: "")
            }
            c = c.cause
        }
        // Through MediaController the cause chain arrives flattened; fall back to the error code and message.
        if (t is androidx.media3.common.PlaybackException) {
            val msg = generateSequence<Throwable>(t) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
            return when {
                msg.contains("REST", true) -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_seek_unsupported)
                msg.contains("DST", true) -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_unsupported_format_detail, "DST-compressed DSD")
                t.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_permission_or_auth)
                t.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_not_found)
                t.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_network)
                t.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_unsupported_format)
                else -> "${t.errorCodeName}: ${msg.take(160)}"
            }
        }
        return t.message ?: t.javaClass.simpleName
    }
}
