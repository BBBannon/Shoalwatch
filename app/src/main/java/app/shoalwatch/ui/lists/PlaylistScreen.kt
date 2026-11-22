package app.shoalwatch.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.lists.PlaylistEntry
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.CardShape
import app.shoalwatch.ui.components.CautionButton
import app.shoalwatch.ui.components.ConfirmDialog
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.sheetTitle
import app.shoalwatch.ui.present.wideArtwork
import kotlinx.coroutines.flow.Flow

/** What one playlist's screen can ask for. The route wires these to the ViewModel and to navigation. */
class PlaylistActions(
    val onBack: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onRetry: () -> Unit,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onDownloadAll: () -> Unit,
    val onMove: (entryId: String, delta: Int) -> Unit,
    val onRemove: (entryId: String) -> Unit,
    val onAskRename: () -> Unit,
    val onRename: () -> Unit,
    val onCancelRename: () -> Unit,
    val onAskDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * One playlist (live): its name as the heading, Play, Shuffle and Download all, then each entry with Move up, Move down and
 * Remove (48dp buttons that name the entry; no dragging), then Rename and Delete (Delete only when the server says this profile
 * may, and it asks first, naming the playlist). While a change runs the buttons wait.
 */
@Composable
fun PlaylistScreen(
    state: PlaylistUiState,
    actions: PlaylistActions,
    name: TextFieldState,
    notices: Flow<ListNotice>,
    snackbar: SnackbarHostState,
) {
    ListNoticesEffect(notices, snackbar, state.session)
    val strings = rememberStrings()
    val playlist = state.playlist
    val session = state.session
    ListsFrame(playlist?.item?.displayName(strings).orEmpty(), actions.onBack, snackbar) {
        when {
            session == null -> LazyColumn(Modifier.fillMaxSize()) { item { SignedOutNotice(actions.onOpenServers) } }
            state.phase == ListsPhase.LOADING -> Unit
            state.phase != ListsPhase.READY || playlist == null -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(state.phase, actions.onRetry) } }
            else -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "summary") {
                    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val count = playlist.entries.size
                        Text(pluralStringResource(R.plurals.lists_item_count, count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (count > 0) Buttons(state, actions)
                    }
                }
                if (playlist.entries.isEmpty()) {
                    item(key = "empty") { EmptyState(stringResource(R.string.lists_playlist_empty_title), stringResource(R.string.lists_playlist_empty_body)) }
                }
                itemsIndexed(playlist.entries, key = { _, e -> e.entryId }) { index, entry ->
                    EntryRow(entry, index, playlist.entries.size, session, strings, state.busy, actions)
                }
                item(key = "manage") {
                    Column(Modifier.padding(horizontal = Gutter)) {
                        SecondaryButton(stringResource(R.string.lists_rename), actions.onAskRename, enabled = !state.busy)
                        if (playlist.canDelete) CautionButton(stringResource(R.string.lists_delete), actions.onAskDelete)
                    }
                }
            }
        }
    }
    if (state.renaming) {
        NameDialog(
            stringResource(R.string.lists_rename_title), stringResource(R.string.lists_save), name, state.nameError, state.busy,
            onConfirm = actions.onRename, onDismiss = actions.onCancelRename,
        )
    }
    if (state.deleteAsk && playlist != null) {
        val shown = playlist.item.displayName(strings)
        ConfirmDialog(
            stringResource(R.string.lists_delete_title, shown), stringResource(R.string.lists_delete_confirm),
            onConfirm = actions.onConfirmDelete, onDismiss = actions.onCancelDelete,
            busyLabel = if (state.busy) stringResource(R.string.lists_delete_confirm) else null,
        ) { Text(stringResource(R.string.lists_delete_body), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Buttons(state: PlaylistUiState, actions: PlaylistActions) {
    Button(onClick = actions.onPlay, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.lists_play), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
    FilledTonalButton(onClick = actions.onShuffle, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.lists_shuffle), style = MaterialTheme.typography.titleMedium)
    }
    SecondaryButton(stringResource(R.string.lists_download_all), actions.onDownloadAll, enabled = !state.busy)
}

/** One entry: its picture and title (opens it), then Move up, Move down and Remove under them, each naming the entry. */
@Composable
private fun EntryRow(entry: PlaylistEntry, index: Int, size: Int, session: Session, strings: Strings, busy: Boolean, actions: PlaylistActions) {
    val item = entry.item.item
    val title = item.sheetTitle(strings)
    Surface(Modifier.padding(horizontal = Gutter).fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(8.dp)) {
            Surface(onClick = { actions.onOpen(entry.item.destination()) }, shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(wideArtwork(item, session), null, aspect = ArtworkAspect.WIDE, title = title, modifier = Modifier.width(96.dp))
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { actions.onMove(entry.entryId, -1) }, enabled = !busy && index > 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.lists_move_up, title))
                }
                IconButton(onClick = { actions.onMove(entry.entryId, 1) }, enabled = !busy && index < size - 1, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.lists_move_down, title))
                }
                IconButton(onClick = { actions.onRemove(entry.entryId) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lists_remove_entry, title))
                }
            }
        }
    }
}
