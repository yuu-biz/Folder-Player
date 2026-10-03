package com.wing.folderplayer.ui.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wing.folderplayer.R
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ImageUris
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceType
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
    onOpenSettings: () -> Unit,
    /** False while something in front of the browser (the full player) owns Back. */
    backEnabled: Boolean = true,
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
        )
    }

    val selected = uiState.selectedFileForPlaylist
    if (selected != null && !showPlaylistPicker) {
        FileActionsSheet(
            file = selected,
            isFavorite = viewModel.isFavorite(selected),
            onAddToPlaylist = { showPlaylistPicker = true },
            onToggleFavorite = { viewModel.toggleFavorite(selected); viewModel.closePlaylistDialog() },
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
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(uiState.message) { uiState.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); viewModel.clearMessage() } }

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
                                    uiState.showingFavorites -> stringResource(R.string.browser_favorites)
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
                                IconButton(onClick = { viewModel.navigateUp() }, modifier = Modifier.testTag("btn_browser_back")) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.browser_up))
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
                                BrowserOverflow(onOpenSettings) { close ->
                                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_sync_favorites)) },
                                        onClick = { close(); viewModel.syncFavorites() })
                                }
                            } else {
                                if (uiState.currentFolder != null) {
                                    IconButton(onClick = { viewModel.openSearch() }, modifier = Modifier.testTag("btn_search")) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.browser_search))
                                    }
                                }
                                IconButton(onClick = { viewModel.setViewMode(if (uiState.viewMode == "GRID") "LIST" else "GRID") }, modifier = Modifier.testTag("btn_view_mode")) {
                                    Icon(if (uiState.viewMode == "GRID") Icons.Default.ViewList else Icons.Default.GridView,
                                        contentDescription = stringResource(R.string.browser_toggle_view))
                                }
                                IconButton(onClick = { viewModel.shufflePlay(onFolderPlay, onCustomPlay) }) {
                                    Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.browser_shuffle))
                                }
                                if (uiState.currentFolder != null) {
                                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("btn_refresh")) {
                                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.browser_refresh))
                                    }
                                }
                                BrowserOverflow(onOpenSettings) { close ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browser_back_to_sources)) },
                                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                        onClick = { close(); viewModel.exitSource() },
                                        modifier = Modifier.testTag("menu_sources")
                                    )
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

                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (uiState.isLoading || uiState.search.running) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (uiState.search.active) {
                        SearchStatus(uiState.search, onCancel = { viewModel.cancelSearch() })
                    } else if (uiState.currentFolder != null) {
                        SortHeader(
                            currentField = uiState.sortField,
                            ascending = uiState.sortAscending,
                            onSortClick = { viewModel.sortFiles(it) }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                      androidx.compose.runtime.CompositionLocalProvider(LocalThumbnailRevision provides uiState.thumbnailRevision) {
                        val files = if (uiState.search.active) uiState.search.results else uiState.files
                        val onClick: (MusicFile) -> Unit = { viewModel.onFileClicked(it, onFolderPlay, onCustomPlay, onCuePlay) }
                        val onLong: (MusicFile) -> Unit = { viewModel.onFileLongClick(it) }
                        when {
                            uiState.isRoot -> SourceList(
                                sources = uiState.availableSources,
                                favoritesCount = uiState.favorites.size,
                                onFavoritesClick = { viewModel.showFavorites() },
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
                                columns = gridColumns(uiState.gridDensity),
                                currentlyPlaying = uiState.currentlyPlayingMediaId,
                                thumbnail = viewModel::folderThumbnail,
                                onFileClick = onClick,
                                onFileLongClick = onLong,
                                state = gridState,
                            )
                            else -> FileList(
                                files = files,
                                currentlyPlaying = uiState.currentlyPlayingMediaId,
                                thumbnail = viewModel::folderThumbnail,
                                showSourcePath = uiState.search.active || uiState.showingFavorites,
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
                      }
                    }
                }
            }
        }
    }
}

