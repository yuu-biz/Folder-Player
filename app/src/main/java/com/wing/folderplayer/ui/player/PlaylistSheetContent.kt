package com.wing.folderplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.launch
import com.wing.folderplayer.R
import com.wing.folderplayer.data.playlist.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheetContent(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f) // Allow it to take up to 90% height
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    ) {
        // Drag Handle / Close Indicator for Landscape
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            )
        }

        Text(
            androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_playlist_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color.White,
            modifier = Modifier.padding(bottom = 0.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Playlist Selector Dropdown
            var expanded by remember { mutableStateOf(false) }
            var menuDialog by remember { mutableStateOf<PlaylistMenuDialog?>(null) }
            var playlistToRename by remember { mutableStateOf<String?>(null) }
            var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
            var renameName by remember { mutableStateOf("") }
            var showTimerDialog by remember { mutableStateOf(false) }

            playlistToDelete?.let { DeletePlaylistDialog(it, viewModel) { playlistToDelete = null } }
            menuDialog?.let { PlaylistMenuDialogHost(it, viewModel) { menuDialog = null } }

            if (showTimerDialog) {
                TimerDialog(
                    uiState = uiState,
                    viewModel = viewModel,
                    onDismiss = { showTimerDialog = false }
                )
            }

            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(top = 0.dp, bottom = 4.dp)
                ) {
                    Text(
                        uiState.activePlaylistName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color(0xFF2A2A2A))
                ) {
                    uiState.allPlaylists.forEach { playlist ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        playlist.name,
                                        modifier = Modifier.weight(1f),
                                        color = Color.White
                                    )
                                    if (playlist.id != "default") {
                                        IconButton(onClick = {
                                            playlistToRename = playlist.id
                                            renameName = playlist.name
                                            expanded = false
                                        }, modifier = Modifier.size(48.dp)) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_rename),
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(onClick = {
                                            playlistToDelete = playlist
                                            expanded = false
                                        }, modifier = Modifier.size(48.dp)) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.common_delete),
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                viewModel.switchPlaylist(playlist.id)
                                expanded = false
                            }
                        )
                    }
                    PlaylistMenuActions(uiState) { menuDialog = it; expanded = false }
                }
            }

            // Dialogs
            if (playlistToRename != null) {
                AlertDialog(
                    onDismissRequest = { playlistToRename = null },
                    title = { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_rename_playlist)) },
                    text = {
                        OutlinedTextField(
                            value = renameName,
                            onValueChange = { renameName = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_new_name)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (renameName.isNotBlank()) {
                                viewModel.renamePlaylist(
                                    playlistToRename!!,
                                    renameName
                                )
                                playlistToRename = null
                            }
                        }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_rename)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { playlistToRename = null }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.common_cancel)) }
                    }
                )
            }

            Row {
                PlaybackOptionsButton(uiState, viewModel)
                IconButton(onClick = { showTimerDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.AccessAlarm,
                        contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_sleep_timer),
                        tint = if (uiState.sleepTimerActive) MaterialTheme.colorScheme.primary else Color.White.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { viewModel.toggleAutoNextFolder() }) {
                    Icon(
                        imageVector = Icons.Default.AllInclusive,
                        contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_next_folder),
                        tint = if (uiState.autoNextFolder) MaterialTheme.colorScheme.primary else Color.White.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_shuffle),
                        tint = if (uiState.shuffleModeEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { viewModel.toggleRepeatMode() }) {
                    Icon(
                        imageVector = when (uiState.repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_repeat),
                        tint = if (uiState.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White.copy(
                            alpha = 0.5f
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        PlaylistTrackList(uiState, viewModel, onPlayed = onClose)
    }
}

/** Asks before a playlist is deleted (the playlist menu of the full player and of the playlist sheet). */
@Composable
fun DeletePlaylistDialog(playlist: Playlist, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title, playlist.name)) },
        confirmButton = {
            TextButton(onClick = { viewModel.deletePlaylist(playlist.id); onDismiss() }, modifier = Modifier.testTag("playlist_delete_confirm")) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        modifier = Modifier.testTag("playlist_delete_dialog"),
    )
}

