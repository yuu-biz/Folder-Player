package com.wing.folderplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.wing.folderplayer.ui.theme.FolderPlayerTheme

import com.wing.folderplayer.ui.AppRoot
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.settings.SettingsScreen
import androidx.lifecycle.ViewModelProvider
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    companion object {
        /** Notification tap: bring the app forward with the full player showing the current track. */
        const val ACTION_OPEN_PLAYER = "com.wing.folderplayer.action.OPEN_PLAYER"
    }

    /** Incremented whenever runtime permissions may have changed (grant dialog, return from settings). */
    private val permissionEpoch = MutableStateFlow(0)

    /** Requests to show the full player, buffered until the UI collects them. */
    private val openPlayerRequests = Channel<Unit>(Channel.CONFLATED)

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
        // The request is sent by every new activity, also a recreated one (language change), and answered at once when
        // nothing is asked. Only an actual change of the granted access reloads the browser (a reload resets its list
        // position); the state before the request is the reference.
        lastPermissionReport = PermissionDiagnostics.report(this)
        val permissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            val now = PermissionDiagnostics.report(this)
            if (now != lastPermissionReport) permissionEpoch.value++
            lastPermissionReport = now
        }
        permissionLauncher.launch(PermissionDiagnostics.mediaPermissions())

        // Activity-scoped (they survive recreation). The browser is the start page, so the player connects to the
        // service here, before any UI exists; a song tapped right after a cold start waits for that connection.
        // initializeController keeps only the application context and builds the controller once per ViewModel.
        val playerViewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        val browserViewModel = ViewModelProvider(this)[BrowserViewModel::class.java]
        playerViewModel.initializeController(this)

        // A recreated activity (language change, process restore) already handled the intent it was started with.
        if (savedInstanceState == null) handleIntent(intent)

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
                    val epoch by permissionEpoch.collectAsState()
                    LaunchedEffect(epoch) { if (epoch > 0) browserViewModel.onPermissionsChanged() }

                    AppRoot(
                        playerViewModel = playerViewModel,
                        browserViewModel = browserViewModel,
                        openPlayerRequests = remember { openPlayerRequests.receiveAsFlow() },
                        settings = { onBack ->
                            SettingsScreen(
                                onBack = onBack,
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
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: notification (and launcher) taps reach the running activity instead of stacking a second one.
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayerRequests.trySend(Unit)
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
