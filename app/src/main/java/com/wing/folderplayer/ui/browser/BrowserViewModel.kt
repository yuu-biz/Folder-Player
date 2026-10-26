package com.wing.folderplayer.ui.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.favorites.Bookmark
import com.wing.folderplayer.data.favorites.BookmarksRepository
import com.wing.folderplayer.data.favorites.FavoriteItem
import com.wing.folderplayer.data.favorites.FavoritesKind
import com.wing.folderplayer.data.favorites.FavoritesRepository
import com.wing.folderplayer.data.favorites.FavoritesSort
import com.wing.folderplayer.data.favorites.SyncAnchor
import com.wing.folderplayer.data.favorites.SyncResult
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.search.NameFilter
import com.wing.folderplayer.data.search.SearchProgress
import com.wing.folderplayer.data.search.SearchRepository
import com.wing.folderplayer.R
import com.wing.folderplayer.data.source.ConnectionTestResult
import com.wing.folderplayer.data.source.FileOperations
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.sortMusicFiles
import com.wing.folderplayer.data.source.SORT_LENGTH
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.viewer.ViewerCache
import com.wing.folderplayer.data.viewer.ViewerKind
import com.wing.folderplayer.data.viewer.ViewerText
import com.wing.folderplayer.data.viewer.ViewerTypes
import com.wing.folderplayer.utils.Strings
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
    /** The filter the list was narrowed by when [scrollIndex] was saved (the position is of that list). */
    val scrollFilter: String = "",
    val sortField: String,
    val sortAscending: Boolean
)

