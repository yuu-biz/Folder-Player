package com.wing.folderplayer.ui.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wing.folderplayer.R
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ImageUris
import com.wing.folderplayer.data.favorites.Bookmark
import com.wing.folderplayer.data.favorites.FavoritesKind
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.viewer.ViewerKind
import com.wing.folderplayer.data.viewer.ViewerTypes
import com.wing.folderplayer.ui.reorderGesture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    onFolderPlay: (SourceRef, String?) -> Unit,
    onCustomPlay: (List<MusicFile>, Int) -> Unit,
    onCuePlay: (SourceRef) -> Unit,
    allPlaylists: List<com.wing.folderplayer.data.playlist.Playlist> = emptyList(),
    onAddToPlaylist: (String, List<MusicFile>) -> Unit = { _, _ -> },
    /** Adds a song or a folder's songs to the queue (next: right after the track playing); gets the number added. */
    onAddToQueue: (MusicFile, Boolean, (Int) -> Unit) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit,
    /** False while something in front of the browser (the full player) owns Back. */
    backEnabled: Boolean = true,
    /** Wide windows only (null otherwise): whether the player pane is beside the browser, and how to show / hide it. */
    playerPane: PlayerPaneToggle? = null,
    viewModel: BrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val error by viewModel.error.collectAsState()
    val scrollTrigger by remember { derivedStateOf { uiState.scrollTrigger } }
    val context = LocalContext.current

    var editorType by remember { mutableStateOf<SourceType?>(null) }
    var sourceToEdit by remember { mutableStateOf<SourceConfig?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val label = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "SAF"
            viewModel.addSafSource(uri, label)
        }
    }

    // Back: close search / folder up / source root → source list. On the source list itself Back is left to the system.
    androidx.activity.compose.BackHandler(enabled = backEnabled && (!uiState.isRoot || uiState.search.active)) {
        viewModel.navigateUp()
    }

    if (editorType != null || sourceToEdit != null) {
        SourceEditorDialog(
            initial = sourceToEdit,
            initialType = sourceToEdit?.type ?: editorType ?: SourceType.WEBDAV,
            onDismiss = { editorType = null; sourceToEdit = null },
            onSave = { cfg, pass ->
                viewModel.saveSource(cfg, pass)
                editorType = null
                sourceToEdit = null
            },
            onTest = { cfg, pass -> viewModel.testConnection(cfg, pass) },
            onDiscover = { viewModel.discoverSmb() },
            onListShares = { cfg, pass -> viewModel.listSmbShares(cfg, pass) },
            onListSharesAsGuest = { host, port -> viewModel.listSmbSharesAsGuest(host, port) },
        )
    }

    val fileOpActions = remember(viewModel) {
        FileOpActions(
            listFolders = viewModel::listFolders,
            onRename = viewModel::renameFile,
            onMove = viewModel::moveFile,
            onDelete = viewModel::deleteFile,
            onDismiss = viewModel::cancelFileOp,
        )
    }
    val fileOpDialog: @Composable () -> Unit = {
        uiState.fileOp?.let { op ->
            val sourceName = uiState.availableSources.firstOrNull { it.id == op.file.sourceId }?.name.orEmpty()
            FileOpDialog(op, uiState.fileOpBusy, sourceName, fileOpActions)
        }
    }

    val externalOpener = rememberExternalOpener(viewModel::viewerFile)
    ExternalOpenProgress(externalOpener)
    uiState.viewer?.let { request ->
        FileViewerDialog(
            request = request,
            loadText = viewModel::viewerText,
            loadFile = viewModel::viewerFile,
            onOpenExternal = { externalOpener.open(it) },
            onFileOp = viewModel::startFileOp,
            onDismiss = { viewModel.closeViewer() },
            // The viewer is a window of its own: its file dialogs are shown inside it, above the file.
            overlay = { fileOpDialog() },
        )
    }
    if (uiState.viewer == null) fileOpDialog()

    val selected = uiState.selectedFileForPlaylist
    if (selected != null && !showPlaylistPicker) {
        FileActionsSheet(
            file = selected,
            isFavorite = viewModel.isFavorite(selected),
            isBookmarked = selected.isDirectory && uiState.isBookmarked(selected.ref),
            onOpen = if (ViewerTypes.isViewable(selected.name)) ({ viewModel.openViewer(selected) }) else null,
            onOpenExternal = if (ViewerTypes.isViewable(selected.name)) ({ viewModel.closePlaylistDialog(); externalOpener.open(selected) }) else null,
            onAddToPlaylist = { showPlaylistPicker = true },
            onQueue = if (selected.isDirectory || MediaTypes.isAudio(selected.name)) ({ next ->
                viewModel.closePlaylistDialog()
                onAddToQueue(selected, next) { added ->
                    if (added > 0) viewModel.showMessage(context.getString(if (next) R.string.queue_added_next else R.string.queue_added_end, selected.name))
                }
            }) else null,
            onToggleFavorite = { viewModel.toggleFavorite(selected); viewModel.closePlaylistDialog() },
            onToggleBookmark = if (selected.isDirectory) ({ viewModel.toggleBookmark(selected); viewModel.closePlaylistDialog() }) else null,
            onFileOp = if (!selected.isDirectory) viewModel::startFileOp else null,
            // A song listed away from its folder (songs list, favourites, search results).
            onShowInFolder = selected.ref.parent?.takeIf { !selected.isDirectory && (uiState.showingSongs || uiState.showingFavorites || uiState.search.active) }
                ?.let { folder -> { viewModel.closePlaylistDialog(); viewModel.revealInFolder(folder, selected.ref) } },
            onDismiss = { viewModel.closePlaylistDialog() },
        )
    }
    if (selected != null && showPlaylistPicker) {
        PlaylistSelectBottomSheet(
            playlists = allPlaylists,
            onPlaylistSelected = { playlistId ->
                onAddToPlaylist(playlistId, listOf(selected))
                showPlaylistPicker = false
                viewModel.onAddToPlaylistDone()
            },
            onDismiss = { showPlaylistPicker = false; viewModel.closePlaylistDialog() }
        )
    }

    val snackbar = remember { SnackbarHostState() }
    // The viewer is a window above the browser and would hide the snackbar: while it is open, a toast says it.
    LaunchedEffect(error) {
        error?.let {
            if (uiState.viewer != null) { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); viewModel.clearError() }
            else { snackbar.showSnackbar(it); viewModel.clearError() }
        }
    }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            if (uiState.viewer != null) { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); viewModel.clearMessage() }
            else { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); viewModel.clearMessage() }
        }
    }

    val horizontalSafePadding = WindowInsets.displayCutout.asPaddingValues().let {
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        maxOf(it.calculateLeftPadding(layoutDirection), it.calculateRightPadding(layoutDirection))
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = horizontalSafePadding)) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (uiState.search.active) {
                        SearchBarRow(
                            query = uiState.search.query,
                            onQuery = { viewModel.search(it) },
                            onClose = { viewModel.closeSearch() },
                            onOpenSettings = onOpenSettings,
                        )
                    } else TopAppBar(
                        title = {
                            Text(
                                when {
                                    uiState.favoritesKind == FavoritesKind.SONGS -> stringResource(R.string.browser_favorite_songs)
                                    uiState.favoritesKind == FavoritesKind.FOLDERS -> stringResource(R.string.browser_favorite_folders)
                                    uiState.showingSongs -> stringResource(R.string.browser_songs)
                                    uiState.currentFolder == null -> stringResource(R.string.browser_sources_title)
                                    else -> {
                                        val folder = uiState.currentFolder!!
                                        val srcName = uiState.currentSource?.name ?: ""
                                        if (folder.path == SourcePath.ROOT) srcName else "$srcName > ${SourcePath.name(folder.path)}"
                                    }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            // Same as system Back. The source list is the top level: nothing to go back to there.
                            if (!uiState.isRoot) {
                                Row {
                                    IconButton(onClick = { viewModel.navigateUp() }, modifier = Modifier.testTag("btn_browser_back")) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back))
                                    }
                                    // One folder up, whatever the folder was opened from (Back may lead to the favourites or bookmarks).
                                    if (uiState.currentFolder?.parent != null) {
                                        IconButton(onClick = { viewModel.goUp() }, modifier = Modifier.testTag("btn_browser_up")) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.browser_up))
                                        }
                                    }
                                    // Straight to the source list (the start page), however deep the user went.
                                    IconButton(onClick = { viewModel.exitSource() }, modifier = Modifier.testTag("btn_browser_home")) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.browser_back_to_sources))
                                    }
                                }
                            }
                        },
                        actions = {
                            if (uiState.isRoot) {
                                Box {
                                    IconButton(onClick = { showAddMenu = true }, modifier = Modifier.testTag("btn_add_source")) {
                                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browser_add_source))
                                    }
                                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                        DropdownMenuItem(text = { Text("WebDAV") }, onClick = { showAddMenu = false; editorType = SourceType.WEBDAV })
                                        DropdownMenuItem(text = { Text("SMB") }, onClick = { showAddMenu = false; editorType = SourceType.SMB })
                                        DropdownMenuItem(text = { Text("FTP / FTPS") }, onClick = { showAddMenu = false; editorType = SourceType.FTP })
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.browser_add_saf)) },
                                            onClick = { showAddMenu = false; treePicker.launch(null) },
                                            modifier = Modifier.testTag("menu_add_saf")
                                        )
                                    }
                                }
                                ShowPlayerPaneButton(playerPane)
                                BrowserOverflow(onOpenSettings) { close ->
                                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_sync_favorites)) },
                                        onClick = { close(); viewModel.syncFavorites() })
                                    if (uiState.bookmarks.any { b -> uiState.availableSources.any { it.id == b.sourceId } }) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.length_scan_bookmarks)) },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                        onClick = { close(); viewModel.scanAllBookmarkLengths() },
                                        modifier = Modifier.testTag("menu_scan_bookmark_lengths")
                                    )
                                    PlayerPaneMenuItem(playerPane, close)
                                }
                            } else {
                                // Little room (the browser pane beside the player, a phone held upright: Back, up and home take the left of
                                // the bar): refresh moves into the menu, so that the title keeps some room. Grid / list stays on the bar
                                // (used often), shuffle is always in the menu (used rarely).
                                val compact = playerPane?.shown == true || LocalConfiguration.current.screenWidthDp < 480
                                if (uiState.currentFolder != null) {
                                    IconButton(onClick = { viewModel.openSearch() }, modifier = Modifier.testTag("btn_search")) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.browser_search))
                                    }
                                }
                                // The songs list is a list of rows (title, artist and album), never a grid.
                                if (!uiState.showingSongs) IconButton(onClick = { viewModel.setViewMode(if (uiState.viewMode == "GRID") "LIST" else "GRID") }, modifier = Modifier.testTag("btn_view_mode")) {
                                    Icon(if (uiState.viewMode == "GRID") Icons.Default.ViewList else Icons.Default.GridView,
                                        contentDescription = stringResource(R.string.browser_toggle_view))
                                }
                                if (uiState.currentFolder != null && !compact) {
                                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("btn_refresh")) {
                                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.browser_refresh))
                                    }
                                }
                                ShowPlayerPaneButton(playerPane)
                                BrowserOverflow(onOpenSettings) { close ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_shuffle)) },
                                        leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                                        onClick = { close(); viewModel.shufflePlay(onFolderPlay, onCustomPlay) },
                                        modifier = Modifier.testTag("menu_shuffle")
                                    )
                                    if (compact && uiState.currentFolder != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_refresh)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = { close(); viewModel.refresh() },
                                        modifier = Modifier.testTag("menu_refresh")
                                    )
                                    if (uiState.currentFolder != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.length_scan_folder)) },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                        onClick = { close(); viewModel.scanCurrentFolderLengths() },
                                        modifier = Modifier.testTag("menu_scan_lengths")
                                    )
                                    if (uiState.currentFolder != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.songs_in_folder)) },
                                        leadingIcon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                        onClick = { close(); viewModel.showSongsIn(uiState.currentFolder!!) },
                                        modifier = Modifier.testTag("menu_songs_in_folder")
                                    )
                                    if (uiState.showingSongs && uiState.songsFolders.isNotEmpty()) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.length_scan_folder)) },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                        onClick = { close(); viewModel.scanSongsFolderLengths() },
                                        modifier = Modifier.testTag("menu_scan_songs_folder")
                                    )
                                    if (uiState.showingSongs && uiState.bookmarks.any { b -> uiState.availableSources.any { it.id == b.sourceId } }) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.length_scan_bookmarks)) },
                                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                        onClick = { close(); viewModel.scanAllBookmarkLengths() },
                                        modifier = Modifier.testTag("menu_scan_bookmark_lengths")
                                    )
                                    PlayerPaneMenuItem(playerPane, close)
                                    uiState.currentFolder?.let { folder ->
                                        val marked = uiState.isBookmarked(folder)
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (marked) R.string.browser_remove_bookmark else R.string.browser_bookmark_folder)) },
                                            leadingIcon = { Icon(if (marked) Icons.Default.BookmarkBorder else Icons.Default.Bookmark, contentDescription = null) },
                                            onClick = { close(); viewModel.toggleCurrentFolderBookmark() },
                                            modifier = Modifier.testTag("menu_bookmark")
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                val listState = rememberLazyListState()
                val gridState = rememberLazyGridState()
                val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

                val isGrid = uiState.viewMode == "GRID"
                LaunchedEffect(listState, gridState, isGrid) {
                    snapshotFlow {
                        if (isGrid) gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                        else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    }.collect { (i, o) -> viewModel.saveScrollPosition(i, o) }
                }
                LaunchedEffect(scrollTrigger) {
                    // Only a new load scrolls. Coming back to the browser (from Settings) re-runs this effect, and the
                    // restored list position must win over the position of the last load.
                    if (scrollTrigger > viewModel.handledScrollTrigger) {
                        viewModel.handledScrollTrigger = scrollTrigger
                        if (uiState.viewMode == "GRID") gridState.scrollToItem(uiState.scrollToIndex, uiState.scrollToOffset)
                        else listState.scrollToItem(uiState.scrollToIndex, uiState.scrollToOffset)
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(padding).then(if (uiState.search.active || uiState.filterOpen) Modifier.imePadding() else Modifier)) {
                    if (uiState.isLoading || uiState.search.running) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LengthScanStatus(viewModel)

                    if (uiState.search.active) {
                        SearchStatus(uiState.search, onCancel = { viewModel.cancelSearch() })
                    } else if (uiState.showingFavorites) {
                        SortHeader(
                            currentField = uiState.sortField,
                            ascending = uiState.sortAscending,
                            onSortClick = { viewModel.sortFiles(it) },
                            fields = if (uiState.favoritesKind == FavoritesKind.SONGS) FavoriteSongsSortFields else FavoriteFoldersSortFields,
                        )
                        LengthFillStatus(uiState, onScan = null)
                    } else if (uiState.showingSongs) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (uiState.filterOpen) viewModel.closeFilter() else viewModel.openFilter() },
                                modifier = Modifier.padding(start = 4.dp).semantics { this.selected = uiState.filterOpen }.testTag("btn_filter")
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = stringResource(R.string.browser_filter),
                                    tint = if (uiState.filterOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            SortHeader(
                                currentField = uiState.sortField,
                                ascending = uiState.sortAscending,
                                onSortClick = { viewModel.sortFiles(it) },
                                modifier = Modifier.weight(1f),
                                fields = SongsSortFields,
                            )
                        }
                        SongsFolderPicker(uiState, onAll = { viewModel.setSongsFolders(emptyList()) }, onToggle = { viewModel.toggleSongsFolder(it) })
                        if (uiState.filterOpen) FilterField(uiState.filter, onChange = { viewModel.setFilter(it) }, onClose = { viewModel.closeFilter() })
                    } else if (uiState.currentFolder != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (uiState.filterOpen) viewModel.closeFilter() else viewModel.openFilter() },
                                modifier = Modifier.padding(start = 4.dp).semantics { this.selected = uiState.filterOpen }.testTag("btn_filter")
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = stringResource(R.string.browser_filter),
                                    tint = if (uiState.filterOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            SortHeader(
                                currentField = uiState.sortField,
                                ascending = uiState.sortAscending,
                                onSortClick = { viewModel.sortFiles(it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        LengthFillStatus(uiState, onScan = { viewModel.scanCurrentFolderLengths() })
                        if (uiState.filterOpen) {
                            // Keyed by folder: Back to a folder brings its filter text back with it.
                            key(uiState.currentFolder) {
                                FilterField(uiState.filter, onChange = { viewModel.setFilter(it) }, onClose = { viewModel.closeFilter() })
                            }
                        }
                    }

                    // The grid follows the width of the list area (a pane of the wide layout is narrow in a landscape window).
                    var listWidth by remember { mutableStateOf(0.dp) }
                    val listDensity = androidx.compose.ui.platform.LocalDensity.current
                    Box(modifier = Modifier.weight(1f).onSizeChanged { listWidth = with(listDensity) { it.width.toDp() } }) {
                      androidx.compose.runtime.CompositionLocalProvider(LocalThumbnailRevision provides uiState.thumbnailRevision) {
                        val files = if (uiState.search.active) uiState.search.results else uiState.visibleFiles
                        val onClick: (MusicFile) -> Unit = { viewModel.onFileClicked(it, onFolderPlay, onCustomPlay, onCuePlay) }
                        val onLong: (MusicFile) -> Unit = { viewModel.onFileLongClick(it) }
                        when {
                            uiState.isRoot -> SourceList(
                                sources = uiState.availableSources,
                                favoriteSongs = uiState.favorites.count { it.type == FavoritesKind.SONGS.type && uiState.availableSources.any { s -> s.id == it.sourceId } },
                                favoriteFolders = uiState.favorites.count { it.type == FavoritesKind.FOLDERS.type && uiState.availableSources.any { s -> s.id == it.sourceId } },
                                bookmarks = uiState.bookmarks.filter { b -> uiState.availableSources.any { it.id == b.sourceId } },
                                onBookmarkClick = { viewModel.openBookmark(it) },
                                onBookmarkRemove = { viewModel.removeBookmark(it) },
                                onBookmarkRename = { b, name -> viewModel.renameBookmark(b, name) },
                                onBookmarkMove = { b, delta -> viewModel.moveBookmark(b, delta) },
                                onBookmarkReorder = { viewModel.reorderBookmarks(it) },
                                onBookmarkScan = { viewModel.scanBookmarkLengths(it) },
                                onBookmarkSongs = { viewModel.showSongsIn(it.ref) },
                                onFavoritesClick = { viewModel.showFavorites(it) },
                                songCount = uiState.songCount,
                                songsFolder = songsFoldersLabel(uiState.songsFolders, uiState.bookmarks, uiState.availableSources),
                                onSongsClick = { viewModel.showSongs() },
                                onSourceClick = { viewModel.selectSource(it) },
                                onEditSource = { sourceToEdit = it },
                                onDeleteSource = { viewModel.removeSource(it.id) },
                                onDuplicateSource = { viewModel.duplicateSource(it.id) },
                                onMoveUp = { viewModel.moveSourceUp(it.id) },
                                onMoveDown = { viewModel.moveSourceDown(it.id) },
                                onRepickSaf = { treePicker.launch(null) },
                                onReplaceRemoteFavorites = { viewModel.replaceRemoteFavorites(it.id) },
                            )
                            uiState.viewMode == "GRID" -> FileGrid(
                                files = files,
                                columns = gridColumns(uiState.gridDensity, listWidth),
                                currentlyPlaying = uiState.currentlyPlayingMediaId,
                                thumbnail = viewModel::folderThumbnail,
                                imageThumbnail = viewModel::imageThumbnail,
                                onFileClick = onClick,
                                onFileLongClick = onLong,
                                state = gridState,
                            )
                            else -> FileList(
                                files = files,
                                currentlyPlaying = uiState.currentlyPlayingMediaId,
                                thumbnail = viewModel::folderThumbnail,
                                imageThumbnail = viewModel::imageThumbnail,
                                duration = viewModel::trackDuration,
                                showSourcePath = uiState.search.active || uiState.showingFavorites,
                                songRows = uiState.showingSongs && !uiState.search.active,
                                sortField = uiState.sortField,
                                onFileClick = onClick,
                                onFileLongClick = onLong,
                                modifier = Modifier.drawWithContent {
                                    drawContent()
                                    val first = listState.firstVisibleItemIndex
                                    val total = files.size
                                    if (total > 0) {
                                        val height = size.height * (listState.layoutInfo.visibleItemsInfo.size.toFloat() / total)
                                        val offset = size.height * (first.toFloat() / total)
                                        drawRect(scrollbarColor, Offset(size.width - 4.dp.toPx(), offset), androidx.compose.ui.geometry.Size(4.dp.toPx(), height))
                                    }
                                },
                                listState = listState
                            )
                        }
                        if (uiState.showingSongs && !uiState.isLoading && files.isEmpty()) {
                            SongsEmptyState(
                                filter = uiState.filter.trim().takeIf { uiState.files.isNotEmpty() },
                                folder = songsFoldersLabel(uiState.songsFolders, uiState.bookmarks, uiState.availableSources),
                                onScan = when {
                                    uiState.songsFolders.isNotEmpty() -> ({ viewModel.scanSongsFolderLengths() })
                                    uiState.bookmarks.any { b -> uiState.availableSources.any { it.id == b.sourceId } } -> ({ viewModel.scanAllBookmarkLengths() })
                                    else -> null
                                },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        // A folder that cannot be read says so (and can be tried again); an empty one says that it is empty.
                        if (!uiState.isRoot && uiState.currentFolder != null && !uiState.isLoading && !uiState.search.active && files.isEmpty()) {
                            if (uiState.files.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.browser_filter_no_match, uiState.filter.trim()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp).testTag("filter_no_match"),
                                )
                            } else FolderEmptyState(uiState.loadError, onRetry = { viewModel.refresh() }, modifier = Modifier.align(Alignment.Center))
                        }
                      }
                    }
                }
            }
        }
    }
}

