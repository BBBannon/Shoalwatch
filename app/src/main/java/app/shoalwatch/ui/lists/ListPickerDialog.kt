package app.shoalwatch.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import kotlinx.coroutines.flow.Flow

/** What a screen is given for the add-to picker: the picker ViewModel's state and entry points. */
class ListPickerHost(
    val state: PickerUiState,
    val notices: Flow<ListNotice>,
    val name: TextFieldState,
    /** Opens the picker for these items. */
    val onOpen: (List<BulkItem>, ListKind) -> Unit,
    val onChoose: (ListChoice) -> Unit,
    val onStartCreate: () -> Unit,
    val onBackToList: () -> Unit,
    val onCreate: () -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

/** The picker's window and its result line, for a screen to compose once ([snackbar] shows the line). */
@Composable
fun ListPickerEffects(host: ListPickerHost, snackbar: SnackbarHostState) {
    ListNoticesEffect(host.notices, snackbar, host.state.session)
    ListPickerDialog(host)
}

/**
 * "Add to playlist" / "Add to collection": one secure dialog listing the existing playlists or collections, then "New playlist..."
 * or "New collection..." which asks for a name (1 to 200 visible characters). Each row is a 48dp button; offline and failed reads
 * say so with Try again; a refusal or failure while adding stays here as words.
 */
@Composable
fun ListPickerDialog(host: ListPickerHost) {
    val ui = host.state
    val kind = ui.kind ?: return
    if (LocalCovered.current) return // under the lock cover
    val playlist = kind == ListKind.PLAYLIST
    AlertDialog(
        onDismissRequest = { host.onDismiss() },
        properties = SecureDialog,
        title = {
            Text(
                stringResource(
                    when {
                        ui.creating && playlist -> R.string.lists_create_playlist_title
                        ui.creating -> R.string.lists_create_collection_title
                        playlist -> R.string.lists_picker_playlist_title
                        else -> R.string.lists_picker_collection_title
                    },
                ),
                Modifier.semantics { heading() },
            )
        },
        text = {
            val obscured = FilterObscuredTouches()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
                Text(pluralStringResource(R.plurals.lists_picker_adding, ui.itemCount, ui.itemCount), style = MaterialTheme.typography.bodyMedium)
                if (ui.creating) NameField(host.name, ui.error) else PickerBody(host, playlist)
                if (!ui.creating && ui.phase == ListsPhase.READY) ErrorMessage(ui.error.takeIf { it != 0 }?.let { stringResource(it) })
            }
        },
        dismissButton = {
            if (ui.creating) {
                TextButton(onClick = host.onBackToList, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cd_back)) }
            } else {
                TextButton(onClick = host.onDismiss, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        confirmButton = {
            if (ui.creating) {
                TextButton(onClick = host.onCreate, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lists_create)) }
            }
        },
    )
}

@Composable
private fun PickerBody(host: ListPickerHost, playlist: Boolean) {
    val ui = host.state
    when (ui.phase) {
        ListsPhase.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.lists_picker_loading))
        }
        ListsPhase.OFFLINE -> Retry(host, R.string.lists_offline_title)
        ListsPhase.GONE, ListsPhase.FAILED -> Retry(host, R.string.lists_load_failed_title)
        ListsPhase.READY -> {
            TextButton(onClick = host.onStartCreate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (playlist) R.string.lists_new_playlist else R.string.lists_new_collection), Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            }
            if (ui.choices.isEmpty()) {
                Text(stringResource(if (playlist) R.string.lists_picker_playlists_empty else R.string.lists_picker_collections_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                val untitled = stringResource(R.string.untitled)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(ui.choices, key = { it.id }) { choice ->
                        TextButton(onClick = { host.onChoose(choice) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(choice.name.ifBlank { untitled }, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Retry(host: ListPickerHost, title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = host.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
}
