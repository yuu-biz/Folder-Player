package com.wing.folderplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Building blocks of the Settings pages. Every row grows with the text (no fixed heights or widths), reads as one
 * item to TalkBack (name, then value / state), and is at least 48 dp high.
 */

/** Heading of a group of settings inside a page. */
@Composable
internal fun SettingsGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Explanation under a setting. */
@Composable
internal fun SettingsHelp(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A row that opens a page: name, its current value or a short description, and a chevron. */
@Composable
internal fun SettingsNavRow(
    title: String,
    value: String?,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = value?.takeIf { it.isNotEmpty() }?.let { v -> { Text(v, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = modifier
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag(tag),
    )
}

/** A setting with a few options shown as chips (2 to 5 of them), under its name. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(options, selected, tagPrefix, Modifier.padding(top = 4.dp), onSelect)
    }
}

/** The chips alone (several rows of options under one name, e.g. sort field and direction). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, tagPrefix: String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("${tagPrefix}_$value"),
            )
        }
    }
}

/** A setting with one switch: the whole row toggles. */
@Composable
internal fun SettingsSwitch(label: String, checked: Boolean, tag: String, modifier: Modifier = Modifier, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One option of a single-choice list (language, font): the whole row selects. */
@Composable
internal fun SettingsRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    status: String? = null,
    statusTag: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!status.isNullOrEmpty()) Text(
                status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (statusTag != null) Modifier.testTag(statusTag) else Modifier,
            )
        }
        trailing?.invoke()
    }
}

/** Plain information line (not a control). */
@Composable
internal fun SettingsInfo(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
internal fun SettingsDivider() {
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp))
}
