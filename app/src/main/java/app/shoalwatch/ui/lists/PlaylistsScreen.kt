package app.shoalwatch.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.rememberStrings
import kotlinx.coroutines.flow.Flow

/** What the Playlists screen can ask for. The route wires these to the ViewModel and to navigation. */
class PlaylistsActions(
    val onBack: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onRetry: () -> Unit,
    val onNew: () -> Unit,
    val onCreate: () -> Unit,
    val onCancelCreate: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * The frame the playlist screens share: a Back arrow and a heading, then [content], on the theme's surface with the snackbar at
 * the bottom. Content is capped at a readable width.
 */
@Composable
internal fun ListsFrame(title: String, onBack: () -> Unit, snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                    Text(
                        title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                ReadableBox { content() }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** The words for a read that did not arrive: offline, gone, or failed, with Try again (not for gone). */
@Composable
internal fun ReadProblem(phase: ListsPhase, onRetry: () -> Unit) {
    when (phase) {
        ListsPhase.OFFLINE -> EmptyState(stringResource(R.string.lists_offline_title), stringResource(R.string.lists_offline_body), actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
        ListsPhase.GONE -> EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body))
        else -> EmptyState(stringResource(R.string.lists_load_failed_title), stringResource(R.string.lists_load_failed_body), actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
    }
}

/**
 * The profile's playlists (live): each a card that opens it, "New playlist..." at the top, "You're offline" with Try again when
 * the server can't be reached. The New playlist window checks the name before anything is sent.
 */
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    actions: PlaylistsActions,
    name: TextFieldState,
    notices: Flow<ListNotice>,
    snackbar: SnackbarHostState,
) {
    ListNoticesEffect(notices, snackbar, state.session)
    ListsFrame(stringResource(R.string.lists_playlists_title), actions.onBack, snackbar) {
        when {
            state.session == null -> LazyColumn(Modifier.fillMaxSize()) { item { SignedOutNotice(actions.onOpenServers) } }
            state.phase == ListsPhase.LOADING -> Unit
            state.phase != ListsPhase.READY -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(state.phase, actions.onRetry) } }
            else -> PlaylistCards(state.playlists, actions)
        }
    }
    if (state.creating) {
        NameDialog(
            stringResource(R.string.lists_create_playlist_title), stringResource(R.string.lists_create), name, state.nameError, state.busy,
            onConfirm = actions.onCreate, onDismiss = actions.onCancelCreate,
        )
    }
}

@Composable
private fun PlaylistCards(playlists: List<ItemWithUserData>, actions: PlaylistsActions) {
    val strings = rememberStrings()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "new") { PrimaryButton(stringResource(R.string.lists_new_playlist), actions.onNew, Modifier.padding(horizontal = Gutter)) }
        if (playlists.isEmpty()) {
            item(key = "empty") { EmptyState(stringResource(R.string.lists_empty_title), stringResource(R.string.lists_empty_body)) }
        }
        items(playlists, key = { it.item.id }) { entry ->
            val count = entry.item.childCount
            ListCard(
                entry.item.displayName(strings),
                if (count != null) pluralStringResource(R.plurals.lists_item_count, count, count) else stringResource(R.string.lists_playlists_title),
                onClick = { actions.onOpen(Destination.Playlist(entry.item.id)) },
                modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth(),
            )
        }
    }
}
