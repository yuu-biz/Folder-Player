package com.wing.folderplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.BuildConfig
import com.wing.folderplayer.R
import com.wing.folderplayer.cast.CastSettings
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.prefs.LyricPreferences
import com.wing.folderplayer.data.prefs.NotchPreferences
import com.wing.folderplayer.data.prefs.OrientationPreferences
import com.wing.folderplayer.data.prefs.PlaybackPreferences
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
import com.wing.folderplayer.utils.PermissionDiagnostics
import kotlinx.coroutines.launch

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, tagPrefix: String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) },
                modifier = Modifier.testTag("${tagPrefix}_$value"))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by playerViewModel.uiState.collectAsState()
    val browserState by browserViewModel.uiState.collectAsState()
    val lyricPrefs = remember { LyricPreferences(context) }
    val playbackPrefs = remember { PlaybackPreferences(context) }
    val thumbs = remember { ThumbnailRepository.get(context) }
    val fonts = remember { FontManager.get(context) }
    val castSettings = remember { CastSettings(context) }
    val exportSettings = remember { ExportSettings(context) }
    var isDebug by remember { mutableStateOf(com.wing.folderplayer.utils.CrashHandler.isDebugEnabled(context)) }
    val snackbar = remember { SnackbarHostState() }

    val horizontalSafePadding = WindowInsets.displayCutout.asPaddingValues().let {
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        maxOf(it.calculateLeftPadding(layoutDirection), it.calculateRightPadding(layoutDirection))
    }

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

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = horizontalSafePadding)) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_close)) }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("settings_column")
                ) {
                    // ---------- Display ----------
                    SectionTitle(stringResource(R.string.settings_cover_size))
                    ChoiceRow(listOf("STANDARD" to stringResource(R.string.settings_cover_standard), "LARGE" to stringResource(R.string.settings_cover_large)),
                        playerState.coverDisplaySize, "cover") { playerViewModel.setCoverDisplaySize(it) }

                    SectionTitle(stringResource(R.string.settings_background))
                    ChoiceRow(listOf("GRADIENT" to stringResource(R.string.settings_background_solid), "BLUR" to stringResource(R.string.settings_background_blur),
                        "BLACK" to stringResource(R.string.settings_background_black)), playerState.backgroundStyle, "bg") { playerViewModel.setBackgroundStyle(it) }

                    SectionTitle(stringResource(R.string.settings_title_mode))
                    var titleMode by remember { mutableStateOf(playbackPrefs.getTitleMode()) }
                    ChoiceRow(listOf("FILENAME" to stringResource(R.string.settings_title_filename), "TAGS" to stringResource(R.string.settings_title_tags)),
                        titleMode, "title") { titleMode = it; playerViewModel.setTitleMode(TitleMode.valueOf(it)) }

                    SectionTitle(stringResource(R.string.settings_orientation))
                    val orientationPrefs = remember { OrientationPreferences(context) }
                    var currentOrientation by remember { mutableStateOf(orientationPrefs.getOrientation()) }
                    ChoiceRow(
                        listOf(
                            OrientationPreferences.ORIENTATION_SYSTEM to stringResource(R.string.settings_system),
                            OrientationPreferences.ORIENTATION_PORTRAIT to stringResource(R.string.settings_portrait),
                            OrientationPreferences.ORIENTATION_LANDSCAPE to stringResource(R.string.settings_landscape),
                        ), currentOrientation, "orientation"
                    ) { currentOrientation = it; orientationPrefs.setOrientation(it); onOrientationChange(it) }

                    SectionTitle(stringResource(R.string.settings_notch))
                    val notchPrefs = remember { NotchPreferences(context) }
                    var currentNotchMode by remember { mutableStateOf(notchPrefs.getNotchMode()) }
                    ChoiceRow(
                        listOf(NotchPreferences.NOTCH_FULLSCREEN to stringResource(R.string.settings_notch_fullscreen),
                            NotchPreferences.NOTCH_BLACK_BAR to stringResource(R.string.settings_notch_black_bar)),
                        currentNotchMode, "notch"
                    ) { currentNotchMode = it; notchPrefs.setNotchMode(it); onNotchModeChange(it) }

                    SectionTitle(stringResource(R.string.settings_grid_density))
                    ChoiceRow((2..5).map { it to it.toString() }, browserState.gridDensity, "grid") { browserViewModel.setGridDensity(it) }

                    // ---------- Language ----------
                    SectionTitle(stringResource(R.string.settings_language))
                    val systemLabel = stringResource(R.string.settings_system)
                    val langLabels = AppLocale.LANGUAGES.map { (tag, name) -> tag to name.ifEmpty { systemLabel } }
                    ChoiceRow(langLabels, AppLocale.get(context), "lang") { onLanguageChange(it) }

                    // ---------- Fonts ----------
                    SectionTitle(stringResource(R.string.settings_font))
                    val selectedFont by fonts.selected.collectAsState()
                    val fontStates by fonts.states.collectAsState()
                    val fallback by fonts.fallbackReason.collectAsState()
                    AppFont.values().forEach { f ->
                        val state = fontStates[f]
                        val label = when (f) {
                            AppFont.SYSTEM -> stringResource(R.string.settings_system)
                            AppFont.NOTO_SANS_SC -> "Noto Sans SC (思源黑体)"
                            AppFont.LXGW_WENKAI -> "LXGW WenKai (霞鹜文楷)"
                            AppFont.SARASA_UI_SC -> "Sarasa UI SC (更纱黑体)"
                            AppFont.IMPORTED -> stringResource(R.string.settings_font_imported_label, fonts.importedName.ifEmpty { "—" })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedFont == f,
                                enabled = state == FontState.Ready,
                                onClick = { fonts.select(f) },
                                modifier = Modifier.testTag("font_${f.id}")
                            )
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                val status = when (state) {
                                    FontState.Ready -> if (f == AppFont.SYSTEM) "" else stringResource(R.string.settings_font_ready)
                                    FontState.NotDownloaded -> if (f == AppFont.IMPORTED) "" else stringResource(R.string.settings_font_not_downloaded)
                                    is FontState.Downloading -> stringResource(R.string.settings_font_downloading, (state.progress * 100).toInt())
                                    is FontState.Failed -> stringResource(R.string.settings_font_failed, state.reason)
                                    null -> ""
                                }
                                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("font_status_${f.id}"))
                            }
                            if (f.url != null && state !is FontState.Downloading && state != FontState.Ready) {
                                TextButton(onClick = { scope.launch { if (fonts.download(f)) fonts.select(f) } }, modifier = Modifier.testTag("font_dl_${f.id}")) {
                                    Text(stringResource(R.string.settings_font_download))
                                }
                            }
                            if (f != AppFont.SYSTEM && state == FontState.Ready) {
                                TextButton(onClick = { fonts.delete(f) }) { Text(stringResource(R.string.common_delete)) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                        modifier = Modifier.testTag("font_import")) {
                        Text(stringResource(R.string.settings_font_import))
                    }
                    Text(stringResource(R.string.settings_font_license), style = MaterialTheme.typography.bodySmall)
                    fallback?.let { Text(stringResource(R.string.settings_font_fallback, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    // ---------- Library ----------
                    SectionTitle(stringResource(R.string.settings_default_sort))
                    val sourcePrefs = remember { com.wing.folderplayer.data.prefs.SourcePreferences(context) }
                    var defaultSort by remember { mutableStateOf(sourcePrefs.getDefaultSort()) }
                    ChoiceRow(listOf("NAME" to stringResource(R.string.sort_name), "DATE" to stringResource(R.string.sort_date), "SIZE" to stringResource(R.string.sort_size)),
                        defaultSort.field, "sort") { defaultSort = defaultSort.copy(field = it); sourcePrefs.saveDefaultSort(defaultSort.field, defaultSort.ascending) }
                    ChoiceRow(listOf(true to stringResource(R.string.settings_ascending), false to stringResource(R.string.settings_descending)),
                        defaultSort.ascending, "sortdir") { defaultSort = defaultSort.copy(ascending = it); sourcePrefs.saveDefaultSort(defaultSort.field, defaultSort.ascending) }
                    var defaultView by remember { mutableStateOf(sourcePrefs.getDefaultViewMode()) }
                    ChoiceRow(listOf("LIST" to stringResource(R.string.settings_view_list), "GRID" to stringResource(R.string.settings_view_grid)),
                        defaultView, "defview") { defaultView = it; sourcePrefs.saveDefaultViewMode(it) }

                    SectionTitle(stringResource(R.string.settings_thumbnails))
                    var thumbsRev by remember { mutableIntStateOf(0) }
                    key(thumbsRev) {
                        listOf(SourceType.LOCAL to stringResource(R.string.settings_thumbs_local), SourceType.SAF to stringResource(R.string.settings_thumbs_saf),
                            SourceType.WEBDAV to "WebDAV", SourceType.SMB to "SMB", SourceType.FTP to "FTP").forEach { (t, label) ->
                            SwitchRow(label, thumbs.settings.thumbnailsEnabled(t), "thumbs_${t.name}") { thumbs.settings.setThumbnailsEnabled(t, it); thumbs.invalidateNegatives(); thumbsRev++ }
                        }
                        SwitchRow(stringResource(R.string.settings_thumbs_wifi_only), thumbs.settings.wifiOnly, "thumbs_wifi") { thumbs.settings.wifiOnly = it; thumbsRev++ }
                    }
                    Text(stringResource(R.string.settings_thumbs_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        thumbs.clearCaches()
                        coil.Coil.imageLoader(context).memoryCache?.clear()
                        browserViewModel.onImageCacheCleared()
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_cache_cleared)) }
                    }, modifier = Modifier.testTag("clear_image_cache")) { Text(stringResource(R.string.settings_clear_image_cache)) }

                    // ---------- Storage & permissions ----------
                    SectionTitle(stringResource(R.string.settings_permissions))
                    val report = remember(permissionEpoch, browserState.availableSources) { PermissionDiagnostics.report(context) }
                    @Composable fun accessText(a: PermissionDiagnostics.Access) = stringResource(when (a) {
                        PermissionDiagnostics.Access.GRANTED -> R.string.perm_granted
                        PermissionDiagnostics.Access.PARTIAL -> R.string.perm_partial
                        PermissionDiagnostics.Access.DENIED -> R.string.perm_denied
                        PermissionDiagnostics.Access.NOT_APPLICABLE -> R.string.perm_na
                    })
                    Text(stringResource(R.string.perm_android_version, report.sdk), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.perm_audio, accessText(report.audio)), modifier = Modifier.testTag("perm_audio"))
                    Text(stringResource(R.string.perm_images, accessText(report.images)), modifier = Modifier.testTag("perm_images"))
                    Text(stringResource(R.string.perm_notifications, accessText(report.notifications)))
                    report.safFolders.forEach { (name, ok) ->
                        Text(stringResource(R.string.perm_saf, name, stringResource(if (ok) R.string.perm_granted else R.string.perm_revoked)))
                    }
                    if (report.images != PermissionDiagnostics.Access.GRANTED) {
                        Text(stringResource(R.string.perm_images_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.testTag("perm_request")) { Text(stringResource(R.string.perm_request)) }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text(stringResource(R.string.perm_open_settings)) }
                    }

                    // ---------- Lyrics ----------
                    SectionTitle(stringResource(R.string.settings_lyrics))
                    var lyricApiUrl by remember { mutableStateOf(lyricPrefs.getLyricApiUrl()) }
                    OutlinedTextField(value = lyricApiUrl, onValueChange = { lyricApiUrl = it; lyricPrefs.setLyricApiUrl(it) },
                        label = { Text(stringResource(R.string.settings_lyric_api)) }, modifier = Modifier.fillMaxWidth())
                    var aiAuto by remember { mutableStateOf(lyricPrefs.aiLyricsAuto) }
                    SwitchRow(stringResource(R.string.settings_ai_lyrics_auto), aiAuto, "ai_lyrics_auto") { aiAuto = it; lyricPrefs.aiLyricsAuto = it; playerViewModel.invalidateLyrics() }
                    var priority by remember { mutableStateOf(lyricPrefs.lyricsPriority) }
                    ChoiceRow(listOf("LOCAL_FIRST" to stringResource(R.string.settings_lyrics_local_first), "AI_FIRST" to stringResource(R.string.settings_lyrics_ai_first)),
                        priority, "lyrics_priority") { priority = it; lyricPrefs.lyricsPriority = it; playerViewModel.invalidateLyrics() }
                    var lyricLang by remember { mutableStateOf(lyricPrefs.aiLyricsLanguage) }
                    OutlinedTextField(value = lyricLang, onValueChange = { lyricLang = it; lyricPrefs.aiLyricsLanguage = it },
                        label = { Text(stringResource(R.string.settings_ai_lyrics_language)) }, modifier = Modifier.fillMaxWidth())
                    var translation by remember { mutableStateOf(lyricPrefs.translationTarget) }
                    OutlinedTextField(value = translation, onValueChange = { translation = it; lyricPrefs.translationTarget = it },
                        label = { Text(stringResource(R.string.settings_translation_target)) }, placeholder = { Text("zh-CN / en / ja …") }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.settings_ai_lyrics_help), style = MaterialTheme.typography.bodySmall)

                    SectionTitle(stringResource(R.string.settings_ai))
                    var aiBaseUrl by remember { mutableStateOf(lyricPrefs.getAiBaseUrl()) }
                    OutlinedTextField(value = aiBaseUrl, onValueChange = { aiBaseUrl = it; lyricPrefs.setAiBaseUrl(it) },
                        label = { Text(stringResource(R.string.settings_ai_base_url)) }, placeholder = { Text("https://api.openai.com/v1") }, modifier = Modifier.fillMaxWidth())
                    var aiApiKey by remember { mutableStateOf(lyricPrefs.getAiApiKey()) }
                    OutlinedTextField(value = aiApiKey, onValueChange = { aiApiKey = it; lyricPrefs.setAiApiKey(it) },
                        label = { Text(stringResource(R.string.settings_ai_key)) }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    var aiModel by remember { mutableStateOf(lyricPrefs.getAiModel()) }
                    OutlinedTextField(value = aiModel, onValueChange = { aiModel = it; lyricPrefs.setAiModel(it) },
                        label = { Text(stringResource(R.string.settings_ai_model)) }, placeholder = { Text("gpt-3.5-turbo") }, modifier = Modifier.fillMaxWidth())

                    // ---------- Playback extras ----------
                    SectionTitle(stringResource(R.string.settings_playback_extras))
                    var castOn by remember { mutableStateOf(castSettings.enabled) }
                    SwitchRow(stringResource(R.string.settings_dlna), castOn, "dlna_enabled") { castOn = it; castSettings.enabled = it }
                    var exportOn by remember { mutableStateOf(exportSettings.enabled) }
                    SwitchRow(stringResource(R.string.settings_auto_save), exportOn, "auto_save") { exportOn = it; exportSettings.enabled = it }
                    Text(stringResource(R.string.settings_auto_save_help, exportSettings.folderName), style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (NativeDecoder.isAvailable()) stringResource(R.string.settings_native_decoder_ok, NativeDecoder.version())
                        else stringResource(R.string.settings_native_decoder_missing),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native_decoder_status")
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // ---------- About ----------
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("about_version")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.settings_about_fork), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Copyright © 2026 Wyvern2000.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("All rights reserved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            if (isDebug) {
                                Text(
                                    text = stringResource(R.string.settings_debug_on),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "https://github.com/wyvern3000/Folder-Player",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                softWrap = false,
                                modifier = Modifier.clickable { uriHandler.openUri("https://github.com/wyvern3000/Folder-Player") }
                            )
                            Text(
                                text = "https://github.com/yuu-biz/Folder-Player",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                softWrap = false,
                                modifier = Modifier.clickable { uriHandler.openUri("https://github.com/yuu-biz/Folder-Player") }
                            )
                            var showLicenses by remember { mutableStateOf(false) }
                            TextButton(onClick = { showLicenses = true }, modifier = Modifier.testTag("open_licenses")) {
                                Text(stringResource(R.string.settings_licenses))
                            }
                            if (showLicenses) LicensesDialog { showLicenses = false }
                        }

                        // App Icon with Secret Trigger
                        var tapCount by remember { mutableStateOf(0) }
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier
                                .size(100.dp)
                                .align(Alignment.TopEnd)
                                .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                                .offset(x = (-1).dp, y = 1.dp)
                                .clickable {
                                    tapCount++
                                    if (tapCount >= 2) {
                                        tapCount = 0
                                        val newState = !isDebug
                                        com.wing.folderplayer.utils.CrashHandler.setDebugEnabled(context, newState)
                                        isDebug = newState
                                    }
                                }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
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
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("licenses_text")) {
                items(lines.size) { i ->
                    Text(lines[i], style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
