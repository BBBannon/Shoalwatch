package app.shoalwatch.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.ExpandableText
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.lists.ListsFrame
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.ReadProblem
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings

/** What a person's page can ask for. The route wires these to the ViewModel and to navigation. */
class PersonActions(
    val onBack: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onEndVisible: () -> Unit,
    val onRetry: () -> Unit,
    val onRetryMore: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * A person: their picture and overview, then the films and shows they appear in (a page at a time as you scroll). "You're
 * offline" with Try again when the server can't be reached.
 */
@Composable
fun PersonScreen(state: PersonUiState, actions: PersonActions) {
    val strings = rememberStrings()
    val snackbar = remember { SnackbarHostState() }
    val person = state.person
    val session = state.session
    val title = person?.displayName(strings) ?: stringResource(R.string.person_title)
    ListsFrame(title, actions.onBack, snackbar) {
        when {
            session == null -> LazyColumn(Modifier.fillMaxSize()) { item { SignedOutNotice(actions.onOpenServers) } }
            state.phase == ListsPhase.LOADING -> Unit
            state.phase != ListsPhase.READY || person == null -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(state.phase, actions.onRetry) } }
            else -> {
                val works = state.works
                val overview = remember(person.overview) { plainOverview(person.overview) }
                PagedPosterGrid(session, actions.onEndVisible, works.loadingMore, works.moreFailed, actions.onRetryMore) {
                    item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Artwork(person.primaryArtwork(session), null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.width(120.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (overview.isNotEmpty()) ExpandableText(overview) else Text(
                                    stringResource(R.string.person_no_overview), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "works-heading", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.person_works), Modifier.padding(top = 8.dp)) }
                    when {
                        works.phase == ListsPhase.LOADING -> Unit
                        works.phase != ListsPhase.READY -> item(key = "works-problem", span = { GridItemSpan(maxLineSpan) }) { ReadProblem(works.phase, actions.onRetry) }
                        works.items.isEmpty() -> item(key = "works-empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.person_no_works), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        else -> items(works.items, key = { it.item.id }) { entry -> ItemTile(entry, session, onOpen = { actions.onOpen(entry.destination()) }) }
                    }
                }
            }
        }
    }
}
