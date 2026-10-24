package com.wing.folderplayer.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import com.wing.folderplayer.data.source.FileOperations
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.ui.player.PlaybackErrorText

/** A change to one file that the user asked for from the long-press sheet or the viewer; its dialog confirms it. */
sealed interface FileOp {
    val file: MusicFile

    data class Rename(override val file: MusicFile) : FileOp
    data class Move(override val file: MusicFile) : FileOp
    data class Delete(override val file: MusicFile) : FileOp
}

/** What the dialogs of [FileOpDialog] call; [listFolders] lists the folders inside a folder for "Move to". */
class FileOpActions(
    val listFolders: suspend (SourceRef) -> List<MusicFile>,
    val onRename: (MusicFile, String) -> Unit,
    val onMove: (MusicFile, String) -> Unit,
    val onDelete: (MusicFile) -> Unit,
    val onDismiss: () -> Unit,
)

/** The dialog of [op]. [busy]: the change is running (the buttons wait); [sourceName] names where a deleted file goes from. */
@Composable
fun FileOpDialog(op: FileOp, busy: Boolean, sourceName: String, actions: FileOpActions) {
    when (op) {
        is FileOp.Rename -> RenameFileDialog(op.file, busy, actions)
        is FileOp.Move -> MoveFileDialog(op.file, busy, sourceName, actions)
        is FileOp.Delete -> AlertDialog(
            onDismissRequest = actions.onDismiss,
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = { Text(stringResource(R.string.file_delete_text, op.file.name, sourceName)) },
            confirmButton = {
                TextButton(onClick = { actions.onDelete(op.file) }, enabled = !busy, modifier = Modifier.testTag("file_op_confirm")) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.common_cancel)) } },
            modifier = Modifier.testTag("file_delete_dialog"),
        )
    }
}

/** A one-line name dialog: rename of a file (the part before the extension selected) or of a bookmark. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    busy: Boolean = false,
    selectBaseName: Boolean = false,
    /** null: the name can be used; else why not (shown below the field). */
    check: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val end = if (selectBaseName) SourcePath.baseName(initial).length else initial.length
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val problem = check(value.text)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("name_field"),
            )
            // Here, not beside the dialog: the field must be placed in the dialog's window before it can take the focus.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = problem == null && value.text.isNotBlank() && value.text.trim() != initial && !busy,
                modifier = Modifier.testTag("name_confirm")) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RenameFileDialog(file: MusicFile, busy: Boolean, actions: FileOpActions) {
    val invalid = stringResource(R.string.file_name_invalid)
    NameDialog(
        title = stringResource(R.string.common_rename),
        initial = file.name,
        confirm = stringResource(R.string.common_rename),
        busy = busy,
        selectBaseName = true,
        check = { if (FileOperations.validName(it) == null) invalid else null },
        onConfirm = { actions.onRename(file, it) },
        onDismiss = actions.onDismiss,
    )
}

/** Picks a folder of the file's source, starting at the file's own folder. */
@Composable
private fun MoveFileDialog(file: MusicFile, busy: Boolean, sourceName: String, actions: FileOpActions) {
    val home = SourcePath.parent(file.path) ?: SourcePath.ROOT
    var folder by remember(file) { mutableStateOf(home) }
    val folders by produceState<Result<List<MusicFile>>?>(null, folder) {
        value = null
        value = runCatching { actions.listFolders(SourceRef(file.sourceId, folder)) }
    }
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.file_move_title, file.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("$sourceName · $folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp).testTag("file_move_path"))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    SourcePath.parent(folder)?.let { up ->
                        item(key = "up") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.browser_up)) },
                                leadingContent = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                                modifier = Modifier.clickable { folder = up }.testTag("file_move_up"),
                            )
                        }
                    }
                    val result = folders
                    when {
                        result == null -> item(key = "loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
                        result.isFailure -> item(key = "error") {
                            Text(PlaybackErrorText.describe(result.exceptionOrNull()!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        }
                        else -> items(result.getOrThrow(), key = { it.path }) { f ->
                            ListItem(
                                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                modifier = Modifier.clickable { folder = f.path }.testTag("file_move_folder_${f.name}"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onMove(file, folder) }, enabled = folder != home && !busy, modifier = Modifier.testTag("file_op_confirm")) {
                Text(stringResource(R.string.file_move_here))
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        modifier = Modifier.testTag("file_move_dialog"),
    )
}
