package com.wing.folderplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.ui.browser.BrowserScreen
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.MainPlayerScreen
import com.wing.folderplayer.ui.player.MiniPlayer
import com.wing.folderplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Pages of the app. The player is not a page: it is shown over them (mini ⇄ full). */
object Routes {
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
}

/**
 * Whether the full player is open — the only place this is kept. Mini / full / nothing follow from it and from
 * whether there is a track. (Stage 2 replaces the boolean with an expansion fraction driven by drag.)
 */
@Stable
class PlayerSheetState(expanded: Boolean) {
    var expanded by mutableStateOf(expanded)
        private set

    fun expand() { expanded = true }
    fun collapse() { expanded = false }

    companion object {
        val Saver: Saver<PlayerSheetState, Boolean> = Saver(save = { it.expanded }, restore = { PlayerSheetState(it) })
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState = rememberSaveable(saver = PlayerSheetState.Saver) { PlayerSheetState(false) }

/**
 * Browser-based app shell: Browser / Settings pages, the mini player under the browser and the full player over both.
 * The ViewModels are the activity's (tests and the settings screen share them); playback lives in MusicService and is
 * never started, stopped or re-queued by opening or closing anything here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(
    playerViewModel: PlayerViewModel,
    browserViewModel: BrowserViewModel,
    /** "Show the current track" (notification tap). */
    openPlayerRequests: Flow<Unit>,
    settings: @Composable (onBack: () -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val sheet = rememberPlayerSheetState()

    // Narrow views of the player state: the full state changes every second while playing and must not recompose the
    // browser or this shell.
    val hasTrack by remember(playerViewModel) { playerViewModel.uiState.map { it.hasTrack }.distinctUntilChanged() }
        .collectAsState(playerViewModel.uiState.value.hasTrack)
    val allPlaylists by remember(playerViewModel) { playerViewModel.uiState.map { it.allPlaylists }.distinctUntilChanged() }
        .collectAsState(playerViewModel.uiState.value.allPlaylists)
    LaunchedEffect(playerViewModel, browserViewModel) {
        playerViewModel.uiState.map { it.currentMediaId }.distinctUntilChanged().collect { browserViewModel.updateCurrentlyPlaying(it) }
    }

    val entry by navController.currentBackStackEntryAsState()
    val onBrowser = entry?.destination?.route.let { it == null || it == Routes.BROWSER }

    LaunchedEffect(navController, sheet) {
        openPlayerRequests.collect {
            // Back from the player must land on the browser, not on whatever page was open.
            navController.popBackStack(Routes.BROWSER, inclusive = false)
            if (playerViewModel.uiState.value.hasTrack) sheet.expand()
        }
    }

    // Every play request from the browser opens the full player; other actions (add to playlist, favourite) do not.
    val onFolderPlay = remember(playerViewModel, sheet) { { folder: SourceRef, start: String? -> playerViewModel.playFolder(folder, start); sheet.expand() } }
    val onCustomPlay = remember(playerViewModel, sheet) { { files: List<MusicFile>, index: Int -> playerViewModel.playCustomList(files, index); sheet.expand() } }
    val onCuePlay = remember(playerViewModel, sheet) { { cue: SourceRef -> playerViewModel.playCueSheet(cue); sheet.expand() } }
    val onAddToPlaylist = remember(playerViewModel) { { id: String, files: List<MusicFile> -> playerViewModel.addFilesToPlaylist(id, files) } }
    val openSettings = remember(navController, sheet) {
        {
            sheet.collapse()
            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
    }
    val openPlayer = remember(sheet) { { sheet.expand() } }
    val collapsePlayer = remember(sheet) { { sheet.collapse() } }

    val fullPlayer = remember { MutableTransitionState(sheet.expanded) }
    fullPlayer.targetState = sheet.expanded
    // Fully open and settled: the pages underneath are not drawn.
    val covered = fullPlayer.currentState && fullPlayer.isIdle
    val miniVisible = hasTrack && onBrowser

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (covered) 0f else 1f }
                // Hidden from TalkBack while the full player is in front.
                .then(if (sheet.expanded) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            // The mini player sits below the pages (never over the last list row) and takes the navigation bar inset.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (miniVisible) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.BROWSER,
                    enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(250)) { it / 8 } },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 8 } },
                ) {
                    composable(Routes.BROWSER) {
                        BrowserScreen(
                            viewModel = browserViewModel,
                            onFolderPlay = onFolderPlay,
                            onCustomPlay = onCustomPlay,
                            onCuePlay = onCuePlay,
                            allPlaylists = allPlaylists,
                            onAddToPlaylist = onAddToPlaylist,
                            onOpenSettings = openSettings,
                            backEnabled = !sheet.expanded,
                        )
                    }
                    composable(Routes.SETTINGS) {
                        settings { navController.popBackStack() }
                    }
                }
            }
            if (miniVisible) MiniPlayer(playerViewModel, onOpen = openPlayer)
        }

        AnimatedVisibility(
            visibleState = fullPlayer,
            enter = slideInVertically(tween(250)) { it / 4 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it / 4 } + fadeOut(tween(180)),
        ) {
            // Composed only while open, so it is registered after (and takes precedence over) the page handlers;
            // the browser's handler is also disabled while the player is open.
            BackHandler(enabled = sheet.expanded) { sheet.collapse() }
            MainPlayerScreen(viewModel = playerViewModel, onCollapse = collapsePlayer)
        }
    }
}