/**
 * Sets or turns off the sleep timer: minutes (presets, or the slider in steps of 5) or songs, and the options. While
 * a timer runs it shows what is left and can turn it off; the values and options are not changed then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerDialog(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val active = uiState.sleepTimerActive
    var selectedMins by remember { mutableStateOf(if (uiState.sleepTimerType == TimerType.TIME) uiState.sleepTimerValue.toFloat() else 0f) }
    var selectedSongs by remember { mutableStateOf(if (uiState.sleepTimerType == TimerType.SONGS) uiState.sleepTimerValue.toFloat() else 0f) }
    var lastInteractedType by remember { mutableStateOf(uiState.sleepTimerType) }
    var finishSong by remember { mutableStateOf(uiState.sleepTimerFinishSong) }
    var fade by remember { mutableStateOf(uiState.sleepTimerFade) }
    val selectedValue = if (lastInteractedType == TimerType.TIME) selectedMins.toInt() else selectedSongs.toInt()
    fun pickMinutes(minutes: Float) {
        selectedMins = minutes
        if (minutes > 0) { selectedSongs = 0f; lastInteractedType = TimerType.TIME }
    }

    val displayLabel = if (active) timerLabel(uiState.sleepTimerType, uiState.sleepTimerValue) else timerLabel(lastInteractedType, selectedValue)
    // Tick marks of 24 / 50 steps would only be noise on a slider this short.
    val sliderColors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.player_timer_stop_after),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                    )
                    Text(
                        displayLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("timer_label"),
                    )
                }
                if (active) {
                    TextButton(onClick = { viewModel.resetSleepTimer(); onDismiss() }, modifier = Modifier.testTag("timer_off")) {
                        Text(stringResource(R.string.player_timer_off), color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (preset in TIMER_PRESET_MINUTES) {
                        FilterChip(
                            selected = !active && lastInteractedType == TimerType.TIME && selectedMins.toInt() == preset,
                            enabled = !active,
                            onClick = { pickMinutes(preset.toFloat()) },
                            label = { Text(timerLabel(TimerType.TIME, preset)) },
                            modifier = Modifier.testTag("timer_preset_$preset"),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = selectedMins,
                        enabled = !active,
                        onValueChange = { pickMinutes(it) },
                        valueRange = 0f..TIMER_MAX_MINUTES.toFloat(),
                        steps = TIMER_MAX_MINUTES / 5 - 1,
                        colors = sliderColors,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.player_timer_mins), modifier = Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = selectedSongs,
                        enabled = !active,
                        onValueChange = {
                            selectedSongs = it
                            if (it > 0) { selectedMins = 0f; lastInteractedType = TimerType.SONGS }
                        },
                        valueRange = 0f..TIMER_MAX_SONGS.toFloat(),
                        steps = TIMER_MAX_SONGS - 1,
                        colors = sliderColors,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.player_timer_songs), modifier = Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }

                TimerOption(stringResource(R.string.player_timer_finish_song), finishSong, enabled = !active, tag = "timer_finish_song") { finishSong = it }
                TimerOption(stringResource(R.string.player_timer_fade), fade, enabled = !active, tag = "timer_fade") { fade = it }
            }
        },
        confirmButton = {
            if (active) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            } else {
                TextButton(
                    onClick = { viewModel.startSleepTimer(lastInteractedType, selectedValue, finishSong, fade); onDismiss() },
                    enabled = selectedValue > 0,
                    modifier = Modifier.testTag("timer_start"),
                ) { Text(stringResource(R.string.player_timer_start)) }
            }
        },
        dismissButton = {
            if (!active) TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun TimerOption(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp).testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val TIMER_PRESET_MINUTES = listOf(15, 30, 45, 60, 90)
private const val TIMER_MAX_MINUTES = 120
private const val TIMER_MAX_SONGS = 50

/** What is left of a running sleep timer, for the full player; a tap opens [TimerDialog]. Nothing while none runs. */
@Composable
fun SleepTimerChip(uiState: PlayerUiState, viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    if (!uiState.sleepTimerActive) return
    var show by remember { mutableStateOf(false) }
    if (show) TimerDialog(uiState, viewModel, onDismiss = { show = false })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable { show = true }
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("player_timer_chip"),
    ) {
        Icon(
            Icons.Default.AccessAlarm,
            contentDescription = stringResource(R.string.player_sleep_timer),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            timerLabel(uiState.sleepTimerType, uiState.sleepTimerValue),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** The button beside the sleep timer that opens [PlaybackOptionsDialog]; lit while the speed is not 1× or left / right are swapped. */
@Composable
fun PlaybackOptionsButton(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    var show by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val equalizer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {}
    // Many phones have no panel; it is then left out (the manifest lets this app see it).
    @Suppress("DEPRECATION")
    val hasEqualizer = remember { context.packageManager.resolveActivity(android.content.Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL), 0) != null }
    if (show) {
        PlaybackOptionsDialog(
            speed = uiState.playbackSpeed,
            swapChannels = uiState.swapChannels,
            onSpeed = viewModel::setPlaybackSpeed,
            onSwapChannels = viewModel::setSwapChannels,
            onEqualizer = if (!hasEqualizer) null else ({
                scope.launch {
                    val session = viewModel.audioSessionId() ?: return@launch
                    val panel = android.content.Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    try {
                        equalizer.launch(panel)
                    } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, R.string.player_equalizer_none, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }),
            onDismiss = { show = false },
        )
    }
    val changed = uiState.playbackSpeed != 1f || uiState.swapChannels
    IconButton(onClick = { show = true }, modifier = Modifier.testTag("player_options")) {
        Icon(
            imageVector = Icons.Default.Speed,
            contentDescription = androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_options),
            tint = if (changed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Playback speed (0.5× – 2×, the pitch kept) and left / right swapped, both applied at once and kept; and a way to the phone's equaliser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackOptionsDialog(
    speed: Float,
    swapChannels: Boolean,
    onSpeed: (Float) -> Unit,
    onSwapChannels: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Opens the phone's equaliser panel; null when the phone has none. */
    onEqualizer: (() -> Unit)? = null,
) {
    // The slider moves in steps of 0.05; the player follows when the finger lifts (each change rebuilds its audio).
    var shown by remember(speed) { mutableStateOf(speed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_options)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_speed, speedLabel(shown)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("player_speed_value"),
                )
                Slider(
                    value = shown,
                    onValueChange = { shown = (Math.round(it * 20) / 20f) },
                    onValueChangeFinished = { onSpeed(shown) },
                    valueRange = PlayerViewModel.MIN_SPEED..PlayerViewModel.MAX_SPEED,
                    steps = ((PlayerViewModel.MAX_SPEED - PlayerViewModel.MIN_SPEED) * 20).toInt() - 1,
                    modifier = Modifier.testTag("player_speed_slider"),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (preset in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
                        FilterChip(
                            selected = shown == preset,
                            onClick = { shown = preset; onSpeed(preset) },
                            label = { Text(speedLabel(preset)) },
                            modifier = Modifier.testTag("player_speed_${speedLabel(preset)}"),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onSwapChannels(!swapChannels) }.testTag("player_swap_channels"),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_swap_channels), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_swap_channels_hint),
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = swapChannels, onCheckedChange = null)
                }
                if (onEqualizer != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onEqualizer() }.testTag("player_equalizer"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.player_equalizer), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.player_equalizer_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        }
                        Icon(Icons.Default.GraphicEq, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.common_ok)) }
        },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