/** Columns of the grid: the chosen density for a phone-wide list, more for a list wide as a landscape phone (about 560 dp and more). */
private fun gridColumns(density: Int, width: androidx.compose.ui.unit.Dp): Int =
    if (width >= 560.dp) (density * 5 + 2) / 3 else density

/** The player pane of a wide window: [shown] beside the browser, or hidden so that the browser has the whole window. */
@Immutable
data class PlayerPaneToggle(val shown: Boolean, val onToggle: () -> Unit)

/** Top bar of a browser that has the whole wide window: brings the player pane back. Nothing otherwise. */
@Composable
private fun ShowPlayerPaneButton(pane: PlayerPaneToggle?) {
    if (pane == null || pane.shown) return
    IconButton(onClick = pane.onToggle, modifier = Modifier.testTag("btn_show_player_pane")) {
        Icon(Icons.Default.VerticalSplit, contentDescription = stringResource(R.string.layout_show_player))
    }
}

/** ⋮ row of a wide window: hides or shows the player pane (the same as the buttons on the bar and on the pane). */
@Composable
private fun PlayerPaneMenuItem(pane: PlayerPaneToggle?, close: () -> Unit) {
    if (pane == null) return
    DropdownMenuItem(
        text = { Text(stringResource(if (pane.shown) R.string.layout_hide_player else R.string.layout_show_player)) },
        leadingIcon = { Icon(if (pane.shown) Icons.Default.Fullscreen else Icons.Default.VerticalSplit, contentDescription = null) },
        onClick = { close(); pane.onToggle() },
        modifier = Modifier.testTag("menu_player_pane")
    )
}

