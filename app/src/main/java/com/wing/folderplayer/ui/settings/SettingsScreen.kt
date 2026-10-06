package com.wing.folderplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.BuildConfig
import com.wing.folderplayer.R
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel

/** Top-level groups of Settings, in the order they are listed. [id] is saved with the screen state and used in test tags. */
internal enum class SettingsCategory(val id: String, val title: Int, val description: Int?, val icon: ImageVector) {
    PLAYBACK("playback", R.string.settings_cat_playback, R.string.settings_cat_playback_desc, Icons.Default.PlayCircle),
    DISPLAY("display", R.string.settings_cat_display, R.string.settings_cat_display_desc, Icons.Default.Palette),
    LIBRARY("library", R.string.settings_cat_library, R.string.settings_cat_library_desc, Icons.Default.LibraryMusic),
    LYRICS("lyrics", R.string.settings_cat_lyrics, R.string.settings_cat_lyrics_desc, Icons.Default.Subject),
    STORAGE("storage", R.string.settings_permissions, R.string.settings_cat_storage_desc, Icons.Default.Storage),
    ABOUT("about", R.string.settings_cat_about, null, Icons.Default.Info);

    companion object {
        fun byId(id: String?) = values().firstOrNull { it.id == id }
    }
}

/** Pages one level below a category: the settings with a long list or text fields. */
internal enum class SettingsSub(val id: String, val title: Int, val parent: SettingsCategory) {
    LANGUAGE("language", R.string.settings_language, SettingsCategory.DISPLAY),
    FONT("font", R.string.settings_font, SettingsCategory.DISPLAY),
    THUMBNAILS("thumbnails", R.string.settings_thumbnails, SettingsCategory.LIBRARY),
    AI("ai", R.string.settings_ai, SettingsCategory.LYRICS);

    companion object {
        fun byId(id: String?) = values().firstOrNull { it.id == id }
    }
}

/** Width from which Settings shows the categories beside their content. Measured on the Settings window itself. */
private val TwoPaneMinWidth = 600.dp
private val CategoryListWidth = 300.dp

/**
 * Settings: categories → settings → (for long lists and text fields) a page of their own.
 * Narrow window: one pane, navigated category list → category → page. Wide window: the category list stays on the left,
 * its content (and pages) on the right. Where the user is (category, page) is one saved value each, so a recreation
 * (language change) or a change of the window size shows the same place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel,
    browserViewModel: BrowserViewModel,
    permissionEpoch: Int,
    onRequestPermissions: () -> Unit,
    onOrientationChange: (String) -> Unit = {},
    onNotchModeChange: (String) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
) {
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var subId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val horizontalSafePadding = WindowInsets.displayCutout.asPaddingValues().let {
        val layoutDirection = LocalLayoutDirection.current
        maxOf(it.calculateLeftPadding(layoutDirection), it.calculateRightPadding(layoutDirection))
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = horizontalSafePadding).testTag("settings_column")) {
            val twoPane = maxWidth >= TwoPaneMinWidth
            val category = SettingsCategory.byId(categoryId)
            // Narrow: no category = the list. Wide: the list is always there, and the first category is shown until one is chosen.
            val shownCategory = if (twoPane) category ?: SettingsCategory.values().first() else category
            val sub = SettingsSub.byId(subId)?.takeIf { it.parent == shownCategory }

            // One step up: page → category → category list → leave Settings.
            val up: () -> Unit = {
                when {
                    sub != null -> subId = null
                    !twoPane && category != null -> categoryId = null
                    else -> onBack()
                }
            }
            BackHandler(enabled = sub != null || (!twoPane && category != null)) { up() }

            val openCategory: (SettingsCategory) -> Unit = { categoryId = it.id; subId = null }
            val openSub: (SettingsSub) -> Unit = { subId = it.id }

            val pageContent: @Composable () -> Unit = {
                when {
                    shownCategory == null -> Unit
                    sub != null -> SettingsSubPage(sub, snackbar, onLanguageChange)
                    else -> SettingsCategoryPage(
                        shownCategory, openSub, snackbar, playerViewModel, browserViewModel, permissionEpoch,
                        onRequestPermissions, onOrientationChange, onNotchModeChange,
                    )
                }
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    // Wide: "Settings" over both panes; the page title is the header of the right pane.
                    val title = when {
                        twoPane || shownCategory == null -> stringResource(R.string.settings_title)
                        sub != null -> stringResource(sub.title)
                        else -> stringResource(shownCategory.title)
                    }
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                        navigationIcon = {
                            IconButton(onClick = { if (twoPane) onBack() else up() }, modifier = Modifier.testTag("settings_up")) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back))
                            }
                        },
                    )
                },
            ) { padding ->
                if (twoPane) {
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.width(CategoryListWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("settings_categories")) {
                            CategoryList(selected = shownCategory, onOpen = openCategory)
                        }
                        Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (shownCategory != null) PaneHeader(
                                title = stringResource((sub?.title ?: shownCategory.title)),
                                onBack = if (sub != null) up else null,
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) { pageContent() }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (shownCategory == null) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("settings_categories")) {
                                CategoryList(selected = null, onOpen = openCategory)
                            }
                        } else pageContent()
                    }
                }
            }
        }
    }
}

/** The categories, each with a short description (the version for About). */
@Composable
private fun CategoryList(selected: SettingsCategory?, onOpen: (SettingsCategory) -> Unit) {
    SettingsCategory.values().forEach { c ->
        val description = c.description?.let { stringResource(it) } ?: BuildConfig.VERSION_NAME
        SettingsNavRow(
            title = stringResource(c.title),
            value = description,
            tag = "settings_cat_${c.id}",
            onClick = { onOpen(c) },
            leadingIcon = c.icon,
            selected = c == selected,
        )
    }
}

/** Header of the right pane: the page's name, with a way back to the category while a page is open. */
@Composable
private fun PaneHeader(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("settings_pane_up")) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
    }
}
