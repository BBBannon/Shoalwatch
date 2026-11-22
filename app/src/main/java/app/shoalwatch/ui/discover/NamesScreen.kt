package app.shoalwatch.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.discover.Named
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.lists.ListsFrame
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.ReadProblem
import kotlinx.coroutines.flow.distinctUntilChanged

/** What a genres or studios page can ask for. The route wires these to the ViewModel and to navigation. */
class NamesActions(
    val onBack: () -> Unit,
    val onOpen: (Named) -> Unit,
    val onEndVisible: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/** The genres or studios of a library, each a row that opens the filtered list; "You're offline" with Try again when the server can't be reached. */
@Composable
fun NamesScreen(state: NamesUiState, actions: NamesActions) {
    val snackbar = remember { SnackbarHostState() }
    val genres = state.kind == NamesKind.GENRES
    ListsFrame(stringResource(if (genres) R.string.discover_genres_title else R.string.discover_studios_title), actions.onBack, snackbar) {
        val names = state.names
        when {
            state.session == null -> LazyColumn(Modifier.fillMaxSize()) { item { SignedOutNotice(actions.onOpenServers) } }
            names.phase == ListsPhase.LOADING -> Unit
            names.phase != ListsPhase.READY -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(names.phase, actions.onRetry) } }
            names.items.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item { EmptyState(stringResource(R.string.discover_names_empty_title), stringResource(if (genres) R.string.discover_genres_empty else R.string.discover_studios_empty)) }
            }
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val visible = info.visibleItemsInfo
                        info.totalItemsCount > 0 && visible.isNotEmpty() && visible.last().index >= info.totalItemsCount - 1 - 2 * visible.size
                    }.distinctUntilChanged().collect { if (it) actions.onEndVisible() }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(names.items, key = { it.id }) { named ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = { actions.onOpen(named) }).padding(horizontal = Gutter, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Text(named.name, style = MaterialTheme.typography.bodyLarge) }
                        HorizontalDivider()
                    }
                    if (names.loadingMore) item(key = "more") {
                        val more = stringResource(R.string.library_loading_more)
                        Box(
                            Modifier.fillMaxWidth().padding(8.dp).semantics { contentDescription = more; liveRegion = LiveRegionMode.Polite },
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
                    }
                    if (names.moreFailed) item(key = "more-failed") { SecondaryButton(stringResource(R.string.action_retry), actions.onRetry, Modifier.fillMaxWidth().padding(Gutter)) }
                }
            }
        }
    }
}