/** ⋮ of every browser level: the level's own [items] first, Settings last. */
@Composable
private fun BrowserOverflow(onOpenSettings: () -> Unit, items: @Composable (close: () -> Unit) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("btn_overflow")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.browser_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items { open = false }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = { open = false; onOpenSettings() },
                modifier = Modifier.testTag("menu_settings")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarRow(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    var text by remember { mutableStateOf(query) }
    TopAppBar(
        title = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onQuery(it) },
                placeholder = { Text(stringResource(R.string.browser_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("search_field")
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
        },
        actions = { BrowserOverflow(onOpenSettings) }
    )
}

/** The filter of the folder on screen: narrows the list as the user types (it does not look into the folders below). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FilterField(filter: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(filter) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        placeholder = { Text(stringResource(R.string.browser_filter_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("filter_close")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
            }
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            .focusRequester(focus).testTag("filter_field"),
    )
    // Just opened: ready to type. Brought back with its text (Back to the folder): no keyboard over the list.
    LaunchedEffect(Unit) { if (filter.isEmpty()) focus.requestFocus() }
}

/**
 * An explicit scan of song lengths, on every browser level: what it has done so far with Cancel, then its result until
 * dismissed.
 */
@Composable
private fun LengthScanStatus(viewModel: BrowserViewModel) {
    val p by viewModel.lengthScan.collectAsState()
    if (!p.running && !p.showResult) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("length_scan_status")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.running) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(16.dp), strokeWidth = 2.dp)
            val counts = stringResource(R.string.length_scan_counts, p.folders, p.songs, p.read)
            val problems = listOfNotNull(
                if (p.notRead > 0) pluralStringResource(R.plurals.length_scan_not_read, p.notRead, p.notRead) else null,
                if (p.unparsable > 0) pluralStringResource(R.plurals.length_scan_unparsable, p.unparsable, p.unparsable) else null,
                if (p.skippedFolders > 0) pluralStringResource(R.plurals.length_scan_skipped, p.skippedFolders, p.skippedFolders) else null,
                if (p.truncated) stringResource(R.string.length_scan_truncated) else null,
            )
            Text(
                when {
                    p.running -> stringResource(R.string.length_scan_running, counts) + if (p.current.isNotEmpty()) " · " + p.current else ""
                    p.cancelled -> stringResource(R.string.length_scan_cancelled, counts)
                    else -> stringResource(R.string.length_scan_done, counts)
                } + problems.joinToString("") { " · $it" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("length_scan_text"),
            )
            if (p.running) TextButton(onClick = { viewModel.cancelLengthScan() }, modifier = Modifier.testTag("length_scan_cancel")) { Text(stringResource(R.string.common_cancel)) }
            else IconButton(onClick = { viewModel.dismissLengthScan() }, modifier = Modifier.testTag("length_scan_dismiss")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
    }
}

/**
 * A list sorted by length whose songs are not all known: how many lengths are being read, or how many are still unknown
 * (listed last) with a scan of the folder ([onScan]; null in the favourites).
 */
@Composable
private fun LengthFillStatus(uiState: BrowserUiState, onScan: (() -> Unit)?) {
    if (uiState.sortField != com.wing.folderplayer.data.source.SORT_LENGTH) return
    val fill = uiState.lengthFill
    val unknown = remember(uiState.files) { uiState.files.count { !it.isDirectory && MediaTypes.isAudio(it.name) && it.durationMs == null } }
    if (fill?.running != true && unknown == 0) return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (fill?.running == true) stringResource(R.string.length_fill_running, fill.done, fill.total)
            else pluralStringResource(R.plurals.length_fill_unknown, unknown, unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp).testTag("length_fill_status"),
        )
        if (fill?.running != true && onScan != null) {
            TextButton(onClick = onScan, modifier = Modifier.testTag("length_fill_scan")) { Text(stringResource(R.string.length_scan_action)) }
        }
    }
}