/** "1.25×": two decimals at most, no trailing zeros. */
fun speedLabel(speed: Float): String =
    java.math.BigDecimal(speed.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let {
        if ('.' in it) "$it×" else "$it.0×"
    }

/** A dialog opened from the playlist menu; it is shown after the menu has closed. */
enum class PlaylistMenuDialog { NEW_LIST, SAVE_QUEUE, CLEAR_QUEUE }

/** The end of the playlist menu (full player and playlist sheet): a new list, and the queue saved as a list or cleared. */
@Composable
fun PlaylistMenuActions(uiState: PlayerUiState, onPick: (PlaylistMenuDialog) -> Unit) {
    // At the cap both ways to a new list stay in the menu, greyed, with the reason below them.
    val canCreate = uiState.allPlaylists.count { it.id != "default" } < com.wing.folderplayer.data.playlist.PlaylistStore.MAX_PLAYLISTS
    val off = Color.White.copy(alpha = 0.4f)
    Divider(color = Color.White.copy(alpha = 0.1f))
    DropdownMenuItem(
        text = { Text(stringResource(R.string.player_new_list), color = if (canCreate) MaterialTheme.colorScheme.primary else off, style = MaterialTheme.typography.labelLarge) },
        onClick = { onPick(PlaylistMenuDialog.NEW_LIST) },
        enabled = canCreate,
    )
    if (uiState.playlist.isNotEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.queue_save_as_playlist), color = if (canCreate) Color.White else off) },
            onClick = { onPick(PlaylistMenuDialog.SAVE_QUEUE) },
            enabled = canCreate,
            modifier = Modifier.testTag("queue_save_as_playlist"),
        )
    }
    if (!canCreate) {
        Text(
            stringResource(R.string.playlist_cap_reached, com.wing.folderplayer.data.playlist.PlaylistStore.MAX_PLAYLISTS),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 4.dp).testTag("playlist_cap_reached"),
        )
    }
    if (uiState.playlist.isEmpty()) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.queue_clear), color = Color.White.copy(alpha = if (uiState.playlist.size > 1) 1f else 0.4f)) },
        onClick = { onPick(PlaylistMenuDialog.CLEAR_QUEUE) },
        enabled = uiState.playlist.size > 1,
        modifier = Modifier.testTag("queue_clear"),
    )
}

@Composable
fun PlaylistMenuDialogHost(dialog: PlaylistMenuDialog, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    when (dialog) {
        PlaylistMenuDialog.NEW_LIST -> PlaylistNameDialog(stringResource(R.string.player_create_playlist), stringResource(R.string.player_create), onDismiss) {
            viewModel.createPlaylist(it)
        }
        PlaylistMenuDialog.SAVE_QUEUE -> PlaylistNameDialog(stringResource(R.string.queue_save_as_playlist), stringResource(R.string.common_save), onDismiss) {
            val text = if (viewModel.saveQueueAsPlaylist(it)) context.getString(R.string.queue_saved_as_playlist, it)
                else context.getString(R.string.playlist_cap_reached, com.wing.folderplayer.data.playlist.PlaylistStore.MAX_PLAYLISTS)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
        PlaylistMenuDialog.CLEAR_QUEUE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.queue_clear_title)) },
            text = { Text(stringResource(R.string.queue_clear_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearQueue(); onDismiss() }, modifier = Modifier.testTag("queue_clear_confirm")) {
                    Text(stringResource(R.string.queue_clear))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Asks for the name of a new playlist; [onName] gets it (not blank) and the dialog closes. */
@Composable
private fun PlaylistNameDialog(title: String, confirm: String, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.player_playlist_name)) },
                singleLine = true,
                modifier = Modifier.testTag("playlist_name_field"),
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) { onName(name); onDismiss() } }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
