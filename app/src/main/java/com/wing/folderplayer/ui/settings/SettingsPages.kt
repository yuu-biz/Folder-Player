package com.wing.folderplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.BuildConfig
import com.wing.folderplayer.R
import com.wing.folderplayer.cast.CastSettings
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.prefs.LyricPreferences
import com.wing.folderplayer.data.prefs.NotchPreferences
import com.wing.folderplayer.data.prefs.OrientationPreferences
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.repo.TitleMode
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.playback.ExportSettings
import com.wing.folderplayer.playback.NativeDecoder
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.ui.theme.AppFont
import com.wing.folderplayer.ui.theme.FontManager
import com.wing.folderplayer.ui.theme.FontState
import com.wing.folderplayer.utils.AppLocale
import com.wing.folderplayer.utils.CrashHandler
import com.wing.folderplayer.utils.PermissionDiagnostics
import kotlinx.coroutines.launch

/** A scrolling page of settings. The keyboard never covers the focused field (it is scrolled into view). */
@Composable
private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
}

/** The content of one category. */
@Composable
internal fun SettingsCategoryPage(
    category: SettingsCategory,
    openSub: (SettingsSub) -> Unit,
    snackbar: SnackbarHostState,
    playerViewModel: PlayerViewModel,
    browserViewModel: BrowserViewModel,
    permissionEpoch: Int,
    onRequestPermissions: () -> Unit,
    onOrientationChange: (String) -> Unit,
    onNotchModeChange: (String) -> Unit,
) {
    when (category) {
        SettingsCategory.PLAYBACK -> PlaybackPage()
        SettingsCategory.DISPLAY -> DisplayPage(openSub, playerViewModel, onOrientationChange, onNotchModeChange)
        SettingsCategory.LIBRARY -> LibraryPage(openSub, browserViewModel)
        SettingsCategory.LYRICS -> LyricsPage(openSub, playerViewModel)
        SettingsCategory.STORAGE -> StoragePage(snackbar, browserViewModel, permissionEpoch, onRequestPermissions)
        SettingsCategory.ABOUT -> AboutPage()
    }
}

/** A page below a category. */
@Composable
internal fun SettingsSubPage(sub: SettingsSub, snackbar: SnackbarHostState, onLanguageChange: (String) -> Unit) {
    when (sub) {
        SettingsSub.LANGUAGE -> LanguagePage(onLanguageChange)
        SettingsSub.FONT -> FontPage(snackbar)
        SettingsSub.THUMBNAILS -> ThumbnailsPage()
        SettingsSub.AI -> AiPage()
    }
}

// ---------------------------------------------------------------- Playback

@Composable
private fun PlaybackPage() {
    val context = LocalContext.current
    val castSettings = remember { CastSettings(context) }
    val exportSettings = remember { ExportSettings(context) }
    SettingsPage {
        var castOn by remember { mutableStateOf(castSettings.enabled) }
        SettingsSwitch(stringResource(R.string.settings_dlna), castOn, "dlna_enabled") { castOn = it; castSettings.enabled = it }
        var exportOn by remember { mutableStateOf(exportSettings.enabled) }
        SettingsSwitch(
            stringResource(R.string.settings_auto_save), exportOn, "auto_save",
            description = stringResource(R.string.settings_auto_save_help, exportSettings.folderName),
        ) { exportOn = it; exportSettings.enabled = it }
        SettingsGroupTitle(stringResource(R.string.settings_decoders))
        SettingsInfo(
            if (NativeDecoder.isAvailable()) stringResource(R.string.settings_native_decoder_ok, NativeDecoder.version())
            else stringResource(R.string.settings_native_decoder_missing),
            modifier = Modifier.testTag("native_decoder_status"),
        )
    }
}

// ---------------------------------------------------------------- Display

