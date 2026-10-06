package com.wing.folderplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wing.folderplayer.R

/**
 * The player in a pane beside the browser (wide windows). It is the same player content as the full player of the phone
 * ([MainPlayerScreen], same [PlayerViewModel], same controller, same cover requests), without the sheet around it: no mini
 * player, no folding, no drag between sizes. Its state is that of a sheet that is open for good, so the parts that move
 * with the sheet's fraction are simply at their end.
 *
 * Nothing here connects, queues, seeks or pauses: leaving the composition (the window got narrow) or entering it (it got
 * wide) only draws the same state differently.
 */
@Composable
fun DockedPlayerPane(viewModel: PlayerViewModel, hasTrack: Boolean, modifier: Modifier = Modifier) {
    val sheet = remember { PlayerSheetState.docked() }
    val transition = rememberPlayerTransition(sheet)
    val backgroundStyle by viewModel.backgroundStyle.collectAsState()
    val cover by viewModel.cover.collectAsState()
    Box(modifier.clipToBounds().testTag("player_pane")) {
        PanelBackground(transition, backgroundStyle, cover)
        if (hasTrack) {
            MainPlayerScreen(viewModel = viewModel, onCollapse = {}, transition = transition, docked = true)
        } else {
            NoTrackPlaceholder()
        }
    }
}

/** What the pane shows while nothing is queued (the phone shows no player then). */
@Composable
private fun NoTrackPlaceholder() {
    Box(Modifier.fillMaxSize().semantics(mergeDescendants = true) {}.testTag("player_pane_empty"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.4f))
            Text(
                stringResource(R.string.player_no_song),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
