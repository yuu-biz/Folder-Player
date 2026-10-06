package com.wing.folderplayer.ui.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.favorites.FavoriteItem
import com.wing.folderplayer.data.favorites.FavoritesRepository
import com.wing.folderplayer.data.favorites.SyncResult
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.search.SearchProgress
import com.wing.folderplayer.data.search.SearchRepository
import com.wing.folderplayer.data.source.ConnectionTestResult
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

data class CacheEntry(
    val files: List<MusicFile>,
    val timestamp: Long,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val sortField: String,
    val sortAscending: Boolean
)

data class SearchUiState(
    val active: Boolean = false,
    val query: String = "",
    val running: Boolean = false,
    val results: List<MusicFile> = emptyList(),
    val foldersScanned: Int = 0,
    val foldersSkipped: Int = 0,
    val truncated: Boolean = false,
)

data class BrowserUiState(
    /** null = source list (root). */
    val currentFolder: SourceRef? = null,
    val showingFavorites: Boolean = false,
    val files: List<MusicFile> = emptyList(),
    val isLoading: Boolean = false,
    val currentlyPlayingMediaId: String? = null,
    val sortField: String = "NAME",
    val sortAscending: Boolean = true,
    val availableSources: List<SourceConfig> = emptyList(),
    val viewMode: String = "LIST",
    val gridDensity: Int = 3,
    // Scroll memory
    val scrollToIndex: Int = 0,
    val scrollToOffset: Int = 0,
    val scrollTrigger: Int = 0, // Increment to trigger scroll in UI
    // Playlist Context Menu
    val selectedFileForPlaylist: MusicFile? = null,
    val search: SearchUiState = SearchUiState(),
    val favorites: List<FavoriteItem> = emptyList(),
    val message: String? = null,
    /** The last folder could not be read (shown in place of the list, with Retry, until the next load). */
    val loadError: String? = null,
    /** Bumped when folder images must be looked up again (refresh, permission change, cache cleared). */
    val thumbnailRevision: Int = 0,
) {
    val currentSource: SourceConfig? get() = currentFolder?.let { f -> availableSources.firstOrNull { it.id == f.sourceId } }
    val isRoot: Boolean get() = currentFolder == null && !showingFavorites
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("BrowserViewModel", "Unhandled exception in coroutine", throwable)
        _error.value = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_generic, throwable.localizedMessage ?: "")
        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    private val sourcePreferences: SourcePreferences
    private val thumbnails: ThumbnailRepository
    private val durations = com.wing.folderplayer.data.metadata.DurationRepository.get(application)
    val favoritesRepository: FavoritesRepository
    private val searchRepository = SearchRepository({ ref -> SourceRegistry.fileSystem(ref).list(ref.path) })
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** Last [BrowserUiState.scrollTrigger] the list has scrolled for (kept here: same lifetime as the counter). */
    var handledScrollTrigger = 0

    // Directory Cache (Expires after 20 minutes), keyed by folder URI
    private val directoryCache = mutableMapOf<String, CacheEntry>()
    private val CACHE_EXPIRY_MS = 20 * 60 * 1000L

    init {
        SourceRegistry.init(application)
        sourcePreferences = SourcePreferences(application)
        thumbnails = ThumbnailRepository.get(application)
        favoritesRepository = FavoritesRepository(File(application.filesDir, "favorites/fav.json"))

        viewModelScope.launch {
            SourceRegistry.sources.collect { list -> _uiState.value = _uiState.value.copy(availableSources = list) }
        }
        viewModelScope.launch {
            favoritesRepository.items.collect { list ->
                _uiState.value = _uiState.value.copy(favorites = list)
                if (_uiState.value.showingFavorites) showFavorites()
            }
        }
        _uiState.value = _uiState.value.copy(gridDensity = sourcePreferences.getGridDensity())

        val last = sourcePreferences.getLastBrowsed()
        if (last != null && SourceRegistry.get(last.sourceId) != null) {
            loadFolder(last)
        }
    }

    // ---------------- sources ----------------

    /** [password]: null keeps the stored secret. */
    fun saveSource(config: SourceConfig, password: String?) {
        SourceRegistry.upsert(config, password)
        thumbnails.invalidateSource(config.id)
        directoryCache.keys.removeIf { SourceRef(config.id, "/").toUriString().removeSuffix("/").let(it::startsWith) }
    }

    fun removeSource(sourceId: String) {
        SourceRegistry.remove(sourceId)
        thumbnails.invalidateSource(sourceId)
    }

    fun duplicateSource(sourceId: String) {
        SourceRegistry.duplicate(sourceId)
    }

    fun moveSourceUp(sourceId: String) = SourceRegistry.move(sourceId, -1)
    fun moveSourceDown(sourceId: String) = SourceRegistry.move(sourceId, +1)

    suspend fun testConnection(config: SourceConfig, password: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val fs = try {
            if (password == null && SourceRegistry.get(config.id) != null) SourceRegistry.create(config) else SourceRegistry.create(config, password ?: "")
        } catch (e: Exception) {
            return@withContext ConnectionTestResult.fromException(e)
        }
        try { runInterruptible { fs.testConnection() } } finally { runCatching { fs.close() } }
    }

    /** Called after ACTION_OPEN_DOCUMENT_TREE returned [tree]; persists the permission and adds the source. */
    fun addSafSource(tree: Uri, displayName: String) {
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(tree, flags)
        } catch (e: SecurityException) {
            // Write may not be grantable (read-only provider); keep read access.
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val existing = SourceRegistry.savedSources().firstOrNull { it.type == SourceType.SAF && it.url == tree.toString() }
        if (existing != null) {
            // Re-selected after revocation: same source id, so favorites/playlists keep working.
            SourceRegistry.upsert(existing.copy(revision = existing.revision + 1), null)
            thumbnails.invalidateSource(existing.id)
        } else {
            SourceRegistry.upsert(SourceConfig(name = displayName, type = SourceType.SAF, url = tree.toString()), null)
        }
        thumbnails.invalidateNegatives()
    }

    // ---------------- navigation ----------------

    fun selectSource(source: SourceConfig) {
        closeSearch()
        loadFolder(SourceRef(source.id, SourcePath.ROOT))
    }

    fun clearError() {
        _error.value = null
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun refresh() {
        val folder = _uiState.value.currentFolder ?: return
        directoryCache.remove(folder.toUriString())
        durations.forgetUnknown()
        thumbnails.invalidate(folder)
        _uiState.value.files.filter { it.isDirectory }.forEach { thumbnails.invalidate(it.ref) }
        thumbnails.invalidateNegatives()
        bumpThumbnails()
        loadFolder(folder)
    }

    fun onPermissionsChanged() {
        thumbnails.invalidateNegatives()
        bumpThumbnails()
        directoryCache.clear()
        _uiState.value.currentFolder?.let { loadFolder(it) }
    }

    /**
     * @param isBackNavigation If true, we will try to restore the scroll position from cache.
     */
    fun loadFolder(folder: SourceRef, isBackNavigation: Boolean = false) {
        val now = System.currentTimeMillis()
        directoryCache.entries.removeIf { now - it.value.timestamp > CACHE_EXPIRY_MS }
        loadJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            currentFolder = folder,
            loadError = null,
            showingFavorites = false,
            viewMode = sourcePreferences.getViewMode(folder),
        )
        sourcePreferences.saveLastBrowsedState(folder)

        loadJob = viewModelScope.launch(exceptionHandler) {
            val override = sourcePreferences.getDirectorySort(folder)
            val (field, asc) = override?.let { it.field to it.ascending }
                ?: sourcePreferences.getDefaultSort().let { it.field to it.ascending }

            val key = folder.toUriString()
            val cached = directoryCache[key]
            if (cached != null && (now - cached.timestamp < CACHE_EXPIRY_MS)) {
                val finalFiles = if (cached.sortField != field || cached.sortAscending != asc) applySort(cached.files, field, asc) else cached.files
                _uiState.value = _uiState.value.copy(
                    files = finalFiles,
                    isLoading = false,
                    sortField = field,
                    sortAscending = asc,
                    scrollToIndex = if (isBackNavigation) cached.scrollIndex else 0,
                    scrollToOffset = if (isBackNavigation) cached.scrollOffset else 0,
                    scrollTrigger = _uiState.value.scrollTrigger + 1
                )
                return@launch
            }

            try {
                val filesRaw = runInterruptible(Dispatchers.IO) { SourceRegistry.fileSystem(folder).list(folder.path) }
                val files = withContext(Dispatchers.Default) { applySort(filesRaw, field, asc) }
                directoryCache[key] = CacheEntry(files, System.currentTimeMillis(), sortField = field, sortAscending = asc)
                val cfg = SourceRegistry.get(folder.sourceId)
                if (cfg?.type == SourceType.LOCAL) {
                    val report = com.wing.folderplayer.utils.PermissionDiagnostics.report(getApplication())
                    if (report.audio == com.wing.folderplayer.utils.PermissionDiagnostics.Access.DENIED) {
                        _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(com.wing.folderplayer.R.string.browser_local_no_audio_permission))
                    }
                }
                if (_uiState.value.currentFolder != folder) return@launch
                _uiState.value = _uiState.value.copy(
                    files = files,
                    isLoading = false,
                    sortField = field,
                    sortAscending = asc,
                    scrollToIndex = 0,
                    scrollToOffset = 0,
                    scrollTrigger = _uiState.value.scrollTrigger + 1
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Error loading folder: ${e.javaClass.simpleName} ${e.message}")
                _error.value = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_load_failed, com.wing.folderplayer.ui.player.PlaybackErrorText.describe(e))
                _uiState.value = _uiState.value.copy(isLoading = false, files = emptyList(), loadError = _error.value)
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        val key = _uiState.value.currentFolder?.toUriString() ?: return
        directoryCache[key]?.let { entry ->
            directoryCache[key] = entry.copy(scrollIndex = index, scrollOffset = offset)
        }
    }

    private fun applySort(list: List<MusicFile>, by: String, ascending: Boolean): List<MusicFile> {
        // ALWAYS put folders first
        val (folders, files) = list.partition { it.isDirectory }

        fun sort(l: List<MusicFile>) = when (by) {
            "NAME" -> if (ascending) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.name.lowercase() }
            "DATE" -> if (ascending) l.sortedBy { it.lastModified } else l.sortedByDescending { it.lastModified }
            "CREATED" -> if (ascending) l.sortedBy { it.createdOrModified } else l.sortedByDescending { it.createdOrModified }
            "SIZE" -> if (ascending) l.sortedBy { it.size } else l.sortedByDescending { it.size }
            else -> l
        }
        return sort(folders) + sort(files)
    }

    fun updateCurrentlyPlaying(mediaId: String?) {
        _uiState.value = _uiState.value.copy(currentlyPlayingMediaId = mediaId)
    }

    fun sortFiles(by: String) {
        viewModelScope.launch(exceptionHandler) {
            val state = _uiState.value
            val folder = state.currentFolder ?: return@launch
            val newAscending = if (state.sortField == by) !state.sortAscending else true
            val sorted = withContext(Dispatchers.Default) { applySort(state.files, by, newAscending) }
            sourcePreferences.saveDirectorySort(folder, by, newAscending)
            _uiState.value = _uiState.value.copy(files = sorted, sortField = by, sortAscending = newAscending)
            val key = folder.toUriString()
            directoryCache[key]?.let { entry ->
                directoryCache[key] = entry.copy(files = sorted, sortField = by, sortAscending = newAscending)
            }
        }
    }

    fun setViewMode(mode: String) {
        val folder = _uiState.value.currentFolder
        if (folder != null) sourcePreferences.saveViewMode(folder, mode) else sourcePreferences.saveDefaultViewMode(mode)
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    fun setGridDensity(columns: Int) {
        sourcePreferences.saveGridDensity(columns)
        _uiState.value = _uiState.value.copy(gridDensity = columns.coerceIn(2, 5))
    }

    fun navigateUp() {
        if (_uiState.value.search.active) { closeSearch(); return }
        if (_uiState.value.showingFavorites) { exitSource(); return }
        val folder = _uiState.value.currentFolder ?: return
        val parent = folder.parent
        if (parent == null) exitSource() else loadFolder(parent, isBackNavigation = true)
    }

    fun exitSource() {
        loadJob?.cancel()
        closeSearch()
        sourcePreferences.saveLastBrowsedState(null)
        _uiState.value = _uiState.value.copy(currentFolder = null, showingFavorites = false, files = emptyList(), isLoading = false)
    }

    fun onFileClicked(
        file: MusicFile,
        onFolderPlay: (SourceRef, String?) -> Unit,
        onCustomPlay: (List<MusicFile>, Int) -> Unit,
        onCuePlay: (SourceRef) -> Unit
    ) {
        if (file.isDirectory) {
            closeSearch()
            loadFolder(file.ref)
            return
        }
        if (MediaTypes.isCue(file.name)) {
            onCuePlay(file.ref)
            return
        }
        if (!MediaTypes.isAudio(file.name)) return

        val list = when {
            _uiState.value.search.active -> _uiState.value.search.results
            _uiState.value.showingFavorites -> _uiState.value.files
            else -> _uiState.value.files
        }
        val musicFiles = list.filter { !it.isDirectory && MediaTypes.isAudio(it.name) }
        val clickIndex = musicFiles.indexOfFirst { it.sourceId == file.sourceId && it.path == file.path }
        if (clickIndex != -1) {
            onCustomPlay(musicFiles, clickIndex)
        } else {
            onFolderPlay(file.ref.parent ?: SourceRef(file.sourceId, SourcePath.ROOT), file.path)
        }
    }

    fun playCurrentFolder(onFolderPlay: (SourceRef, String?) -> Unit) {
        _uiState.value.currentFolder?.let { onFolderPlay(it, null) }
    }

    fun shufflePlay(onFolderPlay: (SourceRef, String?) -> Unit, onCustomPlay: (List<MusicFile>, Int) -> Unit) {
        val files = _uiState.value.files
        if (files.isEmpty()) return

        val musicFiles = files.filter { !it.isDirectory && MediaTypes.isAudio(it.name) }
        val folders = files.filter { it.isDirectory }

        if (musicFiles.isNotEmpty()) {
            onCustomPlay(musicFiles.shuffled(), 0)
        } else if (folders.isNotEmpty()) {
            onFolderPlay(folders.random().ref, null)
        }
    }

    fun onFileLongClick(file: MusicFile) {
        _uiState.value = _uiState.value.copy(selectedFileForPlaylist = file)
    }

    fun onAddToPlaylistDone() {
        _uiState.value = _uiState.value.copy(selectedFileForPlaylist = null)
    }

    fun closePlaylistDialog() {
        _uiState.value = _uiState.value.copy(selectedFileForPlaylist = null)
    }

    // ---------------- thumbnails ----------------

    suspend fun folderThumbnail(folder: SourceRef): ArtworkResult = thumbnails.folderThumbnail(folder)

    /** Track length in ms for the list (null: not shown). */
    suspend fun trackDuration(file: MusicFile): Long? = durations.duration(file)

    private fun bumpThumbnails() {
        _uiState.value = _uiState.value.copy(thumbnailRevision = _uiState.value.thumbnailRevision + 1)
    }

    /** Called after the image cache was cleared in Settings: visible folders look up their image again. */
    fun onImageCacheCleared() = bumpThumbnails()

    // ---------------- favorites ----------------

    fun isFavorite(file: MusicFile): Boolean = favoritesRepository.isFavorite(file.ref, file.isDirectory)

    fun toggleFavorite(file: MusicFile) = favoritesRepository.toggle(file)

    fun showFavorites() {
        closeSearch()
        loadJob?.cancel()
        val sources = SourceRegistry.sources.value.map { it.id }.toSet()
        val entries = favoritesRepository.items.value.mapNotNull { f ->
            val ref = f.ref ?: return@mapNotNull null
            MusicFile(f.name.ifEmpty { ref.name }, ref.path, f.type == FavoriteItem.TYPE_FOLDER, f.size, f.timestamp, ref.sourceId)
                .takeIf { ref.sourceId in sources }
        }
        _uiState.value = _uiState.value.copy(
            showingFavorites = true,
            currentFolder = null,
            files = entries,
            isLoading = false,
            viewMode = sourcePreferences.getDefaultViewMode(),
        )
    }

    fun syncFavorites() {
        viewModelScope.launch(exceptionHandler) {
            val targets = SourceRegistry.sources.value.filter { it.syncPath.isNotBlank() }
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(message = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_no_path))
                return@launch
            }
            val messages = targets.map { cfg ->
                val r = runInterruptible(Dispatchers.IO) { favoritesRepository.sync(SourceRegistry.fileSystem(cfg.id), SourcePath.normalize(cfg.syncPath)) }
                "${cfg.name}: " + describe(r)
            }
            _uiState.value = _uiState.value.copy(message = messages.joinToString("\n"))
        }
    }

    fun replaceRemoteFavorites(sourceId: String) {
        viewModelScope.launch(exceptionHandler) {
            val cfg = SourceRegistry.get(sourceId) ?: return@launch
            val r = runInterruptible(Dispatchers.IO) { favoritesRepository.replaceRemote(SourceRegistry.fileSystem(cfg.id), SourcePath.normalize(cfg.syncPath)) }
            _uiState.value = _uiState.value.copy(message = "${cfg.name}: " + describe(r))
        }
    }

    private fun describe(r: SyncResult): String = when (r) {
        is SyncResult.Synced -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_done, r.added, r.total)
        is SyncResult.RemoteCorrupt -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_remote_broken, r.reason)
        is SyncResult.RemoteNotWritable -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_remote_read_only, r.reason, r.mergedFromRemote)
        is SyncResult.Failed -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_failed, r.reason)
        is SyncResult.RemoteHasUnreadableEntries -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_remote_unreadable_entries, r.rejected, r.mergedFromRemote)
    }

    // ---------------- search ----------------

    fun openSearch() {
        if (_uiState.value.currentFolder == null) return
        _uiState.value = _uiState.value.copy(search = SearchUiState(active = true))
    }

    fun closeSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(search = SearchUiState())
    }

    fun search(query: String) {
        val root = _uiState.value.currentFolder ?: return
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(search = SearchUiState(active = true, query = query, running = query.isNotBlank()))
        if (query.isBlank()) return
        searchJob = viewModelScope.launch(exceptionHandler) {
            kotlinx.coroutines.delay(300) // debounce typing
            searchRepository.search(root, query).collect { p: SearchProgress ->
                _uiState.value = _uiState.value.copy(
                    search = _uiState.value.search.copy(
                        running = !p.done,
                        results = p.results,
                        foldersScanned = p.foldersScanned,
                        foldersSkipped = p.foldersSkipped,
                        truncated = p.truncated,
                    )
                )
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(search = _uiState.value.search.copy(running = false))
    }
}