@Composable
private fun SearchStatus(s: SearchUiState, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.browser_search_results, s.results.size, s.results.size) + " · " +
                pluralStringResource(R.plurals.browser_search_scanned, s.foldersScanned, s.foldersScanned) +
                (if (s.foldersSkipped > 0) " · " + pluralStringResource(R.plurals.browser_search_skipped, s.foldersSkipped, s.foldersSkipped) else "") +
                (if (s.truncated) " · " + stringResource(R.string.browser_search_truncated) else ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).testTag("search_status")
        )
        if (s.running) TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
    }
}

private val FileSortFields = listOf("NAME" to R.string.sort_name, "DATE" to R.string.sort_date, "CREATED" to R.string.sort_created, "SIZE" to R.string.sort_size)
// By length: the songs (folders stay first, by name).
private val FolderSortFields = FileSortFields + (com.wing.folderplayer.data.source.SORT_LENGTH to R.string.sort_length)
private val FavoriteSongsSortFields = listOf(com.wing.folderplayer.data.favorites.FavoritesSort.ADDED to R.string.sort_added) + FolderSortFields
private val FavoriteFoldersSortFields = listOf(com.wing.folderplayer.data.favorites.FavoritesSort.ADDED to R.string.sort_added) + FileSortFields
private val SongsSortFields = com.wing.folderplayer.data.metadata.SongList.let {
    listOf(it.TITLE to R.string.sort_title, it.ARTIST to R.string.sort_artist, it.ALBUM to R.string.sort_album, com.wing.folderplayer.data.source.SORT_LENGTH to R.string.sort_length, it.DATE to R.string.sort_date)
}

/**
 * The songs list has none: nothing scanned yet ([onScan] reads the bookmarks, or the [folder] the list is narrowed to;
 * null without either), or [filter] matches none of them.
 */
