package com.wing.folderplayer.ui

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.ui.browser.BrowserScreen
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.MiniPlayerHeight
import com.wing.folderplayer.ui.player.PlayerSheetHost
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.ui.player.rememberPlayerSheetState
import kotlinx.coroutines.flow.Flow

/** Pages of the app. The player is not a page: it is shown over them (mini ⇄ full). */
object Routes {
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
}

/**
 * Browser-based app shell: Browser / Settings pages and, over them, the player that grows from the mini player into
 * the full player ([PlayerSheetHost]). Whether the player is open is kept in one place, the saveable
 * [com.wing.folderplayer.ui.player.PlayerSheetState] (its logical state; the fraction only follows it).
 * The ViewModels are the activity's (tests and the settings screen share them); playback lives in MusicService and is
 * never started, stopped or re-queued by opening or closing anything here.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
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
    val hasTrack by playerViewModel.hasTrack.collectAsState()
    val allPlaylists by playerViewModel.allPlaylists.collectAsState()
    LaunchedEffect(playerViewModel, browserViewModel) {
        playerViewModel.currentMediaId.collect { browserViewModel.updateCurrentlyPlaying(it) }
    }

    val entry by navController.currentBackStackEntryAsState()
    val onBrowser = entry?.destination?.route.let { it == null || it == Routes.BROWSER }

    // "Show the current track" (notification tap) stays pending until it can be answered: on a new activity without a
    // cached track the track is only known once the controller has connected. If there is still none then, the request
    // ends on the browser; nothing is started.
    var openPlayerPending by rememberSaveable { mutableStateOf(false) }
    val controllerReady by playerViewModel.controllerReady.collectAsState()
    LaunchedEffect(navController) {
        openPlayerRequests.collect {
            // Back from the player must land on the browser, not on whatever page was open.
            navController.popBackStack(Routes.BROWSER, inclusive = false)
            openPlayerPending = true
        }
    }
    LaunchedEffect(openPlayerPending, hasTrack, controllerReady) {
        if (!openPlayerPending) return@LaunchedEffect
        if (hasTrack) {
            sheet.expand()
            openPlayerPending = false
        } else if (controllerReady) {
            openPlayerPending = false
        }
    }

    // Every play request from the browser opens the full player; other actions (add to playlist, favourite) do not.
    val onFolderPlay = remember(playerViewModel, sheet) { { folder: SourceRef, start: String? -> playerViewModel.playFolder(folder, start); sheet.expand() } }
    val onCustomPlay = remember(playerViewModel, sheet) { { files: List<MusicFile>, index: Int -> playerViewModel.playCustomList(files, index); sheet.expand() } }
    val onCuePlay = remember(playerViewModel, sheet) { { cue: SourceRef -> playerViewModel.playCueSheet(cue); sheet.expand() } }
    val onAddToPlaylist = remember(playerViewModel) { { id: String, files: List<MusicFile> -> playerViewModel.addFilesToPlaylist(id, files) } }
    val openSettings = remember(navController, sheet) {
        {
            // No folding animation: Settings slides in at the same moment.
            sheet.snapTo(false)
            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
    }
    val dismissSession = remember(playerViewModel) { { playerViewModel.dismissSession() } }

    val miniVisible = hasTrack && onBrowser

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // Fully open and at rest: the pages underneath are not drawn (read while drawing, not composing).
                .graphicsLayer { alpha = if (sheet.isSettledFull) 0f else 1f }
                .testTag("app_pages")
                // Hidden from TalkBack while the full player is in front.
                .then(if (sheet.expanded) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            // The mini player (drawn by the player host) sits below the pages, never over the last list row: its place
            // is kept here, with the navigation bar inset it takes.
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
                        // Guarded: a double tap on ← must not pop the browser as well (empty page).
                        settings { if (navController.currentDestination?.route == Routes.SETTINGS) navController.popBackStack() }
                    }
                }
            }
            if (miniVisible) Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(MiniPlayerHeight))
        }

        PlayerSheetHost(sheet = sheet, viewModel = playerViewModel, miniAllowed = miniVisible, onDismiss = dismissSession)
    }
}