@Composable
private fun gridColumns(density: Int): Int {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    return if (landscape) (density * 5 + 2) / 3 else density
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

@Composable
fun SortHeader(currentField: String, ascending: Boolean, onSortClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("NAME" to R.string.sort_name, "DATE" to R.string.sort_date, "SIZE" to R.string.sort_size).forEach { (field, label) ->
            val isSelected = currentField == field
            Surface(
                onClick = { onSortClick(field) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
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
    favoritesCount: Int,
    onFavoritesClick: () -> Unit,
    onSourceClick: (SourceConfig) -> Unit,
    onEditSource: (SourceConfig) -> Unit,
    onDeleteSource: (SourceConfig) -> Unit,
    onDuplicateSource: (SourceConfig) -> Unit,
    onMoveUp: (SourceConfig) -> Unit,
    onMoveDown: (SourceConfig) -> Unit,
    onRepickSaf: () -> Unit,
    onReplaceRemoteFavorites: (SourceConfig) -> Unit,
) {
    LazyColumn(modifier = Modifier.testTag("source_list")) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_favorites)) },
                supportingContent = { Text(pluralStringResource(R.plurals.browser_favorites_count, favoritesCount, favoritesCount)) },
                leadingContent = { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onFavoritesClick() }.testTag("entry_favorites")
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileList(
    files: List<MusicFile>,
    currentlyPlaying: String?,
    thumbnail: suspend (SourceRef) -> ArtworkResult,
    showSourcePath: Boolean,
    onFileClick: (MusicFile) -> Unit,
    onFileLongClick: (MusicFile) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(state = listState, modifier = modifier.testTag("file_list")) {
        items(files, key = { it.sourceId + it.path }) { file ->
            val playing = isPlaying(file, currentlyPlaying)
            val parentOfPlaying = remember(file.path, currentlyPlaying) { isParentOfPlaying(file, currentlyPlaying) }
            val isUnsupported = !file.isDirectory && !MediaTypes.isAudio(file.name) && !MediaTypes.isCue(file.name)

            Column {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Text(
                                file.name,
                                color = when {
                                    playing -> MaterialTheme.colorScheme.primary
                                    parentOfPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    isUnsupported -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else -> Color.Unspecified
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing || parentOfPlaying) androidx.compose.ui.text.font.FontWeight.Bold else null
                            )
                        },
                        supportingContent = {
                            when {
                                showSourcePath -> Text(SourcePath.parent(file.path) ?: "/", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                !file.isDirectory -> Text(
                                    "${formatSize(file.size)} • ${file.extension.uppercase()}",
                                    color = if (isUnsupported) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else Color.Unspecified
                                )
                            }
                        },
                        leadingContent = {
                            if (file.isDirectory && !playing) {
                                FolderThumb(file, thumbnail, Modifier.size(40.dp), 24.dp)
                            } else {
                                val icon = when {
                                    playing -> Icons.Default.PlayCircleFilled
                                    MediaTypes.isCue(file.name) -> Icons.Default.Description
                                    MediaTypes.isAudio(file.name) -> Icons.Default.MusicNote
                                    else -> Icons.Default.InsertDriveFile
                                }
                                val tint = when {
                                    playing || parentOfPlaying -> MaterialTheme.colorScheme.primary
                                    isUnsupported -> LocalContentColor.current.copy(alpha = 0.3f)
                                    else -> LocalContentColor.current
                                }
                                Icon(icon, contentDescription = null, tint = tint)
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            enabled = !isUnsupported,
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) }
                        ).testTag("item_${file.name}")
                    )

                    if (file.lastModified > 0 && !showSourcePath) {
                        Text(
                            text = dateFormatter.format(Date(file.lastModified)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isUnsupported) 0.2f else 0.4f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 4.dp)
                        )
                    }
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
    onFileClick: (MusicFile) -> Unit,
    onFileLongClick: (MusicFile) -> Unit,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
) {
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
            val unsupported = !file.isDirectory && !MediaTypes.isAudio(file.name) && !MediaTypes.isCue(file.name)
            Column(
                modifier = Modifier
                    .combinedClickable(enabled = !unsupported, onClick = { onFileClick(file) }, onLongClick = { onFileLongClick(file) })
                    .testTag("item_${file.name}"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (file.isDirectory) {
                    FolderThumb(file, thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), 40.dp)
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when {
                                MediaTypes.isCue(file.name) -> Icons.Default.Description
                                MediaTypes.isAudio(file.name) -> Icons.Default.MusicNote
                                else -> Icons.Default.InsertDriveFile
                            },
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
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp), maxLines = 2)
            ListItem(
                headlineContent = { Text(stringResource(R.string.browser_add_to_playlist)) },
                leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable { onAddToPlaylist() }.testTag("action_add_playlist")
            )
            ListItem(
                headlineContent = { Text(stringResource(if (isFavorite) R.string.browser_remove_favorite else R.string.browser_add_favorite)) },
                leadingContent = { Icon(if (isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite, contentDescription = null) },
                modifier = Modifier.clickable { onToggleFavorite() }.testTag("action_favorite")
            )
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
