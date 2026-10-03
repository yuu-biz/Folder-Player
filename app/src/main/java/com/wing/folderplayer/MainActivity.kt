package com.wing.folderplayer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.wing.folderplayer.ui.theme.FolderPlayerTheme

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import com.wing.folderplayer.ui.player.MainPlayerScreen
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.ui.browser.BrowserScreen
import com.wing.folderplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

import android.content.pm.ActivityInfo
import com.wing.folderplayer.data.prefs.OrientationPreferences
import com.wing.folderplayer.data.prefs.NotchPreferences
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import com.wing.folderplayer.ui.theme.FontManager
import com.wing.folderplayer.utils.AppLocale
import com.wing.folderplayer.utils.PermissionDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Incremented whenever runtime permissions may have changed (grant dialog, return from settings). */
    private val permissionEpoch = MutableStateFlow(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private fun applyOrientation(orientation: String) {
        requestedOrientation = when (orientation) {
            OrientationPreferences.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPreferences.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyNotchMode(mode: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                // FIXED: Always allow display cutout so switching doesn't trigger a layout jump/resize
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Toggle visual "Black Bar" effect by changing status bar transparency
            window.statusBarColor = when (mode) {
                NotchPreferences.NOTCH_BLACK_BAR -> android.graphics.Color.BLACK
                else -> android.graphics.Color.TRANSPARENT
            }
            window.navigationBarColor = android.graphics.Color.TRANSPARENT

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RE-ENABLE edge-to-edge so background can flow into notch area
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Apply saved orientation preference
        val orientationPrefs = OrientationPreferences(this)
        applyOrientation(orientationPrefs.getOrientation())

        // Apply saved notch mode preference
        val notchPrefs = NotchPreferences(this)
        applyNotchMode(notchPrefs.getNotchMode())

        // Audio + images (Android 13+), optional partial photo access (14+), notifications. Denying images must not
        // affect audio playback; covers in unindexed folders are reachable through SAF sources instead.
        val permissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            lastPermissionReport = PermissionDiagnostics.report(this)
            permissionEpoch.value++
        }
        permissionLauncher.launch(PermissionDiagnostics.mediaPermissions())

        val fonts = FontManager.get(this)

        setContent {
            val configuration = LocalConfiguration.current

            // Auto hide/show status bar based on orientation
            LaunchedEffect(configuration.orientation) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            val selectedFont by fonts.selected.collectAsState()
            val fontStates by fonts.states.collectAsState()
            val fontFamily = remember(selectedFont, fontStates[selectedFont]) { fonts.fontFamily(selectedFont) }

            FolderPlayerTheme(fontFamily = fontFamily) {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                    val playerViewModel: PlayerViewModel = viewModel()
                    val browserViewModel: com.wing.folderplayer.ui.browser.BrowserViewModel = viewModel()
                    val pagerState = rememberPagerState(pageCount = { 3 })
                    val scope = rememberCoroutineScope()

                    // Bridge State: Update browser's "playing" indicator when player state changes
                    LaunchedEffect(playerViewModel.uiState) {
                        playerViewModel.uiState.collect { state ->
                            browserViewModel.updateCurrentlyPlaying(state.currentMediaId)
                        }
                    }
                    val epoch by permissionEpoch.collectAsState()
                    LaunchedEffect(epoch) { if (epoch > 0) browserViewModel.onPermissionsChanged() }

                    HorizontalPager(state = pagerState) { page ->
                        when (page) {
                            0 -> MainPlayerScreen(
                                viewModel = playerViewModel
                            )
                            1 -> BrowserScreen(
                                viewModel = browserViewModel,
                                onFolderPlay = { folder, startPath ->
                                    playerViewModel.playFolder(folder, startPath)
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                onCustomPlay = { files, index ->
                                    playerViewModel.playCustomList(files, index)
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                onCuePlay = { cue ->
                                    playerViewModel.playCueSheet(cue)
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                allPlaylists = playerViewModel.uiState.collectAsState().value.allPlaylists,
                                onAddToPlaylist = { targetListId, musicFiles ->
                                    playerViewModel.addFilesToPlaylist(targetListId, musicFiles)
                                },
                                onBack = {
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                }
                            )
                            2 -> SettingsScreen(
                                onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                                playerViewModel = playerViewModel,
                                browserViewModel = browserViewModel,
                                permissionEpoch = epoch,
                                onRequestPermissions = { permissionLauncher.launch(PermissionDiagnostics.mediaPermissions()) },
                                onOrientationChange = { applyOrientation(it) },
                                onNotchModeChange = { applyNotchMode(it) },
                                onLanguageChange = { tag ->
                                    AppLocale.set(this@MainActivity, tag)
                                    recreate()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private var lastPermissionReport: PermissionDiagnostics.Report? = null

    override fun onResume() {
        super.onResume()
        // Permissions (or SAF grants) can be changed in system settings while we are in the background.
        val now = PermissionDiagnostics.report(this)
        if (lastPermissionReport != null && now != lastPermissionReport) permissionEpoch.value++
        lastPermissionReport = now
    }
}
