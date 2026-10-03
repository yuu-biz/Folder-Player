package com.wing.folderplayer.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R
import com.wing.folderplayer.data.source.ConnectionOutcome
import com.wing.folderplayer.data.source.ConnectionTestResult
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceType
import kotlinx.coroutines.launch

@Composable
fun connectionOutcomeText(r: ConnectionTestResult): String = when (r.outcome) {
    ConnectionOutcome.OK -> pluralStringResource(R.plurals.source_test_ok, r.entries, r.entries)
    ConnectionOutcome.AUTH_FAILED -> stringResource(R.string.source_test_auth_failed)
    ConnectionOutcome.SHARE_MISSING -> stringResource(R.string.source_test_share_missing)
    ConnectionOutcome.SHARE_NOT_FOUND -> stringResource(R.string.source_test_share_not_found)
    ConnectionOutcome.ROOT_NOT_FOUND -> stringResource(R.string.source_test_root_not_found)
    ConnectionOutcome.PERMISSION_DENIED -> stringResource(R.string.source_test_permission_denied)
    ConnectionOutcome.UNREACHABLE -> stringResource(R.string.source_test_unreachable)
    ConnectionOutcome.TLS_ERROR -> stringResource(R.string.source_test_tls_error)
    ConnectionOutcome.INVALID_CONFIG -> stringResource(R.string.source_test_invalid, r.detail)
    ConnectionOutcome.ERROR -> stringResource(R.string.source_test_error, r.detail)
}

/**
 * Add / edit a WebDAV, SMB or FTP(S) source. The password field is empty when editing; leaving it empty keeps the
 * stored password (it is never shown again).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditorDialog(
    initial: SourceConfig?,
    initialType: SourceType,
    onDismiss: () -> Unit,
    onSave: (SourceConfig, String?) -> Unit,
    onTest: suspend (SourceConfig, String?) -> ConnectionTestResult,
) {
    val editing = initial != null
    var type by remember { mutableStateOf(initial?.type ?: initialType) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var share by remember { mutableStateOf(initial?.share ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var passTouched by remember { mutableStateOf(false) }
    var passVisible by remember { mutableStateOf(false) }
    var anonymous by remember { mutableStateOf(initial?.anonymous ?: false) }
    var useTls by remember { mutableStateOf(initial?.useTls ?: false) }
    var pin by remember { mutableStateOf(initial?.tlsPinnedSha256 ?: "") }
    var syncPath by remember { mutableStateOf(initial?.syncPath ?: "") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val scope = rememberCoroutineScope()

    fun build(): SourceConfig {
        val base = initial ?: SourceConfig(type = type)
        return base.copy(
            name = name.ifBlank {
                when (type) {
                    SourceType.WEBDAV -> url.substringAfter("://").substringBefore('/')
                    else -> host
                }.ifBlank { type.name }
            },
            type = type,
            url = if (type == SourceType.WEBDAV) url.trim() else base.url,
            host = host.trim(),
            port = port.trim().toIntOrNull() ?: 0,
            share = share.trim().trim('/', '\\'),
            path = path.trim().ifBlank { null },
            domain = domain.trim(),
            username = user.trim(),
            anonymous = anonymous,
            useTls = type == SourceType.FTP && useTls,
            tlsPinnedSha256 = if (type == SourceType.FTP && useTls) pin.trim() else "",
            syncPath = syncPath.trim(),
        )
    }

    fun password(): String? = if (passTouched || !editing) pass else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.source_edit_title else R.string.source_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SourceType.WEBDAV to "WebDAV", SourceType.SMB to "SMB", SourceType.FTP to "FTP").forEach { (t, label) ->
                            FilterChip(selected = type == t, onClick = { type = t; testResult = null }, label = { Text(label) },
                                modifier = Modifier.testTag("type_$label"))
                        }
                    }
                }
                SourceField(name, { name = it }, stringResource(R.string.source_name), "field_name")
                when (type) {
                    SourceType.WEBDAV -> {
                        SourceField(url, { url = it }, stringResource(R.string.source_server_url), "field_url",
                            placeholder = "http://192.168.x.x:5244/dav", keyboard = KeyboardType.Uri)
                        SourceField(path, { path = it }, stringResource(R.string.source_root_path), "field_path", placeholder = "/Music")
                    }
                    SourceType.SMB -> {
                        SourceField(host, { host = it }, stringResource(R.string.source_host), "field_host", keyboard = KeyboardType.Uri)
                        SourceField(port, { port = it }, stringResource(R.string.source_port_default, 445), "field_port", keyboard = KeyboardType.Number)
                        SourceField(share, { share = it }, stringResource(R.string.source_share), "field_share")
                        SourceField(path, { path = it }, stringResource(R.string.source_root_path), "field_path", placeholder = "/Music")
                        SourceField(domain, { domain = it }, stringResource(R.string.source_domain), "field_domain")
                    }
                    SourceType.FTP -> {
                        SourceField(host, { host = it }, stringResource(R.string.source_host), "field_host", keyboard = KeyboardType.Uri)
                        SourceField(port, { port = it }, stringResource(R.string.source_port_default, 21), "field_port", keyboard = KeyboardType.Number)
                        SourceField(path, { path = it }, stringResource(R.string.source_root_path), "field_path", placeholder = "/Music")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useTls, onCheckedChange = { useTls = it }, modifier = Modifier.testTag("field_tls"))
                            Text(stringResource(R.string.source_ftps))
                        }
                        if (useTls) {
                            SourceField(pin, { pin = it }, stringResource(R.string.source_tls_pin), "field_pin")
                            Text(stringResource(R.string.source_tls_pin_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> Unit
                }
                if (type != SourceType.WEBDAV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = anonymous, onCheckedChange = { anonymous = it }, modifier = Modifier.testTag("field_anonymous"))
                        Text(stringResource(if (type == SourceType.SMB) R.string.source_guest else R.string.source_anonymous))
                    }
                }
                SourceField(user, { user = it }, stringResource(R.string.source_username), "field_user")
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; passTouched = true },
                    label = { Text(stringResource(R.string.source_password)) },
                    placeholder = { if (editing) Text(stringResource(R.string.source_password_unchanged)) },
                    modifier = Modifier.fillMaxWidth().testTag("field_password"),
                    singleLine = true,
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }) {
                            Icon(if (passVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null)
                        }
                    }
                )
                SourceField(syncPath, { syncPath = it }, stringResource(R.string.source_sync_path), "field_sync", placeholder = "/fav.json")

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = onTest(build(), password())
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.testTag("btn_test")
                    ) { Text(stringResource(R.string.source_test)) }
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                testResult?.let { r ->
                    Text(
                        connectionOutcomeText(r),
                        color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("test_result")
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(build(), password()) }, modifier = Modifier.testTag("btn_save")) {
                Text(stringResource(if (editing) R.string.common_save else R.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun SourceField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next)
    )
}
