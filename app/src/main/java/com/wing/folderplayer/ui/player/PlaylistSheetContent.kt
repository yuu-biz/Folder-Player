package com.wing.folderplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

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
            var showCreateDialog by remember { mutableStateOf(false) }
            var playlistToRename by remember { mutableStateOf<String?>(null) }
            var renameName by remember { mutableStateOf("") }
            var createName by remember { mutableStateOf("") }
            var showTimerDialog by remember { mutableStateOf(false) }

            if (showTimerDialog) {
                TimerDialog(
                    uiState = uiState,
                    onStart = { type, value -> viewModel.startSleepTimer(type, value) },
                    onReset = { viewModel.resetSleepTimer() },
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
                        uiState.activePlaylistName.uppercase(),
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
                                            viewModel.deletePlaylist(playlist.id)
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
                    if (uiState.allPlaylists.size < 11) {
                        Divider(color = Color.White.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "+ NEW LIST",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            onClick = {
                                showCreateDialog = true
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Dialogs
            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_create_playlist)) },
                    text = {
                        OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_playlist_name)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (createName.isNotBlank()) {
                                viewModel.createPlaylist(createName)
                                createName = ""
                                showCreateDialog = false
                            }
                        }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_create)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.common_cancel)) }
                    }
                )
            }

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

@Composable
fun TimerDialog(
    uiState: PlayerUiState,
    onStart: (TimerType, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMins by remember { mutableStateOf(if (uiState.sleepTimerType == TimerType.TIME) uiState.sleepTimerValue.toFloat() else 0f) }
    var selectedSongs by remember { mutableStateOf(if (uiState.sleepTimerType == TimerType.SONGS) uiState.sleepTimerValue.toFloat() else 0f) }
    var lastInteractedType by remember { mutableStateOf(uiState.sleepTimerType) }

    // If active, show current countdown from uiState. If not, show what sliders represent.
    val displayLabel = if (uiState.sleepTimerActive) timerLabel(uiState.sleepTimerType, uiState.sleepTimerValue) else {
        timerLabel(lastInteractedType, if (lastInteractedType == TimerType.TIME) selectedMins.toInt() else selectedSongs.toInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val statusColor = if (uiState.sleepTimerActive) MaterialTheme.colorScheme.primary else Color.White
                    Text(
                        "Stop after ",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (uiState.sleepTimerActive) statusColor else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        displayLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = selectedMins,
                        enabled = !uiState.sleepTimerActive,
                        onValueChange = { 
                            selectedMins = it
                            if (it > 0) {
                                selectedSongs = 0f
                                lastInteractedType = TimerType.TIME 
                            }
                        },
                        valueRange = 0f..99f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_timer_mins), modifier = Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = selectedSongs,
                        enabled = !uiState.sleepTimerActive,
                        onValueChange = { 
                            selectedSongs = it
                            if (it > 0) {
                                selectedMins = 0f
                                lastInteractedType = TimerType.SONGS 
                            }
                        },
                        valueRange = 0f..50f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_timer_songs), modifier = Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                if (uiState.sleepTimerActive) {
                    onDismiss()
                } else {
                    val value = if (lastInteractedType == TimerType.TIME) selectedMins.toInt() else selectedSongs.toInt()
                    onStart(lastInteractedType, value)
                    onDismiss()
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { 
                onReset()
                selectedMins = 0f
                selectedSongs = 0f
                onDismiss()
            }) { Text(androidx.compose.ui.res.stringResource(com.wing.folderplayer.R.string.player_timer_reset)) }
        },
        containerColor = Color(0xFF1E1E1E),
        textContentColor = Color.White
    )
}