/** [done] of [total] lengths read for a list sorted by length; [running] until all were tried. */
data class LengthFill(val done: Int, val total: Int, val running: Boolean = true)

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
    /** The favourites list on screen (songs or folders); null: a folder or the source list. */
    val favoritesKind: FavoritesKind? = null,
    /** The songs list on screen (every song the length index knows, see [com.wing.folderplayer.data.metadata.SongList]). */
    val showingSongs: Boolean = false,
    /** Songs in that list (shown on the source list). */
    val songCount: Int = 0,
    /** The folders that list is narrowed to, with the folders below them (kept, see [SourcePreferences.getSongsFolders]); empty: every song. */
    val songsFolders: List<SourceRef> = emptyList(),
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
    /** Bookmarked folders of this device (those whose source still exists are listed on the source list). */
    val bookmarks: List<Bookmark> = emptyList(),
    val message: String? = null,
    /** The last folder could not be read (shown in place of the list, with Retry, until the next load). */
    val loadError: String? = null,
    /** Bumped when folder images must be looked up again (refresh, permission change, cache cleared). */
    val thumbnailRevision: Int = 0,
    /** The filter of the folder on screen (see [NameFilter]); kept for the way back to the folder, dropped for another one. */
    val filter: String = "",
    val filterOpen: Boolean = false,
    /** The file open in the viewer (text, image or PDF beside the songs), or null. */
    val viewer: ViewerRequest? = null,
    /** A rename, move or delete waiting for the user's confirmation (its dialog is shown), or running ([fileOpBusy]). */
    val fileOp: FileOp? = null,
    val fileOpBusy: Boolean = false,
    /** Sorted by length: the lengths of this list being read (or read) for the order; null when none were missing. */
    val lengthFill: LengthFill? = null,
) {
    /** [files] narrowed by [filter]: the list on screen (playing a song of it queues the songs shown). */
    val visibleFiles: List<MusicFile> by lazy(LazyThreadSafetyMode.NONE) {
        if (showingSongs) com.wing.folderplayer.data.metadata.SongList.filter(files, filter) else NameFilter.apply(files, filter)
    }

    val showingFavorites: Boolean get() = favoritesKind != null
    val currentSource: SourceConfig? get() = currentFolder?.let { f -> availableSources.firstOrNull { it.id == f.sourceId } }
    val isRoot: Boolean get() = currentFolder == null && !showingFavorites && !showingSongs

    fun isBookmarked(ref: SourceRef): Boolean = bookmarks.any { it.sourceId == ref.sourceId && it.path == ref.path }
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
    val bookmarksRepository: BookmarksRepository
    private val viewerCache = ViewerCache(File(application.cacheDir, "viewer"))
    private val searchRepository = SearchRepository({ ref -> SourceRegistry.fileSystem(ref).list(ref.path) })
    private var loadJob: Job? = null

    /** A place Back can lead to: the source list, the favourites list or a folder. */
    private sealed interface Place {
        object SourceList : Place
        data class Favorites(val kind: FavoritesKind) : Place
        data class Songs(val filter: String = "") : Place
        data class Folder(val ref: SourceRef, val filter: String = "") : Place
    }

    /** The places left by navigating, latest last: Back goes to the previous one (see [navigateUp]). */
    private val backStack = ArrayDeque<Place>()
    private val MAX_BACK_STACK = 100
    /** Scroll position of each favourites list, kept for the way back from a favourite. */
    private val favoritesScroll = HashMap<FavoritesKind, Pair<Int, Int>>()
    private var songsScroll = 0 to 0
    /** Counts the songs for the source list (declared before init: its collectors start it). */
    private var countJob: Job? = null
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
        favoritesRepository = FavoritesRepository.get(application)
        bookmarksRepository = BookmarksRepository(File(application.filesDir, "favorites/bookmarks.json"))

        viewModelScope.launch {
            SourceRegistry.sources.collect { list ->
                _uiState.value = _uiState.value.copy(availableSources = list, songsFolders = songsFolders())
                countSongs()
            }
        }
        viewModelScope.launch {
            favoritesRepository.items.collect { list ->
                _uiState.value = _uiState.value.copy(favorites = list)
                _uiState.value.favoritesKind?.let { renderFavorites(it, restoreScroll = false) }
            }
        }
        viewModelScope.launch {
            bookmarksRepository.items.collect { list -> _uiState.value = _uiState.value.copy(bookmarks = list) }
        }
        viewModelScope.launch(Dispatchers.IO) { durations.warmUp() }
        viewModelScope.launch {
            // Lengths and tags were kept or entries dropped: the songs list (and its count) follows.
            durations.revision.collect {
                countSongs()
                if (_uiState.value.showingSongs) renderSongs(restoreScroll = false)
            }
        }
        viewModelScope.launch {
            // A scan read lengths: a list sorted by them is sorted again.
            var finished = durations.scan.value.finished
            durations.scan.collect { p -> if (p.finished != finished) { finished = p.finished; resortByLength() } }
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

    /** Shares an SMB server offers for the form of the editor ([password] null: the one stored for this source, if any). */
    suspend fun listSmbShares(config: SourceConfig, password: String?): com.wing.folderplayer.data.source.SmbShareResult = withContext(Dispatchers.IO) {
        val pass = password ?: SourceRegistry.get(config.id)?.let { SourceRegistry.credentials.get(it.effectiveCredentialRef) }
        runInterruptible { com.wing.folderplayer.data.source.SmbShareLister.list(config.host, config.effectivePort, config.username, pass, config.domain, config.anonymous) }
    }

    /** The same as a guest, for a server just found by the search. */
    suspend fun listSmbSharesAsGuest(host: String, port: Int): com.wing.folderplayer.data.source.SmbShareResult = withContext(Dispatchers.IO) {
        runInterruptible { com.wing.folderplayer.data.source.SmbShareLister.listAsGuest(host, port) }
    }

    /** Searches the local network for SMB servers (cancel the collector to stop). */
    fun discoverSmb(): kotlinx.coroutines.flow.Flow<com.wing.folderplayer.data.source.SmbDiscoveryState> =
        com.wing.folderplayer.data.source.SmbDiscovery(getApplication()).discover()

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

    private fun currentPlace(): Place = when {
        _uiState.value.favoritesKind != null -> Place.Favorites(_uiState.value.favoritesKind!!)
        _uiState.value.showingSongs -> Place.Songs(_uiState.value.filter)
        else -> _uiState.value.currentFolder?.let { Place.Folder(it, _uiState.value.filter) } ?: Place.SourceList
    }

    /** Call before leaving the current place by navigating (a refresh or Back does not). */
    private fun rememberCurrentPlace() {
        val here = currentPlace()
        if (backStack.lastOrNull() == here) return
        backStack.addLast(here)
        while (backStack.size > MAX_BACK_STACK) backStack.removeFirst()
    }

    /** A fresh start from the source list: Back from here on leads to it. */
    private fun startFromSourceList() {
        backStack.clear()
        backStack.addLast(Place.SourceList)
    }

    fun selectSource(source: SourceConfig) {
        startFromSourceList()
        closeSearch()
        loadFolder(SourceRef(source.id, SourcePath.ROOT))
    }

    fun clearError() {
        _error.value = null
    }

    /** A short note in the browser's snackbar. */
    fun showMessage(text: String) {
        _uiState.value = _uiState.value.copy(message = text)
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
     * @param filter The filter to show the folder with; null: the current one for the same folder (a refresh), none for another.
     * @param focus A file of the folder to bring into view (the track playing, see [revealInFolder]).
     */
    fun loadFolder(folder: SourceRef, isBackNavigation: Boolean = false, filter: String? = null, focus: SourceRef? = null) {
        val now = System.currentTimeMillis()
        directoryCache.entries.removeIf { now - it.value.timestamp > CACHE_EXPIRY_MS }
        loadJob?.cancel()
        songsJob?.cancel()

        lengthJob?.cancel()
        val sameFolder = folder == _uiState.value.currentFolder
        val newFilter = filter ?: if (sameFolder) _uiState.value.filter else ""
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            currentFolder = folder,
            loadError = null,
            favoritesKind = null,
            showingSongs = false,
            lengthFill = null,
            viewMode = sourcePreferences.getViewMode(folder),
            filter = newFilter,
            filterOpen = newFilter.isNotEmpty() || (sameFolder && _uiState.value.filterOpen),
        )
        sourcePreferences.saveLastBrowsedState(folder)

        loadJob = viewModelScope.launch(exceptionHandler) {
            val override = sourcePreferences.getDirectorySort(folder)
            val (field, asc) = override?.let { it.field to it.ascending }
                ?: sourcePreferences.getDefaultSort().let { it.field to it.ascending }

            val key = folder.toUriString()
            val cached = directoryCache[key]
            if (cached != null && (now - cached.timestamp < CACHE_EXPIRY_MS)) {
                // By length: sorted again with the lengths known now (a scan or the rows may have read more since).
                val finalFiles = if (field == SORT_LENGTH || cached.sortField != field || cached.sortAscending != asc) withContext(Dispatchers.Default) { applySort(cached.files, field, asc) } else cached.files
                val restore = isBackNavigation && cached.scrollFilter == newFilter
                _uiState.value = _uiState.value.copy(
                    files = finalFiles,
                    isLoading = false,
                    sortField = field,
                    sortAscending = asc,
                    scrollToIndex = focusIndex(finalFiles, newFilter, focus) ?: if (restore) cached.scrollIndex else 0,
                    scrollToOffset = if (restore && focus == null) cached.scrollOffset else 0,
                    scrollTrigger = _uiState.value.scrollTrigger + 1
                )
                fillLengths()
                return@launch
            }

            try {
                val filesRaw = runInterruptible(Dispatchers.IO) { SourceRegistry.fileSystem(folder).list(folder.path) }
                // The whole folder: lengths of files that are no longer in it go.
                viewModelScope.launch(Dispatchers.IO) { durations.onFolderListed(folder, filesRaw) }
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
                    scrollToIndex = focusIndex(files, newFilter, focus) ?: 0,
                    scrollToOffset = 0,
                    scrollTrigger = _uiState.value.scrollTrigger + 1
                )
                fillLengths()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Error loading folder: ${e.javaClass.simpleName} ${e.message}")
                _error.value = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_load_failed, com.wing.folderplayer.ui.player.PlaybackErrorText.describe(e))
                _uiState.value = _uiState.value.copy(isLoading = false, files = emptyList(), loadError = _error.value)
            }
        }
    }

    /** Where the list starts so that [focus] is in view with a row or two above it; null: not in the list. */
    private fun focusIndex(files: List<MusicFile>, filter: String, focus: SourceRef?): Int? {
        focus ?: return null
        val i = NameFilter.apply(files, filter).indexOfFirst { it.sourceId == focus.sourceId && it.path == focus.path }
        return if (i < 0) null else (i - 2).coerceAtLeast(0)
    }

    /**
     * The folder of the track playing, from the player (its folder name): a new step, so Back returns to where the user
     * was; already there, the list only scrolls to [file] (and the filter goes if it hides it).
     */
    fun revealInFolder(folder: SourceRef, file: SourceRef?) {
        closeSearch()
        val state = _uiState.value
        if (state.currentFolder == folder) {
            val shown = focusIndex(state.files, state.filter, file)
            val unfiltered = if (shown == null) focusIndex(state.files, "", file) else null
            val index = shown ?: unfiltered ?: return
            _uiState.value = _uiState.value.copy(
                filter = if (unfiltered != null) "" else state.filter,
                filterOpen = if (unfiltered != null) false else state.filterOpen,
                scrollToIndex = index, scrollToOffset = 0, scrollTrigger = _uiState.value.scrollTrigger + 1,
            )
            return
        }
        if (state.isRoot) startFromSourceList() else rememberCurrentPlace()
        loadFolder(folder, filter = "", focus = file)
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        _uiState.value.favoritesKind?.let { favoritesScroll[it] = index to offset; return }
        if (_uiState.value.showingSongs) { songsScroll = index to offset; return }
        val key = _uiState.value.currentFolder?.toUriString() ?: return
        directoryCache[key]?.let { entry ->
            directoryCache[key] = entry.copy(scrollIndex = index, scrollOffset = offset, scrollFilter = _uiState.value.filter)
        }
    }

    private fun applySort(list: List<MusicFile>, by: String, ascending: Boolean): List<MusicFile> {
        // ALWAYS put folders first (by name when the songs are by length: a folder has none)
        val (folders, files) = list.partition { it.isDirectory }
        if (by == SORT_LENGTH) return sortMusicFiles(folders, "NAME", true) + sortMusicFiles(durations.withCachedLengths(files), by, ascending)

        return sortMusicFiles(folders, by, ascending) + sortMusicFiles(files, by, ascending)
    }

    fun updateCurrentlyPlaying(mediaId: String?) {
        _uiState.value = _uiState.value.copy(currentlyPlayingMediaId = mediaId)
    }

    fun sortFiles(by: String) {
        _uiState.value.favoritesKind?.let { kind ->
            val s = _uiState.value
            sourcePreferences.saveFavoritesSort(kind, by, if (s.sortField == by) !s.sortAscending else true)
            renderFavorites(kind, restoreScroll = false)
            return
        }
        if (_uiState.value.showingSongs) {
            val s = _uiState.value
            sourcePreferences.saveSongsSort(by, if (s.sortField == by) !s.sortAscending else true)
            renderSongs(restoreScroll = false, top = true)
            return
        }
        viewModelScope.launch(exceptionHandler) {
            val state = _uiState.value
            val folder = state.currentFolder ?: return@launch
            val newAscending = if (state.sortField == by) !state.sortAscending else true
            val sorted = withContext(Dispatchers.Default) { applySort(state.files, by, newAscending) }
            sourcePreferences.saveDirectorySort(folder, by, newAscending)
            _uiState.value = _uiState.value.copy(files = sorted, sortField = by, sortAscending = newAscending)
            fillLengths()
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

    /**
     * Back (system Back and the arrow of the top bar): the previous place, as in a browser: the folder, the favourites
     * list or the source list the user came from, whichever way they went (a folder entered from the favourites list or
     * a bookmark leads back to it, a folder that was entered from a folder leads back to that folder). Going up one folder
     * is [goUp], and Back after it returns to where the user was.
     */
    fun navigateUp() {
        if (_uiState.value.search.active) { closeSearch(); return }
        val previous = backStack.removeLastOrNull()
        if (previous != null) {
            when (previous) {
                Place.SourceList -> showSourceList()
                is Place.Favorites -> renderFavorites(previous.kind, restoreScroll = true)
                is Place.Songs -> renderSongs(restoreScroll = true, filter = previous.filter)
                is Place.Folder -> loadFolder(previous.ref, isBackNavigation = true, filter = previous.filter)
            }
            return
        }
        // No history (the app opened on the folder it was last in): the folder above, the source list above a source root.
        if (_uiState.value.showingFavorites || _uiState.value.showingSongs) { showSourceList(); return }
        val folder = _uiState.value.currentFolder ?: return
        val parent = folder.parent
        if (parent == null) showSourceList() else loadFolder(parent, isBackNavigation = true)
    }

    /** The arrow-up button of the top bar: the folder above (a new step: Back returns to the folder the user was in). */
    fun goUp() {
        if (_uiState.value.search.active) return
        val parent = _uiState.value.currentFolder?.parent ?: return
        rememberCurrentPlace()
        loadFolder(parent, isBackNavigation = true)
    }

    /** The home button of the top bar: the source list, with no way back from it (Back there leaves the app). */
    fun exitSource() {
        backStack.clear()
        showSourceList()
    }

    private fun showSourceList() {
        loadJob?.cancel()
        lengthJob?.cancel()
        closeSearch()
        sourcePreferences.saveLastBrowsedState(null)
        songsJob?.cancel()
        _uiState.value = _uiState.value.copy(currentFolder = null, favoritesKind = null, showingSongs = false, lengthFill = null, files = emptyList(), isLoading = false, filter = "", filterOpen = false)
    }

    fun onFileClicked(
        file: MusicFile,
        onFolderPlay: (SourceRef, String?) -> Unit,
        onCustomPlay: (List<MusicFile>, Int) -> Unit,
        onCuePlay: (SourceRef) -> Unit
    ) {
        if (file.isDirectory) {
            rememberCurrentPlace()
            closeSearch()
            loadFolder(file.ref)
            return
        }
        if (MediaTypes.isCue(file.name)) {
            onCuePlay(file.ref)
            return
        }
        if (ViewerTypes.isViewable(file.name)) {
            openViewer(file)
            return
        }
        if (!MediaTypes.isAudio(file.name)) return

        val list = listOnScreen()
        val musicFiles = list.filter { !it.isDirectory && MediaTypes.isAudio(it.name) }
        val clickIndex = musicFiles.indexOfFirst { it.sourceId == file.sourceId && it.path == file.path }
        if (clickIndex != -1) {
            onCustomPlay(musicFiles, clickIndex)
        } else {
            onFolderPlay(file.ref.parent ?: SourceRef(file.sourceId, SourcePath.ROOT), file.path)
        }
    }

    /** The entries shown: search results, the favourites list or the (filtered) folder. */
    private fun listOnScreen(): List<MusicFile> = when {
        _uiState.value.search.active -> _uiState.value.search.results
        _uiState.value.showingFavorites -> _uiState.value.files
        else -> _uiState.value.visibleFiles
    }

    // ---------------- viewer ----------------

    /** Opens [file] in the viewer; an image can be swiped to the other images of the list on screen, in its order. */
    fun openViewer(file: MusicFile) {
        val kind = ViewerTypes.kind(file.name) ?: return
        val same = { f: MusicFile -> f.sourceId == file.sourceId && f.path == file.path }
        val images = if (kind == ViewerKind.IMAGE) listOnScreen().filter { !it.isDirectory && ViewerTypes.kind(it.name) == ViewerKind.IMAGE } else emptyList()
        val index = images.indexOfFirst(same)
        val request = if (index >= 0) ViewerRequest(images, index) else ViewerRequest(listOf(file), 0)
        _uiState.value = _uiState.value.copy(viewer = request, selectedFileForPlaylist = null)
    }

    fun closeViewer() {
        _uiState.value = _uiState.value.copy(viewer = null)
    }

    /** The beginning of a text file for the viewer ([ViewerText.LIMIT]). */
    suspend fun viewerText(file: MusicFile): Pair<ByteArray, Boolean> =
        runInterruptible(Dispatchers.IO) { ViewerText.read(SourceRegistry.fileSystem(file.ref), file.path) }

    /** The local copy of [file] for the viewer or another app (made once, see [ViewerCache]). */
    suspend fun viewerFile(file: MusicFile, onProgress: (Long) -> Unit): File =
        runInterruptible(Dispatchers.IO) { viewerCache.fetch(SourceRegistry.fileSystem(file.ref), file, onProgress = onProgress) }

    fun playCurrentFolder(onFolderPlay: (SourceRef, String?) -> Unit) {
        _uiState.value.currentFolder?.let { onFolderPlay(it, null) }
    }

    fun shufflePlay(onFolderPlay: (SourceRef, String?) -> Unit, onCustomPlay: (List<MusicFile>, Int) -> Unit) {
        val files = _uiState.value.visibleFiles
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

    // ---------------- file operations ----------------

    /** Asks for a rename, a move or a delete of a file ([FileOp]); the file that is playing is refused. */
    fun startFileOp(op: FileOp) {
        val playing = _uiState.value.currentlyPlayingMediaId?.let { com.wing.folderplayer.data.source.SourceUris.parse(it) }
        if (playing == op.file.ref) {
            _uiState.value = _uiState.value.copy(selectedFileForPlaylist = null, message = Strings.get(R.string.file_op_playing))
            return
        }
        _uiState.value = _uiState.value.copy(selectedFileForPlaylist = null, fileOp = op)
    }

    fun cancelFileOp() {
        if (!_uiState.value.fileOpBusy) _uiState.value = _uiState.value.copy(fileOp = null)
    }

    /** The folders inside [folder], by name, for the folder picker of "Move to". */
    suspend fun listFolders(folder: SourceRef): List<MusicFile> = runInterruptible(Dispatchers.IO) {
        sortMusicFiles(SourceRegistry.fileSystem(folder).list(folder.path).filter { it.isDirectory }, "NAME", true)
    }

    fun renameFile(file: MusicFile, name: String) = runFileOp(file, { fs -> FileOperations.rename(fs, file, name) }) {
        Strings.get(R.string.file_renamed, it!!.name)
    }

    fun moveFile(file: MusicFile, folder: String) = runFileOp(file, { fs -> FileOperations.move(fs, file, folder) }) {
        Strings.get(R.string.file_moved, folder)
    }

    fun deleteFile(file: MusicFile) = runFileOp(file, { fs -> FileOperations.delete(fs, file); null }) {
        Strings.get(R.string.file_deleted, file.name)
    }

    /** Runs [op] on the file's source; on success the folders it touched are read again and the viewer follows the file. */
    private fun runFileOp(file: MusicFile, op: (com.wing.folderplayer.data.source.SourceFileSystem) -> MusicFile?, done: (MusicFile?) -> String) {
        if (_uiState.value.fileOpBusy) return
        _uiState.value = _uiState.value.copy(fileOpBusy = true)
        viewModelScope.launch(exceptionHandler) {
            val result = runCatching { runInterruptible(Dispatchers.IO) { op(SourceRegistry.fileSystem(file.ref)) } }
            _uiState.value = _uiState.value.copy(fileOpBusy = false)
            result.onFailure { e ->
                _uiState.value = _uiState.value.copy(fileOp = null)
                _error.value = describeFileOpFailure(file, e)
            }.onSuccess { moved ->
                afterFileOp(file, moved)
                _uiState.value = _uiState.value.copy(fileOp = null, message = done(moved))
            }
        }
    }

    private fun describeFileOpFailure(file: MusicFile, e: Throwable): String = when (e) {
        is FileOperations.Failure.InvalidName -> Strings.get(R.string.file_name_invalid)
        is FileOperations.Failure.AlreadyExists -> Strings.get(R.string.file_name_taken, e.name)
        is FileOperations.Failure.Refused, is SourceException.PermissionDenied, is SourceException.ReadOnly ->
            if (SourceRegistry.get(file.sourceId)?.type == SourceType.LOCAL) Strings.get(R.string.file_op_local_refused)
            else Strings.get(R.string.file_op_failed, com.wing.folderplayer.ui.player.PlaybackErrorText.describe(e))
        else -> Strings.get(R.string.file_op_failed, com.wing.folderplayer.ui.player.PlaybackErrorText.describe(e))
    }

    /** [moved]: the file under its new path, null when it was deleted. */
    private fun afterFileOp(file: MusicFile, moved: MusicFile?) {
        durations.onFileOp(file, moved)
        val folders = listOfNotNull(SourcePath.parent(file.path), moved?.path?.let(SourcePath::parent)).distinct()
            .map { SourceRef(file.sourceId, it) }
        folders.forEach { directoryCache.remove(it.toUriString()); thumbnails.invalidate(it) }
        bumpThumbnails()
        val same = { f: MusicFile -> f.sourceId == file.sourceId && f.path == file.path }
        val state = _uiState.value
        val viewer = state.viewer?.let { v ->
            val i = v.files.indexOfFirst(same)
            when {
                i < 0 -> v
                // Renamed: still shown; moved away or deleted: the next image (or none).
                moved != null && SourcePath.parent(moved.path) == SourcePath.parent(file.path) ->
                    v.copy(files = v.files.toMutableList().also { it[i] = moved })
                else -> {
                    val rest = v.files.filterIndexed { j, _ -> j != i }
                    if (rest.isEmpty()) null else ViewerRequest(rest, minOf(v.index, rest.lastIndex))
                }
            }
        }
        val results = state.search.results.mapNotNull { if (same(it)) moved else it }
        _uiState.value = state.copy(viewer = viewer, search = state.search.copy(results = results))
        state.currentFolder?.takeIf { it in folders }?.let { loadFolder(it) }
    }

    // ---------------- thumbnails ----------------

    suspend fun folderThumbnail(folder: SourceRef): ArtworkResult = thumbnails.folderThumbnail(folder)

    /** Thumbnail of an image file in the list: the image itself, under the same switches as folder images. */
    fun imageThumbnail(image: MusicFile): ArtworkResult = thumbnails.imageThumbnail(image)

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

    // ---------------- bookmarks ----------------

    /** A source's root is listed under the source's name, any other folder under its own name. */
    private fun bookmarkName(ref: SourceRef, fallback: String): String =
        if (ref.path == SourcePath.ROOT) SourceRegistry.get(ref.sourceId)?.name ?: fallback else SourcePath.name(ref.path).ifEmpty { fallback }

    fun toggleBookmark(file: MusicFile) {
        if (file.isDirectory) bookmarksRepository.toggle(file.ref, bookmarkName(file.ref, file.name))
    }

    fun toggleCurrentFolderBookmark() {
        val folder = _uiState.value.currentFolder ?: return
        bookmarksRepository.toggle(folder, bookmarkName(folder, ""))
    }

    fun openBookmark(bookmark: Bookmark) {
        startFromSourceList()
        closeSearch()
        loadFolder(bookmark.ref)
    }

    fun removeBookmark(bookmark: Bookmark) = bookmarksRepository.remove(bookmark.ref)

    fun renameBookmark(bookmark: Bookmark, name: String) = bookmarksRepository.rename(bookmark.id, name)

    /** Up ([delta] < 0) or down past the next bookmark the source list shows (those of removed sources are hidden). */
    fun moveBookmark(bookmark: Bookmark, delta: Int) {
        val state = _uiState.value
        val shown = state.bookmarks.filter { b -> state.availableSources.any { it.id == b.sourceId } }.map { it.id }
        bookmarksRepository.reorder(BookmarksRepository.swapped(shown, bookmark.id, delta))
    }

    /** The order of the bookmarks the source list shows, after a drag; hidden ones keep their place. */
    fun reorderBookmarks(shownIds: List<String>) = bookmarksRepository.reorder(shownIds)

    /** A favourites list (entered from the source list, or from a folder or the other list when called from elsewhere). */
    fun showFavorites(kind: FavoritesKind) {
        val state = _uiState.value
        if (state.favoritesKind == kind) return renderFavorites(kind, restoreScroll = false)
        if (state.currentFolder == null && state.favoritesKind == null) startFromSourceList() else rememberCurrentPlace()
        renderFavorites(kind, restoreScroll = false)
    }

    /** Entries whose file times were asked for in this run of the app (an entry that cannot be read is not asked again). */
    private val favoriteTimesAsked = HashSet<Triple<String, String, String>>()
    private var favoriteTimesJob: Job? = null

    /** Reads the size and times of favourites added before 1.6.0 (fav.json did not keep them), for sorting by date. */
    private fun fillFavoriteTimes(shown: List<FavoriteItem>) {
        if (favoriteTimesJob?.isActive == true) return
        val missing = shown.filter { it.modified == 0L && favoriteTimesAsked.add(it.key) }
        if (missing.isEmpty()) return
        favoriteTimesJob = viewModelScope.launch(Dispatchers.IO) {
            val found = HashMap<Triple<String, String, String>, MusicFile>()
            for ((sourceId, items) in missing.groupBy { it.sourceId }) {
                val fs = runCatching { SourceRegistry.fileSystem(sourceId) }.getOrNull() ?: continue
                for (f in items) {
                    try {
                        fs.stat(f.path)?.let { found[f.key] = it }
                    } catch (e: SourceException.NotFound) {
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // An unreachable source (a NAS that is off) would cost a timeout per entry: skip the rest of it.
                        break
                    }
                }
            }
            favoritesRepository.updateMetadata(found)
        }
    }

    /** [fill]: read the lengths the list lacks when it is sorted by them (not when it is sorted again after reading them). */
    private fun renderFavorites(kind: FavoritesKind, restoreScroll: Boolean, fill: Boolean = true) {
        closeSearch()
        loadJob?.cancel()
        if (fill) lengthJob?.cancel()
        val sources = SourceRegistry.sources.value.map { it.id }.toSet()
        val sort = sourcePreferences.getFavoritesSort(kind)
        val shown = favoritesRepository.items.value.filter { it.ref != null && it.sourceId in sources && it.type == kind.type }
        val byLength = sort.field == SORT_LENGTH
        val entries = FavoritesSort.sort(shown, sort.field, sort.ascending, durations::cached).map(FavoritesSort::asFile)
            .map { if (byLength && !it.isDirectory) it.copy(durationMs = durations.cached(it)) else it }
        if (FavoritesSort.needsFileTimes(sort.field)) fillFavoriteTimes(shown)
        songsJob?.cancel()
        _uiState.value = _uiState.value.copy(
            favoritesKind = kind,
            showingSongs = false,
            currentFolder = null,
            filter = "",
            filterOpen = false,
            files = entries,
            sortField = sort.field,
            sortAscending = sort.ascending,
            isLoading = false,
            viewMode = sourcePreferences.getDefaultViewMode(),
            // Back from a favourite: the list is as it was left (kept by [saveScrollPosition]).
            scrollToIndex = if (restoreScroll) favoritesScroll[kind]?.first ?: 0 else _uiState.value.scrollToIndex,
            scrollToOffset = if (restoreScroll) favoritesScroll[kind]?.second ?: 0 else _uiState.value.scrollToOffset,
            scrollTrigger = if (restoreScroll) _uiState.value.scrollTrigger + 1 else _uiState.value.scrollTrigger,
        )
        if (fill) fillLengths()
    }

    // ---------------- songs list ----------------

    private var songsJob: Job? = null

    /** The songs list (entered from the source list, or from elsewhere as a new step). */
    fun showSongs() {
        val state = _uiState.value
        if (state.showingSongs) return
        if (state.isRoot) startFromSourceList() else rememberCurrentPlace()
        closeSearch()
        loadJob?.cancel()
        lengthJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showingSongs = true, favoritesKind = null, currentFolder = null, files = emptyList(), lengthFill = null,
            filter = "", filterOpen = false, isLoading = true, viewMode = "LIST",
        )
        renderSongs(restoreScroll = false, top = true)
    }

    /**
     * Builds the songs list from the length index (below the folder it is narrowed to, [setSongsFolder]), in its order. [restoreScroll]: Back to it (the place it was left at);
     * [top]: from its first row (entered, or sorted again); otherwise it stays where it is (the index changed).
     */
    private fun renderSongs(restoreScroll: Boolean, top: Boolean = false, filter: String? = null) {
        songsJob?.cancel()
        val sort = sourcePreferences.getSongsSort()
        if (restoreScroll) {
            closeSearch()
            loadJob?.cancel()
            lengthJob?.cancel()
            _uiState.value = _uiState.value.copy(showingSongs = true, favoritesKind = null, currentFolder = null, lengthFill = null, filter = filter.orEmpty(), filterOpen = !filter.isNullOrEmpty())
        }
        val folders = songsFolders()
        songsJob = viewModelScope.launch(exceptionHandler) {
            val songs = withContext(Dispatchers.Default) {
                val list = com.wing.folderplayer.data.metadata.SongList
                list.sort(list.inFolders(durations.songs(), folders), sort.field, sort.ascending)
            }
            val now = _uiState.value
            if (!now.showingSongs) return@launch // left meanwhile
            val f = now.filter
            val scroll = when {
                restoreScroll -> songsScroll
                top -> 0 to 0
                else -> null
            }
            _uiState.value = now.copy(
                showingSongs = true, favoritesKind = null, currentFolder = null, files = songs, isLoading = false, songsFolders = folders,
                sortField = sort.field, sortAscending = sort.ascending, viewMode = "LIST", lengthFill = null,
                filter = f, filterOpen = f.isNotEmpty() || now.filterOpen,
                scrollToIndex = scroll?.first ?: now.scrollToIndex,
                scrollToOffset = scroll?.second ?: now.scrollToOffset,
                scrollTrigger = if (scroll != null) now.scrollTrigger + 1 else now.scrollTrigger,
            )
        }
    }

    /** The folders the songs list is narrowed to, without those of sources that are gone; empty: every song. */
    private fun songsFolders(): List<SourceRef> = sourcePreferences.getSongsFolders().filter { SourceRegistry.get(it.sourceId) != null }

    private fun countSongs() {
        countJob?.cancel()
        countJob = viewModelScope.launch {
            val folders = songsFolders()
            val count = withContext(Dispatchers.Default) { com.wing.folderplayer.data.metadata.SongList.inFolders(durations.songs(), folders).size }
            _uiState.value = _uiState.value.copy(songCount = count, songsFolders = folders)
        }
    }

    /** Narrows the songs list to [folders] and the folders below them (empty: every song); kept for the next time. */
    fun setSongsFolders(folders: List<SourceRef>) {
        sourcePreferences.saveSongsFolders(folders)
        _uiState.value = _uiState.value.copy(songsFolders = songsFolders())
        countSongs()
        if (_uiState.value.showingSongs) renderSongs(restoreScroll = false, top = true)
    }

    /** Adds [folder] to the folders the songs list is narrowed to, or takes it out (the folder picker). */
    fun toggleSongsFolder(folder: SourceRef) {
        val now = songsFolders()
        setSongsFolders(if (folder in now) now - folder else now + folder)
    }

    /** The songs list of [folder] and the folders below it only (a folder's menu, a bookmark's menu). */
    fun showSongsIn(folder: SourceRef) {
        setSongsFolders(listOf(folder))
        showSongs()
    }

    /** Reads the song lengths of the folders the songs list is narrowed to. */
    fun scanSongsFolderLengths() { songsFolders().takeIf { it.isNotEmpty() }?.let { scanLengths(it) } }

    // ---------------- lengths ----------------

    private var lengthJob: Job? = null

    /**
     * Sorted by length: reads the lengths the list on screen does not know yet (under the same switches as its rows) and
     * sorts it once when they are read, not on every length, so the list does not move under the finger meanwhile. A
     * network source that stops answering is not tried file after file (a timeout each).
     */
    private fun fillLengths() {
        lengthJob?.cancel()
        val state = _uiState.value
        val missing = if (state.sortField == SORT_LENGTH) state.files.filter { !it.isDirectory && MediaTypes.isAudio(it.name) && it.durationMs == null } else emptyList()
        if (missing.isEmpty()) {
            if (state.lengthFill != null) _uiState.value = state.copy(lengthFill = null)
            return
        }
        val place = currentPlace()
        _uiState.value = state.copy(lengthFill = LengthFill(0, missing.size))
        lengthJob = viewModelScope.launch(exceptionHandler) {
            var found = 0
            var missedInARow = 0
            for ((i, f) in missing.withIndex()) {
                if (durations.duration(f) != null) { found++; missedInARow = 0 }
                else if (SourceRegistry.get(f.sourceId)?.isNetwork == true && ++missedInARow >= 3) break
                _uiState.value = _uiState.value.copy(lengthFill = LengthFill(i + 1, missing.size))
            }
            if (currentPlace() != place) return@launch
            _uiState.value = _uiState.value.copy(lengthFill = LengthFill(missing.size, missing.size, running = false))
            if (found > 0) resortByLength()
        }
    }

    /** The list on screen sorted by length again with the lengths known now (the scroll position stays). */
    private fun resortByLength() {
        val s = _uiState.value
        if (s.sortField != SORT_LENGTH) return
        s.favoritesKind?.let { renderFavorites(it, restoreScroll = false, fill = false); return }
        // (The songs list knows every length.)
        val folder = s.currentFolder ?: return
        viewModelScope.launch(exceptionHandler) {
            val sorted = withContext(Dispatchers.Default) { applySort(s.files, SORT_LENGTH, s.sortAscending) }
            val now = _uiState.value
            if (now.currentFolder != folder || now.sortField != SORT_LENGTH || now.sortAscending != s.sortAscending) return@launch
            _uiState.value = now.copy(files = sorted)
            val key = folder.toUriString()
            directoryCache[key]?.let { directoryCache[key] = it.copy(files = sorted, sortField = SORT_LENGTH, sortAscending = s.sortAscending) }
        }
    }

    /** Explicit scans of the song lengths (see [com.wing.folderplayer.data.metadata.DurationRepository.startScan]). */
    val lengthScan: StateFlow<com.wing.folderplayer.data.metadata.DurationRepository.ScanProgress> = durations.scan

    private fun scanLengths(roots: List<SourceRef>) {
        if (roots.isEmpty()) return
        if (!durations.startScan(roots)) _uiState.value = _uiState.value.copy(message = Strings.get(R.string.length_scan_busy))
    }

    /** The folder on screen and the folders below it. */
    fun scanCurrentFolderLengths() { _uiState.value.currentFolder?.let { scanLengths(listOf(it)) } }

    fun scanBookmarkLengths(bookmark: Bookmark) = scanLengths(listOf(bookmark.ref))

    /** Every bookmarked folder the source list shows. */
    fun scanAllBookmarkLengths() {
        val state = _uiState.value
        scanLengths(state.bookmarks.filter { b -> state.availableSources.any { it.id == b.sourceId } }.map { it.ref })
    }

    fun cancelLengthScan() = durations.cancelScan()

    fun dismissLengthScan() = durations.dismissScanResult()

    fun syncFavorites() {
        viewModelScope.launch(exceptionHandler) {
            val targets = SourceRegistry.sources.value.filter { it.syncPath.isNotBlank() }
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(message = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.sync_no_path))
                return@launch
            }
            val messages = targets.map { cfg ->
                val r = runInterruptible(Dispatchers.IO) { favoritesRepository.sync(SourceRegistry.fileSystem(cfg.id), SourcePath.normalize(cfg.syncPath), SyncAnchor.of(cfg)) }
                "${cfg.name}: " + describe(r)
            }
            _uiState.value = _uiState.value.copy(message = messages.joinToString("\n"))
        }
    }

    fun replaceRemoteFavorites(sourceId: String) {
        viewModelScope.launch(exceptionHandler) {
            val cfg = SourceRegistry.get(sourceId) ?: return@launch
            val r = runInterruptible(Dispatchers.IO) { favoritesRepository.replaceRemote(SourceRegistry.fileSystem(cfg.id), SourcePath.normalize(cfg.syncPath), SyncAnchor.of(cfg)) }
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

    // ---------------- filter ----------------

    fun openFilter() {
        if (_uiState.value.currentFolder == null && !_uiState.value.showingSongs) return
        _uiState.value = _uiState.value.copy(filterOpen = true)
    }

    /** Narrows the folder on screen as the user types; the list starts again from the top. */
    fun setFilter(text: String) {
        if (text == _uiState.value.filter) return
        _uiState.value = _uiState.value.copy(filter = text, scrollToIndex = 0, scrollToOffset = 0, scrollTrigger = _uiState.value.scrollTrigger + 1)
    }

    fun closeFilter() {
        setFilter("")
        _uiState.value = _uiState.value.copy(filterOpen = false)
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