@Composable
private fun SongsEmptyState(filter: String?, folder: String?, onScan: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 32.dp, vertical = 24.dp).testTag("songs_empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                filter != null -> stringResource(R.string.browser_filter_no_match, filter)
                folder != null -> stringResource(R.string.songs_folder_empty, folder)
                else -> stringResource(R.string.songs_empty)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filter == null && onScan != null) {
            Spacer(Modifier.height(12.dp))
            if (folder != null) Button(onClick = onScan, modifier = Modifier.testTag("songs_scan_folder")) { Text(stringResource(R.string.length_scan_folder)) }
            else Button(onClick = onScan, modifier = Modifier.testTag("songs_scan_bookmarks")) { Text(stringResource(R.string.length_scan_bookmarks)) }
        }
    }
}

/** How the songs list names [folder]: as its bookmark, as its source for a source's top folder, else "source › folder". */
private fun songsFolderLabel(folder: SourceRef, bookmarks: List<Bookmark>, sources: List<SourceConfig>): String {
    bookmarks.firstOrNull { it.sourceId == folder.sourceId && it.path == folder.path }?.let { return it.name.ifEmpty { SourcePath.name(it.path) } }
    val source = sources.firstOrNull { it.id == folder.sourceId }?.name.orEmpty()
    return if (folder.path == SourcePath.ROOT) source else "$source › ${SourcePath.name(folder.path)}"
}

/** The folders the songs list is narrowed to, by name, comma-separated; null: every folder. */
private fun songsFoldersLabel(folders: List<SourceRef>, bookmarks: List<Bookmark>, sources: List<SourceConfig>): String? =
    folders.takeIf { it.isNotEmpty() }?.joinToString(", ") { songsFolderLabel(it, bookmarks, sources) }

/**
 * The folders the songs list shows the songs of (with the folders below them): every folder, or any of the bookmarked
 * folders and sources together (the menu stays open while they are ticked; Back or a tap beside it closes it). Any
 * other folder is picked from its own menu ("Songs list of this folder") and is offered here while it is picked.
 */