@Composable
private fun fontLabel(f: AppFont, fonts: FontManager): String = when (f) {
    AppFont.SYSTEM -> stringResource(R.string.settings_system)
    AppFont.NOTO_SANS_SC -> "Noto Sans SC (思源黑体)"
    AppFont.LXGW_WENKAI -> "LXGW WenKai (霞鹜文楷)"
    AppFont.SARASA_UI_SC -> "Sarasa UI SC (更纱黑体)"
    AppFont.IMPORTED -> stringResource(R.string.settings_font_imported_label, fonts.importedName.ifEmpty { "—" })
}

@Composable
private fun DisplayPage(
    openSub: (SettingsSub) -> Unit,
    playerViewModel: PlayerViewModel,
    onOrientationChange: (String) -> Unit,
    onNotchModeChange: (String) -> Unit,
) {
    val context = LocalContext.current
    // Only the two display choices: the full player state changes about once a second while playing.
    val coverDisplaySize by playerViewModel.coverDisplaySize.collectAsState()
    val backgroundStyle by playerViewModel.backgroundStyle.collectAsState()
    val playbackPrefs = remember { PlaybackPreferences(context) }
    val fonts = remember { FontManager.get(context) }
    val selectedFont by fonts.selected.collectAsState()
    SettingsPage {
        val systemLabel = stringResource(R.string.settings_system)
        val language = AppLocale.LANGUAGES.firstOrNull { it.first == AppLocale.get(context) }?.second.orEmpty().ifEmpty { systemLabel }
        SettingsNavRow(stringResource(R.string.settings_language), language, "settings_sub_${SettingsSub.LANGUAGE.id}", { openSub(SettingsSub.LANGUAGE) })
        SettingsNavRow(stringResource(R.string.settings_font), fontLabel(selectedFont, fonts), "settings_sub_${SettingsSub.FONT.id}", { openSub(SettingsSub.FONT) })

        SettingsGroupTitle(stringResource(R.string.settings_group_player))
        SettingsChoice(
            stringResource(R.string.settings_cover_size),
            listOf("STANDARD" to stringResource(R.string.settings_cover_standard), "LARGE" to stringResource(R.string.settings_cover_large)),
            coverDisplaySize, "cover",
        ) { playerViewModel.setCoverDisplaySize(it) }
        SettingsChoice(
            stringResource(R.string.settings_background),
            listOf(
                "GRADIENT" to stringResource(R.string.settings_background_solid), "BLUR" to stringResource(R.string.settings_background_blur),
                "BLACK" to stringResource(R.string.settings_background_black),
            ),
            backgroundStyle, "bg",
        ) { playerViewModel.setBackgroundStyle(it) }
        var titleMode by remember { mutableStateOf(playbackPrefs.getTitleMode()) }
        SettingsChoice(
            stringResource(R.string.settings_title_mode),
            listOf("FILENAME" to stringResource(R.string.settings_title_filename), "TAGS" to stringResource(R.string.settings_title_tags)),
            titleMode, "title",
        ) { titleMode = it; playerViewModel.setTitleMode(TitleMode.valueOf(it)) }

        SettingsGroupTitle(stringResource(R.string.settings_group_screen))
        val orientationPrefs = remember { OrientationPreferences(context) }
        var currentOrientation by remember { mutableStateOf(orientationPrefs.getOrientation()) }
        SettingsChoice(
            stringResource(R.string.settings_orientation),
            listOf(
                OrientationPreferences.ORIENTATION_SYSTEM to stringResource(R.string.settings_system),
                OrientationPreferences.ORIENTATION_PORTRAIT to stringResource(R.string.settings_portrait),
                OrientationPreferences.ORIENTATION_LANDSCAPE to stringResource(R.string.settings_landscape),
            ),
            currentOrientation, "orientation",
        ) { currentOrientation = it; orientationPrefs.setOrientation(it); onOrientationChange(it) }
        val notchPrefs = remember { NotchPreferences(context) }
        var currentNotchMode by remember { mutableStateOf(notchPrefs.getNotchMode()) }
        SettingsChoice(
            stringResource(R.string.settings_notch),
            listOf(
                NotchPreferences.NOTCH_FULLSCREEN to stringResource(R.string.settings_notch_fullscreen),
                NotchPreferences.NOTCH_BLACK_BAR to stringResource(R.string.settings_notch_black_bar),
            ),
            currentNotchMode, "notch",
        ) { currentNotchMode = it; notchPrefs.setNotchMode(it); onNotchModeChange(it) }
    }
}