@Composable
private fun SongsFolderPicker(state: BrowserUiState, onAll: () -> Unit, onToggle: (SourceRef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.songsFolders
    val narrowed = current.isNotEmpty()
    val bookmarks = state.bookmarks.filter { b -> state.availableSources.any { it.id == b.sourceId } }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(8.dp),
                color = if (narrowed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = if (narrowed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("songs_folder"),
            ) {
                Row(Modifier.heightIn(min = 40.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (narrowed) Icons.Default.FolderOpen else Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        songsFoldersLabel(current, state.bookmarks, state.availableSources) ?: stringResource(R.string.songs_folder_all),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).widthIn(max = 260.dp).testTag("songs_folder_label"),
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // "All folders" closes the menu; a folder is ticked or unticked and the menu stays for the next one.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.songs_folder_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                    trailingIcon = { if (!narrowed) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { open = false; if (narrowed) onAll() },
                    modifier = Modifier.semantics { selected = !narrowed }.testTag("songs_folder_all"),
                )
                @Composable
                fun option(folder: SourceRef, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String) {
                    val ticked = folder in current
                    DropdownMenuItem(
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        trailingIcon = { Icon(if (ticked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null) },
                        onClick = { onToggle(folder) },
                        modifier = Modifier.semantics { selected = ticked }.testTag(tag),
                    )
                }
                val listed = bookmarks.map { it.ref } + state.availableSources.map { SourceRef(it.id, SourcePath.ROOT) }
                current.filter { it !in listed }.forEachIndexed { i, folder ->
                    option(folder, songsFolderLabel(folder, state.bookmarks, state.availableSources), Icons.Default.FolderOpen, if (i == 0) "songs_folder_current" else "songs_folder_current_$i")
                }
                bookmarks.forEach { b -> option(b.ref, b.name.ifEmpty { SourcePath.name(b.path) }, Icons.Default.Bookmark, "songs_folder_bookmark_${b.name}") }
                state.availableSources.forEach { s -> option(SourceRef(s.id, SourcePath.ROOT), s.name, sourceIcon(s.type), "songs_folder_source_${s.id}") }
            }
        }
    }
}

/** [fields]: the sort fields offered, with their labels (the favourites list adds "Added" in front). */
@Composable
fun SortHeader(
    currentField: String,
    ascending: Boolean,
    onSortClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    fields: List<Pair<String, Int>> = FolderSortFields,
) {
    // Equal buttons while their names fit; with a large font or a narrow pane a button is as wide as its name and the
    // row scrolls sideways (a name is never broken in the middle of a word).
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val equalWidth = ((maxWidth - 16.dp - 8.dp * (fields.size - 1)) / fields.size).coerceAtLeast(48.dp)
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        fields.forEach { (field, label) ->
            val isSelected = currentField == field
            val direction = stringResource(if (ascending) R.string.settings_ascending else R.string.settings_descending)
            Surface(
                onClick = { onSortClick(field) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = equalWidth).semantics { selected = isSelected; if (isSelected) stateDescription = direction }.testTag("sortbtn_$field")
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp))
                    if (isSelected) {
                        Icon(
                            if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
    }
}

private fun sourceIcon(t: SourceType) = when (t) {
    SourceType.LOCAL -> Icons.Default.PhoneAndroid
    SourceType.SAF -> Icons.Default.FolderOpen
    SourceType.WEBDAV -> Icons.Default.Cloud
    SourceType.SMB -> Icons.Default.Dns
    SourceType.FTP -> Icons.Default.Storage
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceList(
    sources: List<SourceConfig>,
    favoriteSongs: Int,
    favoriteFolders: Int,
    onFavoritesClick: (FavoritesKind) -> Unit,
    /** Songs the length index knows (the songs list), and opening that list. */
    songCount: Int = 0,
    /** The name of the folder the songs list is narrowed to (null: every song). */
    songsFolder: String? = null,
    onSongsClick: () -> Unit = {},
    bookmarks: List<Bookmark>,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkRemove: (Bookmark) -> Unit,
    onBookmarkRename: (Bookmark, String) -> Unit,
    /** -1 up, +1 down. */
    onBookmarkMove: (Bookmark, Int) -> Unit,
    /** Ids of [bookmarks] in the order a drag left them. */
    onBookmarkReorder: (List<String>) -> Unit,
    /** Reads the song lengths in the bookmarked folder and below. */
    onBookmarkScan: (Bookmark) -> Unit,
    /** The songs list of the bookmarked folder and below. */
    onBookmarkSongs: (Bookmark) -> Unit = {},
    onSourceClick: (SourceConfig) -> Unit,
    onEditSource: (SourceConfig) -> Unit,
    onDeleteSource: (SourceConfig) -> Unit,
    onDuplicateSource: (SourceConfig) -> Unit,
    onMoveUp: (SourceConfig) -> Unit,
    onMoveDown: (SourceConfig) -> Unit,
    onRepickSaf: () -> Unit,
    onReplaceRemoteFavorites: (SourceConfig) -> Unit,
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    renaming?.let { b ->
        NameDialog(
            title = stringResource(R.string.common_rename),
            initial = b.name.ifEmpty { SourcePath.name(b.path) },
            confirm = stringResource(R.string.common_save),
            check = { null },
            onConfirm = { onBookmarkRename(b, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    fun rowKey(id: String) = "bookmark:$id"
    fun isBookmarkKey(key: Any) = (key as? String)?.startsWith("bookmark:") == true

    // The rows follow the shown bookmarks except while one is dragged; the new order is stored when the finger is lifted.
    val rows = remember { mutableStateListOf<Bookmark>().apply { addAll(bookmarks) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(bookmarks, draggingId == null) {
        if (draggingId != null || rows.toList() == bookmarks) return@LaunchedEffect
        rows.clear()
        rows.addAll(bookmarks)
    }

    fun startDrag(id: String) {
        if (rows.none { it.id == id }) return
        draggingId = id
        dragOffset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(dy: Float) {
        val id = draggingId ?: return
        dragOffset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == rowKey(id) } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        // Only past other bookmarks: the favourites entry, the header and the sources are not slots for a bookmark.
        val target = visible.firstOrNull { it.key != current.key && isBookmarkKey(it.key) && center >= it.offset && center <= it.offset + it.size } ?: return
        val from = rows.indexOfFirst { it.id == id }
        val to = rows.indexOfFirst { rowKey(it.id) == target.key }
        if (from < 0 || to < 0) return
        // Moving the first visible row would make the list scroll along with it; keep the viewport where it is.
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        rows.add(to, rows.removeAt(from))
        // The dragged row is now laid out in the target's place: keep it under the finger.
        dragOffset -= (target.offset - current.offset)
        if (current.index == firstIndex || target.index == firstIndex) {
            scope.launch { listState.scrollToItem(firstIndex, firstOffset) }
        }
    }

    fun endDrag() {
        if (draggingId == null) return
        draggingId = null
        dragOffset = 0f
        val order = rows.map { it.id }
        if (order != bookmarks.map { it.id }) onBookmarkReorder(order)
    }

    // Scroll while a row is held near the top or bottom edge.
    LaunchedEffect(draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        while (draggingId == id) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == rowKey(id) }
            if (item != null) {
                val top = item.offset + dragOffset
                val bottom = top + item.size
                val edge = item.size.toFloat()
                val step = when {
                    bottom > info.viewportEndOffset - edge / 2 -> edge / 6
                    top < info.viewportStartOffset + edge / 2 -> -edge / 6
                    else -> 0f
                }
                if (step != 0f) {
                    val consumed = listState.scrollBy(step)
                    dragOffset += consumed
                    dragBy(0f)
                }
            }
            delay(16)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.testTag("source_list")) {
        // Songs and folders are two lists (one fav.json): a song list to play from, a folder list to go to.
        item {
            // Every song read by a scan (or seen in a list), as a music player lists its library.
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_songs)) },
                supportingContent = {
                    val count = pluralStringResource(R.plurals.browser_songs_count, songCount, songCount)
                    Text(if (songsFolder != null) "$count · $songsFolder" else count, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("entry_songs_count"))
                },
                leadingContent = { Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onSongsClick() }.testTag("entry_songs")
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_favorite_songs)) },
                supportingContent = { Text(pluralStringResource(R.plurals.browser_favorite_songs_count, favoriteSongs, favoriteSongs)) },
                leadingContent = { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onFavoritesClick(FavoritesKind.SONGS) }.testTag("entry_favorite_songs")
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_favorite_folders)) },
                supportingContent = { Text(pluralStringResource(R.plurals.browser_favorite_folders_count, favoriteFolders, favoriteFolders)) },
                leadingContent = { Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onFavoritesClick(FavoritesKind.FOLDERS) }.testTag("entry_favorite_folders")
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
        if (rows.isNotEmpty()) {
            item(key = "bookmarks_header") {
                Text(
                    stringResource(R.string.browser_bookmarks),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() }.testTag("bookmarks_header")
                )
            }
            itemsIndexed(rows, key = { _, it -> rowKey(it.id) }) { index, bookmark ->
                var showMenu by remember { mutableStateOf(false) }
                val sourceName = sources.firstOrNull { it.id == bookmark.sourceId }?.name.orEmpty()
                val dragging = draggingId == bookmark.id
                Column(
                    (if (dragging) Modifier.zIndex(1f) else Modifier.animateItemPlacement())
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 8.dp.toPx() else 0f
                        }
                ) {
                    Box {
                        ListItem(
                            headlineContent = { Text(bookmark.name.ifEmpty { SourcePath.name(bookmark.path) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(sourceName + " · " + bookmark.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            // Handle: drag at once; a long press on the row stays the menu.
                            trailingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .reorderGesture(longPress = false, onStart = { startDrag(bookmark.id) }, onDrag = ::dragBy, onEnd = ::endDrag)
                                        .testTag("bookmark_handle_${bookmark.name}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.bookmark_drag))
                                }
                            },
                            modifier = Modifier
                                .combinedClickable(onClick = { onBookmarkClick(bookmark) }, onLongClick = { showMenu = true })
                                .testTag("bookmark_${bookmark.name}")
                        )
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { renaming = bookmark; showMenu = false },
                                modifier = Modifier.testTag("bookmark_rename")
                            )
                            if (index > 0) DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_move_up)) },
                                leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                                onClick = { onBookmarkMove(bookmark, -1); showMenu = false },
                                modifier = Modifier.testTag("bookmark_move_up")
                            )
                            if (index < rows.lastIndex) DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_move_down)) },
                                leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                                onClick = { onBookmarkMove(bookmark, +1); showMenu = false },
                                modifier = Modifier.testTag("bookmark_move_down")
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.songs_in_folder)) },
                                leadingIcon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                onClick = { onBookmarkSongs(bookmark); showMenu = false },
                                modifier = Modifier.testTag("bookmark_songs")
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.length_scan_bookmark)) },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                onClick = { onBookmarkScan(bookmark); showMenu = false },
                                modifier = Modifier.testTag("bookmark_scan_lengths")
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_remove_bookmark)) },
                                leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                                onClick = { onBookmarkRemove(bookmark); showMenu = false },
                                modifier = Modifier.testTag("bookmark_remove")
                            )
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
        items(sources, key = { it.id }) { source ->
            var showMenu by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.describe(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(sourceIcon(source.type), contentDescription = null) },
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSourceClick(source) },
                            onLongClick = { if (!source.builtIn) showMenu = true }
                        )
                        .testTag("source_${source.name}")
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (source.type != SourceType.SAF) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { onEditSource(source); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_duplicate)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = { onDuplicateSource(source); showMenu = false }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_repick_saf)) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = { onRepickSaf(); showMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_move_up)) },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                        onClick = { onMoveUp(source); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_move_down)) },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        onClick = { onMoveDown(source); showMenu = false }
                    )
                    if (source.syncPath.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_replace_remote_favorites)) },
                            leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                            onClick = { onReplaceRemoteFavorites(source); showMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { onDeleteSource(source); showMenu = false }
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

private fun isPlaying(file: MusicFile, mediaId: String?): Boolean =
    mediaId != null && !file.isDirectory && com.wing.folderplayer.data.source.SourceUris.parse(mediaId) == file.ref

private fun isParentOfPlaying(file: MusicFile, mediaId: String?): Boolean {
    if (!file.isDirectory || mediaId == null) return false
    val ref = com.wing.folderplayer.data.source.SourceUris.parse(mediaId) ?: return false
    return ref.sourceId == file.sourceId && SourcePath.isAncestorOrSelf(file.path, ref.path) && ref.path != file.path
}

private val LocalThumbnailRevision = androidx.compose.runtime.compositionLocalOf { 0 }

/** Folder image for a directory, loaded only while the item is on screen (leaving the screen cancels it). */
@Composable
private fun FolderThumb(file: MusicFile, thumbnail: suspend (SourceRef) -> ArtworkResult, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp) {
    val revision = LocalThumbnailRevision.current
    // Re-run when the folder changed (mtime) or a refresh / permission change / cache clear asked for it.
    val result by produceState<ArtworkResult?>(initialValue = null, file.sourceId, file.path, file.lastModified, revision) {
        value = runCatching { thumbnail(file.ref) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else ArtworkResult.fromException(it) }
    }
    val r = result
    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (r is ArtworkResult.Found) {
            AsyncImage(
                model = ImageUris.of(r.image, r.entry),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("thumb_${file.name}")
            )
        } else if (r is ArtworkResult.Failed) {
            // An image that could not be read is not "no image": say why (and it is not cached as absent).
            val (icon, label) = when (r.kind) {
                ArtworkResult.Kind.PERMISSION -> Icons.Default.Lock to stringResource(R.string.thumb_error_permission)
                ArtworkResult.Kind.DECODE -> Icons.Default.BrokenImage to stringResource(R.string.thumb_error_decode)
                else -> Icons.Default.CloudOff to stringResource(R.string.thumb_error_network)
            }
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize * 0.8f).testTag("thumb_err_${file.name}"),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
        } else {
            Icon(Icons.Default.Folder, contentDescription = null,
                modifier = Modifier.size(iconSize).then(if (r == ArtworkResult.None) Modifier.testTag("thumb_none_${file.name}") else Modifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

/**
 * Thumbnail of an image file (not a folder): the whole picture, not cropped, on a framed tile with an image badge in the
 * corner, so that it is not taken for a folder (whose image fills its tile edge to edge). Loaded only while on screen;
 * with thumbnails off for the source (or Wi-Fi required), too large or unreadable, the image icon stays.
 */
@Composable
private fun ImageThumb(file: MusicFile, thumbnail: (MusicFile) -> ArtworkResult, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp, badgeSize: androidx.compose.ui.unit.Dp) {
    val revision = LocalThumbnailRevision.current
    val result by produceState<ArtworkResult?>(initialValue = null, file.sourceId, file.path, file.size, file.lastModified, revision) {
        value = thumbnail(file)
    }
    val r = result
    // null = not loaded yet (or not asked), true = shown, false = the picture could not be read or decoded.
    var loaded by remember(r) { mutableStateOf<Boolean?>(null) }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded != true) {
                Icon(if (loaded == false) Icons.Default.BrokenImage else Icons.Default.Image, contentDescription = null,
                    modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            if (r is ArtworkResult.Found) {
                AsyncImage(
                    model = ImageUris.of(r.image, r.entry),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        loaded = when (state) {
                            is coil.compose.AsyncImagePainter.State.Success -> true
                            is coil.compose.AsyncImagePainter.State.Error -> false
                            else -> loaded
                        }
                    },
                    modifier = Modifier.fillMaxSize().testTag("imgthumb_${file.name}")
                )
            }
        }
        // The badge says "a picture" whatever the picture shows.
        if (loaded == true) {
            Icon(
                Icons.Default.Image, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(badgeSize)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)).padding(2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileList(
    files: List<MusicFile>,
    currentlyPlaying: String?,
    thumbnail: suspend (SourceRef) -> ArtworkResult,
    imageThumbnail: (MusicFile) -> ArtworkResult,
    duration: suspend (MusicFile) -> Long?,
    showSourcePath: Boolean,
    /** The songs list: title (or file name), artist and album, and the length kept with them. */
    songRows: Boolean = false,
    /** Sort key: size or date is then shown beside the format, so a list sorted by it says why. */
    sortField: String = "NAME",
    onFileClick: (MusicFile) -> Unit,
    onFileLongClick: (MusicFile) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val nowPlayingLabel = stringResource(R.string.browser_now_playing)
    val detailsLabel = stringResource(R.string.browser_row_details)

    LazyColumn(state = listState, modifier = modifier.testTag("file_list")) {
        items(files, key = { it.sourceId + it.path }) { file ->
            val playing = isPlaying(file, currentlyPlaying)
            val parentOfPlaying = remember(file.path, currentlyPlaying) { isParentOfPlaying(file, currentlyPlaying) }
            val isUnsupported = !file.isDirectory && !MediaTypes.isAudio(file.name) && !MediaTypes.isCue(file.name) && !ViewerTypes.isViewable(file.name)

            Column {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Reading order: name first, then the kind of file (or where it is), then the length at the end.
                    // Size and modification time are in the actions sheet (long press), not in every row.
                    ListItem(
                        headlineContent = {
                            Text(
                                if (songRows) com.wing.folderplayer.data.metadata.SongList.title(file) else file.name,
                                color = when {
                                    playing -> MaterialTheme.colorScheme.primary
                                    parentOfPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    isUnsupported -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else -> Color.Unspecified
                                },
                                // Two lines: a long name stays readable at 320 dp and with a large font; the end is cut last.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing || parentOfPlaying) androidx.compose.ui.text.font.FontWeight.Bold else null
                            )
                        },
                        supportingContent = when {
                            songRows -> ({
                                // Artist and album when the tags have them, the folder otherwise.
                                val line = listOfNotNull(file.tags?.artist?.takeIf { it.isNotBlank() }, file.tags?.album?.takeIf { it.isNotBlank() })
                                    .joinToString(" • ").ifEmpty { SourcePath.parent(file.path) ?: "/" }
                                Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("info_${file.name}"))
                            })
                            showSourcePath -> ({
                                Text(SourcePath.parent(file.path) ?: "/", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("info_${file.name}"))
                            })
                            !file.isDirectory && file.extension.isNotEmpty() -> ({
                                val sortValue = when {
                                    isUnsupported -> null
                                    sortField == "SIZE" && file.size > 0 -> formatSize(file.size)
                                    sortField == "DATE" && file.lastModified > 0 -> dateFormatter.format(Date(file.lastModified))
                                    sortField == "CREATED" && file.createdOrModified > 0 -> dateFormatter.format(Date(file.createdOrModified))
                                    else -> null
                                }
                                Text(
                                    listOfNotNull(file.extension.uppercase(), sortValue).joinToString(" • "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isUnsupported) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else Color.Unspecified,
                                    modifier = Modifier.testTag("info_${file.name}")
                                )
                            })
                            else -> null
                        },
                        trailingContent = if (MediaTypes.isAudio(file.name)) ({
                            // Track length, read only while the row is on screen (and kept); shown when known, nothing before
                            // (no placeholder). Refresh (revision) looks again, e.g. after network lengths were switched on.
                            val length by produceState<Long?>(file.durationMs.takeIf { songRows }, file.sourceId, file.path, file.size, file.lastModified, LocalThumbnailRevision.current) {
                                if (songRows && file.durationMs != null) return@produceState
                                value = runCatching { duration(file) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
                            }
                            length?.let {
                                Text(
                                    com.wing.folderplayer.data.metadata.DurationFormat.format(it),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.testTag("duration_${file.name}")
                                )
                            }
                        }) else null,
                        leadingContent = {
                            if (file.isDirectory && !playing) {
                                FolderThumb(file, thumbnail, Modifier.size(40.dp), 24.dp)
                            } else if (ViewerTypes.kind(file.name) == ViewerKind.IMAGE) {
                                ImageThumb(file, imageThumbnail, Modifier.size(40.dp), 24.dp, 14.dp)
                            } else {
                                val icon = if (playing) Icons.Default.PlayCircleFilled else fileIcon(file)
                                val tint = when {
                                    playing || parentOfPlaying -> MaterialTheme.colorScheme.primary
                                    isUnsupported -> LocalContentColor.current.copy(alpha = 0.3f)
                                    else -> LocalContentColor.current
                                }
                                Icon(icon, contentDescription = null, tint = tint)
                            }
                        },
                        modifier = Modifier
                            .combinedClickable(
                                enabled = !isUnsupported,
                                role = Role.Button,
                                onLongClickLabel = detailsLabel,
                                onClick = { onFileClick(file) },
                                onLongClick = { onFileLongClick(file) }
                            )
                            .then(if (playing) Modifier.semantics { stateDescription = nowPlayingLabel } else Modifier)
                            .testTag("item_${file.name}")
                    )
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGrid(
    files: List<MusicFile>,
    columns: Int,
    currentlyPlaying: String?,
    thumbnail: suspend (SourceRef) -> ArtworkResult,
    imageThumbnail: (MusicFile) -> ArtworkResult,
    onFileClick: (MusicFile) -> Unit,
    onFileLongClick: (MusicFile) -> Unit,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
) {
    val nowPlayingLabel = stringResource(R.string.browser_now_playing)
    val detailsLabel = stringResource(R.string.browser_row_details)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("file_grid")
    ) {
        items(files, key = { it.sourceId + it.path }) { file ->
            val playing = isPlaying(file, currentlyPlaying) || isParentOfPlaying(file, currentlyPlaying)
            val unsupported = !file.isDirectory && !MediaTypes.isAudio(file.name) && !MediaTypes.isCue(file.name) && !ViewerTypes.isViewable(file.name)
            Column(
                modifier = Modifier
                    .combinedClickable(enabled = !unsupported, role = Role.Button, onLongClickLabel = detailsLabel, onClick = { onFileClick(file) }, onLongClick = { onFileLongClick(file) })
                    .then(if (playing) Modifier.semantics { stateDescription = nowPlayingLabel } else Modifier)
                    .testTag("item_${file.name}"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (file.isDirectory) {
                    FolderThumb(file, thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), 40.dp)
                } else if (ViewerTypes.kind(file.name) == ViewerKind.IMAGE) {
                    ImageThumb(file, imageThumbnail, Modifier.fillMaxWidth().aspectRatio(1f), 36.dp, 20.dp)
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            fileIcon(file),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = if (unsupported) LocalContentColor.current.copy(alpha = 0.3f) else LocalContentColor.current
                        )
                    }
                }
                Text(
                    file.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** The icon of a file that is not a folder: song, CUE sheet, a file the viewer opens, or another file. */
private fun fileIcon(file: MusicFile): androidx.compose.ui.graphics.vector.ImageVector = when {
    MediaTypes.isCue(file.name) -> Icons.Default.Description
    MediaTypes.isAudio(file.name) -> Icons.Default.MusicNote
    else -> when (ViewerTypes.kind(file.name)) {
        ViewerKind.IMAGE -> Icons.Default.Image
        ViewerKind.PDF -> Icons.Default.PictureAsPdf
        ViewerKind.TEXT -> Icons.Default.Article
        null -> Icons.Default.InsertDriveFile
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionsSheet(
    file: MusicFile,
    isFavorite: Boolean,
    isBookmarked: Boolean,
    /** Files the viewer shows: null = no "Open" rows. */
    onOpen: (() -> Unit)?,
    onOpenExternal: (() -> Unit)?,
    onAddToPlaylist: () -> Unit,
    /** Songs and folders: null = no queue rows. */
    onQueue: ((next: Boolean) -> Unit)?,
    onToggleFavorite: () -> Unit,
    /** Folders only: null = no bookmark row. */
    onToggleBookmark: (() -> Unit)?,
    /** Files only: null = no rename / move / delete rows. */
    onFileOp: ((FileOp) -> Unit)?,
    /** null = no "Show in folder" row. */
    onShowInFolder: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            // The details left out of the list rows: format, size, modification time, and where the file is.
            val details = remember(file) {
                val date = if (file.lastModified > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified)) else null
                listOfNotNull(
                    file.extension.takeIf { !file.isDirectory && it.isNotEmpty() }?.uppercase(),
                    formatSize(file.size).takeIf { !file.isDirectory && file.size > 0 },
                    date,
                ).joinToString(" • ")
            }
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("action_details"))
            Text(SourcePath.parent(file.path) ?: "/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            if (onOpen != null) ListItem(
                headlineContent = { Text(stringResource(R.string.viewer_open)) },
                leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
                modifier = Modifier.clickable { onOpen() }.testTag("action_open")
            )
            if (onOpenExternal != null) ListItem(
                headlineContent = { Text(stringResource(R.string.viewer_open_external)) },
                leadingContent = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                modifier = Modifier.clickable { onOpenExternal() }.testTag("action_open_external")
            )
            if (onShowInFolder != null) ListItem(
                headlineContent = { Text(stringResource(R.string.action_show_in_folder)) },
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable { onShowInFolder() }.testTag("action_show_in_folder")
            )
            // Playlists and favourites hold what can be played: folders, songs and CUE sheets.
            val playable = file.isDirectory || MediaTypes.isAudio(file.name) || MediaTypes.isCue(file.name)
            if (onQueue != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_play_next)) },
                    leadingContent = { Icon(Icons.Default.QueuePlayNext, contentDescription = null) },
                    modifier = Modifier.clickable { onQueue(true) }.testTag("action_play_next")
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_add_to_queue)) },
                    leadingContent = { Icon(Icons.Default.AddToQueue, contentDescription = null) },
                    modifier = Modifier.clickable { onQueue(false) }.testTag("action_add_queue")
                )
            }
            if (playable) ListItem(
                headlineContent = { Text(stringResource(R.string.browser_add_to_playlist)) },
                leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable { onAddToPlaylist() }.testTag("action_add_playlist")
            )
            if (playable || isFavorite) ListItem(
                headlineContent = { Text(stringResource(if (isFavorite) R.string.browser_remove_favorite else R.string.browser_add_favorite)) },
                leadingContent = { Icon(if (isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite, contentDescription = null) },
                modifier = Modifier.clickable { onToggleFavorite() }.testTag("action_favorite")
            )
            if (onToggleBookmark != null) ListItem(
                headlineContent = { Text(stringResource(if (isBookmarked) R.string.browser_remove_bookmark else R.string.browser_add_bookmark)) },
                leadingContent = { Icon(if (isBookmarked) Icons.Default.BookmarkBorder else Icons.Default.Bookmark, contentDescription = null) },
                modifier = Modifier.clickable { onToggleBookmark() }.testTag("action_bookmark")
            )
            if (onFileOp != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.common_rename)) },
                    leadingContent = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    modifier = Modifier.clickable { onFileOp(FileOp.Rename(file)) }.testTag("action_rename")
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.file_move)) },
                    leadingContent = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    modifier = Modifier.clickable { onFileOp(FileOp.Move(file)) }.testTag("action_move")
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.common_delete)) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                    modifier = Modifier.clickable { onFileOp(FileOp.Delete(file)) }.testTag("action_delete")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSelectBottomSheet(
    playlists: List<com.wing.folderplayer.data.playlist.Playlist>,
    onPlaylistSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.browser_add_to_playlist),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            LazyColumn {
                items(playlists) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        leadingContent = {
                            Icon(
                                if (playlist.id == "default") Icons.Default.Audiotrack else Icons.Default.PlaylistPlay,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { onPlaylistSelected(playlist.id) }
                    )
                }
            }
        }
    }
}

/** Nothing to list: an unreadable folder shows why (with Retry), an empty one says so. Both are announced as one item. */
@Composable
private fun FolderEmptyState(error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp).testTag(if (error != null) "folder_error" else "folder_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (error != null) Icons.Default.ErrorOutline else Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            error ?: stringResource(R.string.browser_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("folder_retry")) { Text(stringResource(R.string.common_retry)) }
    }
}