@Composable
private fun LanguagePage(onLanguageChange: (String) -> Unit) {
    val context = LocalContext.current
    val current = AppLocale.get(context)
    val systemLabel = stringResource(R.string.settings_system)
    SettingsPage {
        // Each language is named in itself (never translated), the system default in the language shown now.
        AppLocale.LANGUAGES.forEach { (tag, name) ->
            SettingsRadioRow(
                label = name.ifEmpty { systemLabel },
                selected = current == tag,
                onClick = { if (current != tag) onLanguageChange(tag) },
                modifier = Modifier.testTag("lang_$tag"),
            )
        }
    }
}

@Composable
private fun FontPage(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fonts = remember { FontManager.get(context) }
    val selectedFont by fonts.selected.collectAsState()
    val fontStates by fonts.states.collectAsState()
    val fallback by fonts.fallbackReason.collectAsState()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "font"
            try {
                fonts.import(uri, name)
                fonts.select(AppFont.IMPORTED)
                snackbar.showSnackbar(context.getString(R.string.settings_font_imported, name))
            } catch (e: Exception) {
                snackbar.showSnackbar(context.getString(R.string.settings_font_import_failed, e.message ?: ""))
            }
        }
    }
    SettingsPage {
        AppFont.values().forEach { f ->
            val state = fontStates[f]
            val status = when (state) {
                FontState.Ready -> if (f == AppFont.SYSTEM) "" else stringResource(R.string.settings_font_ready)
                FontState.NotDownloaded -> if (f == AppFont.IMPORTED) "" else stringResource(R.string.settings_font_not_downloaded)
                is FontState.Downloading -> stringResource(R.string.settings_font_downloading, (state.progress * 100).toInt())
                is FontState.Failed -> stringResource(R.string.settings_font_failed, state.reason)
                null -> ""
            }
            Column {
                SettingsRadioRow(
                    label = fontLabel(f, fonts),
                    selected = selectedFont == f,
                    enabled = state == FontState.Ready,
                    onClick = { fonts.select(f) },
                    status = status.takeIf { it.isNotEmpty() },
                    statusTag = "font_status_${f.id}",
                    modifier = Modifier.testTag("font_${f.id}"),
                )
                val canDownload = f.url != null && state !is FontState.Downloading && state != FontState.Ready
                val canDelete = f != AppFont.SYSTEM && state == FontState.Ready
                if (canDownload || canDelete) {
                    Row(Modifier.padding(start = 52.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canDownload) TextButton(
                            onClick = { scope.launch { if (fonts.download(f)) fonts.select(f) } },
                            modifier = Modifier.testTag("font_dl_${f.id}"),
                        ) { Text(stringResource(R.string.settings_font_download)) }
                        if (canDelete) TextButton(onClick = { fonts.delete(f) }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("font_import"),
        ) { Text(stringResource(R.string.settings_font_import)) }
        SettingsHelp(stringResource(R.string.settings_font_license))
        fallback?.let { Text(stringResource(R.string.settings_font_fallback, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    }
}

// ---------------------------------------------------------------- Library & network

@Composable
private fun LibraryPage(openSub: (SettingsSub) -> Unit, browserViewModel: BrowserViewModel) {
    val context = LocalContext.current
    val browserState by browserViewModel.uiState.collectAsState()
    val sourcePrefs = remember { SourcePreferences(context) }
    val thumbs = remember { ThumbnailRepository.get(context) }
    SettingsPage {
        var defaultSort by remember { mutableStateOf(sourcePrefs.getDefaultSort()) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_default_sort), style = MaterialTheme.typography.bodyLarge)
            ChoiceChips(
                listOf("NAME" to stringResource(R.string.sort_name), "DATE" to stringResource(R.string.sort_date), "CREATED" to stringResource(R.string.sort_created), "SIZE" to stringResource(R.string.sort_size)),
                defaultSort.field, "sort", Modifier.padding(top = 4.dp),
            ) { defaultSort = defaultSort.copy(field = it); sourcePrefs.saveDefaultSort(defaultSort.field, defaultSort.ascending) }
            ChoiceChips(
                listOf(true to stringResource(R.string.settings_ascending), false to stringResource(R.string.settings_descending)),
                defaultSort.ascending, "sortdir", Modifier.padding(top = 4.dp),
            ) { defaultSort = defaultSort.copy(ascending = it); sourcePrefs.saveDefaultSort(defaultSort.field, defaultSort.ascending) }
        }
        var defaultView by remember { mutableStateOf(sourcePrefs.getDefaultViewMode()) }
        SettingsChoice(
            stringResource(R.string.browser_toggle_view),
            listOf("LIST" to stringResource(R.string.settings_view_list), "GRID" to stringResource(R.string.settings_view_grid)),
            defaultView, "defview",
        ) { defaultView = it; sourcePrefs.saveDefaultViewMode(it) }
        SettingsChoice(
            stringResource(R.string.settings_grid_density),
            (2..5).map { it to it.toString() },
            browserState.gridDensity, "grid",
        ) { browserViewModel.setGridDensity(it) }

        SettingsGroupTitle(stringResource(R.string.settings_group_network))
        val types = THUMB_TYPES
        val enabled = types.count { thumbs.settings.thumbnailsEnabled(it) }
        SettingsNavRow(
            stringResource(R.string.settings_thumbnails),
            stringResource(R.string.settings_thumbs_summary, enabled, types.size),
            "settings_sub_${SettingsSub.THUMBNAILS.id}",
            { openSub(SettingsSub.THUMBNAILS) },
        )
    }
}

private val THUMB_TYPES = listOf(SourceType.LOCAL, SourceType.SAF, SourceType.WEBDAV, SourceType.SMB, SourceType.FTP)

@Composable
private fun ThumbnailsPage() {
    val context = LocalContext.current
    val thumbs = remember { ThumbnailRepository.get(context) }
    SettingsPage {
        var rev by remember { mutableIntStateOf(0) }
        key(rev) {
            THUMB_TYPES.forEach { t ->
                val label = when (t) {
                    SourceType.LOCAL -> stringResource(R.string.settings_thumbs_local)
                    SourceType.SAF -> stringResource(R.string.settings_thumbs_saf)
                    SourceType.WEBDAV -> "WebDAV"
                    SourceType.SMB -> "SMB"
                    SourceType.FTP -> "FTP"
                }
                SettingsSwitch(label, thumbs.settings.thumbnailsEnabled(t), "thumbs_${t.name}") {
                    thumbs.settings.setThumbnailsEnabled(t, it); thumbs.invalidateNegatives(); rev++
                }
            }
            SettingsSwitch(stringResource(R.string.settings_thumbs_wifi_only), thumbs.settings.wifiOnly, "thumbs_wifi") { thumbs.settings.wifiOnly = it; rev++ }
        }
        SettingsHelp(stringResource(R.string.settings_thumbs_help))
    }
}

// ---------------------------------------------------------------- Lyrics & AI

@Composable
private fun LyricsPage(openSub: (SettingsSub) -> Unit, playerViewModel: PlayerViewModel) {
    val context = LocalContext.current
    val lyricPrefs = remember { LyricPreferences(context) }
    SettingsPage {
        var lyricApiUrl by remember { mutableStateOf(lyricPrefs.getLyricApiUrl()) }
        OutlinedTextField(
            value = lyricApiUrl, onValueChange = { lyricApiUrl = it; lyricPrefs.setLyricApiUrl(it) },
            label = { Text(stringResource(R.string.settings_lyric_api)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("lyric_api_url"),
        )
        var priority by remember { mutableStateOf(lyricPrefs.lyricsPriority) }
        SettingsChoice(
            stringResource(R.string.settings_lyrics_priority),
            listOf("LOCAL_FIRST" to stringResource(R.string.settings_lyrics_local_first), "AI_FIRST" to stringResource(R.string.settings_lyrics_ai_first)),
            priority, "lyrics_priority",
        ) { priority = it; lyricPrefs.lyricsPriority = it; playerViewModel.invalidateLyrics() }
        var aiAuto by remember { mutableStateOf(lyricPrefs.aiLyricsAuto) }
        SettingsSwitch(stringResource(R.string.settings_ai_lyrics_auto), aiAuto, "ai_lyrics_auto", description = stringResource(R.string.settings_ai_lyrics_help)) {
            aiAuto = it; lyricPrefs.aiLyricsAuto = it; playerViewModel.invalidateLyrics()
        }
        var lyricLang by remember { mutableStateOf(lyricPrefs.aiLyricsLanguage) }
        OutlinedTextField(
            value = lyricLang, onValueChange = { lyricLang = it; lyricPrefs.aiLyricsLanguage = it },
            label = { Text(stringResource(R.string.settings_ai_lyrics_language)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai_lyrics_language"),
        )
        var translation by remember { mutableStateOf(lyricPrefs.translationTarget) }
        OutlinedTextField(
            value = translation, onValueChange = { translation = it; lyricPrefs.translationTarget = it },
            label = { Text(stringResource(R.string.settings_translation_target)) }, placeholder = { Text("zh-CN / en / ja …") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SettingsGroupTitle(stringResource(R.string.settings_group_ai))
        val model = lyricPrefs.getAiModel()
        SettingsNavRow(
            stringResource(R.string.settings_ai),
            if (model.isBlank()) stringResource(R.string.settings_not_set) else model,
            "settings_sub_${SettingsSub.AI.id}",
            { openSub(SettingsSub.AI) },
        )
    }
}

@Composable
private fun AiPage() {
    val context = LocalContext.current
    val lyricPrefs = remember { LyricPreferences(context) }
    SettingsPage {
        SettingsHelp(stringResource(R.string.settings_ai_lyrics_help))
        var aiBaseUrl by remember { mutableStateOf(lyricPrefs.getAiBaseUrl()) }
        OutlinedTextField(
            value = aiBaseUrl, onValueChange = { aiBaseUrl = it; lyricPrefs.setAiBaseUrl(it) },
            label = { Text(stringResource(R.string.settings_ai_base_url)) }, placeholder = { Text("https://api.openai.com/v1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai_base_url"),
        )
        var aiApiKey by remember { mutableStateOf(lyricPrefs.getAiApiKey()) }
        OutlinedTextField(
            value = aiApiKey, onValueChange = { aiApiKey = it; lyricPrefs.setAiApiKey(it) },
            label = { Text(stringResource(R.string.settings_ai_key)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai_api_key"),
        )
        var aiModel by remember { mutableStateOf(lyricPrefs.getAiModel()) }
        OutlinedTextField(
            value = aiModel, onValueChange = { aiModel = it; lyricPrefs.setAiModel(it) },
            label = { Text(stringResource(R.string.settings_ai_model)) }, placeholder = { Text("gpt-3.5-turbo") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ai_model"),
        )
    }
}

// ---------------------------------------------------------------- Storage & permissions

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoragePage(snackbar: SnackbarHostState, browserViewModel: BrowserViewModel, permissionEpoch: Int, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserState by browserViewModel.uiState.collectAsState()
    val thumbs = remember { ThumbnailRepository.get(context) }
    SettingsPage {
        val report = remember(permissionEpoch, browserState.availableSources) { PermissionDiagnostics.report(context) }
        @Composable fun accessText(a: PermissionDiagnostics.Access) = stringResource(
            when (a) {
                PermissionDiagnostics.Access.GRANTED -> R.string.perm_granted
                PermissionDiagnostics.Access.PARTIAL -> R.string.perm_partial
                PermissionDiagnostics.Access.DENIED -> R.string.perm_denied
                PermissionDiagnostics.Access.NOT_APPLICABLE -> R.string.perm_na
            }
        )
        SettingsInfo(stringResource(R.string.perm_audio, accessText(report.audio)), Modifier.testTag("perm_audio"))
        SettingsInfo(stringResource(R.string.perm_images, accessText(report.images)), Modifier.testTag("perm_images"))
        SettingsInfo(stringResource(R.string.perm_notifications, accessText(report.notifications)))
        report.safFolders.forEach { (name, ok) ->
            SettingsInfo(stringResource(R.string.perm_saf, name, stringResource(if (ok) R.string.perm_granted else R.string.perm_revoked)))
        }
        if (report.images != PermissionDiagnostics.Access.GRANTED) SettingsHelp(stringResource(R.string.perm_images_help))
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.testTag("perm_request")) { Text(stringResource(R.string.perm_request)) }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(stringResource(R.string.perm_open_settings)) }
        }
        SettingsInfo(stringResource(R.string.perm_android_version, report.sdk), color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsGroupTitle(stringResource(R.string.settings_group_cache))
        OutlinedButton(
            onClick = {
                thumbs.clearCaches()
                coil.Coil.imageLoader(context).memoryCache?.clear()
                browserViewModel.onImageCacheCleared()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_cache_cleared)) }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("clear_image_cache"),
        ) { Text(stringResource(R.string.settings_clear_image_cache)) }
    }
}

// ---------------------------------------------------------------- About

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    var isDebug by remember { mutableStateOf(CrashHandler.isDebugEnabled(context)) }
    val uriHandler = LocalUriHandler.current
    var showLicenses by remember { mutableStateOf(false) }
    SettingsPage {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("about_version"),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_about_fork), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Copyright © 2026 Wyvern2000.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("All rights reserved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isDebug) Text(
                    stringResource(R.string.settings_debug_on),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Tapping the icon twice toggles the debug log (as before). Decorative for TalkBack.
            var tapCount by remember { mutableIntStateOf(0) }
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(96.dp)
                    .graphicsLayer(scaleX = 1.4f, scaleY = 1.4f)
                    .clickable {
                        tapCount++
                        if (tapCount >= 2) {
                            tapCount = 0
                            val newState = !isDebug
                            CrashHandler.setDebugEnabled(context, newState)
                            isDebug = newState
                        }
                    },
            )
        }
        SettingsDivider()
        LinkRow("https://github.com/wyvern3000/Folder-Player") { uriHandler.openUri(it) }
        LinkRow("https://github.com/yuu-biz/Folder-Player") { uriHandler.openUri(it) }
        TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 8.dp).testTag("open_licenses")) {
            Text(stringResource(R.string.settings_licenses))
        }
        if (showLicenses) LicensesDialog { showLicenses = false }
    }
}

/** A link: wraps when it is longer than the screen is wide, at least 48 dp high. */
@Composable
private fun LinkRow(url: String, open: (String) -> Unit) {
    Text(
        url,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { open(url) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Third-party notices bundled as an asset (generated by scripts/licenses/gen_notices.py). */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember {
        runCatching { context.assets.open("licenses/third_party_notices.txt").bufferedReader().readLines() }.getOrElse { listOf(it.message ?: "") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("licenses_text")) {
                items(lines.size) { i ->
                    Text(lines[i], style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
