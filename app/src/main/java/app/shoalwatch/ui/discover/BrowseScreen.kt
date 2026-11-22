package app.shoalwatch.ui.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.lists.ListsFrame
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.ReadProblem
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.destination

/** What the filtered list can ask for. The route wires these to the ViewModel and to navigation. */
class BrowseActions(
    val onBack: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onGenre: (id: String?) -> Unit,
    val onStudio: (id: String?) -> Unit,
    val onYear: (Int?) -> Unit,
    val onRating: (String?) -> Unit,
    val onTag: (String?) -> Unit,
    val onLetter: (String?) -> Unit,
    val onClear: () -> Unit,
    val onEndVisible: () -> Unit,
    val onRetry: () -> Unit,
    val onRetryMore: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * A library's live filtered list: a row of filter chips (Genre, Year, Age rating, Studio, Tag; each opens a menu of the library's
 * own values with "Any" first, one value each), a letter bar ("#" then A to Z), and the matching posters a page at a time.
 * "You're offline" with Try again when the server can't be reached.
 */
@Composable
fun BrowseScreen(state: BrowseUiState, actions: BrowseActions) {
    val snackbar = remember { SnackbarHostState() }
    ListsFrame(state.title.ifEmpty { stringResource(R.string.discover_filter_title) }, actions.onBack, snackbar) {
        val session = state.session
        when {
            session == null -> LazyColumn(Modifier.fillMaxSize()) { item { SignedOutNotice(actions.onOpenServers) } }
            state.phase == ListsPhase.LOADING -> Unit
            state.phase != ListsPhase.READY -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(state.phase, actions.onRetry) } }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterRow(state, actions)
                LetterBar(state.filter.letter, actions.onLetter)
                val list = state.list
                Box(Modifier.weight(1f)) {
                    when {
                        list.phase == ListsPhase.LOADING -> Unit
                        list.phase != ListsPhase.READY -> LazyColumn(Modifier.fillMaxSize()) { item { ReadProblem(list.phase, actions.onRetry) } }
                        list.items.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                if (state.filter.isEmpty) {
                                    EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
                                } else {
                                    EmptyState(
                                        stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_filtered),
                                        actionLabel = stringResource(R.string.action_clear_filters), onAction = actions.onClear,
                                    )
                                }
                            }
                        }
                        else -> PagedPosterGrid(session, actions.onEndVisible, list.loadingMore, list.moreFailed, actions.onRetryMore) {
                            items(list.items, key = { it.item.id }) { entry -> ItemTile(entry, session, onOpen = { actions.onOpen(entry.destination()) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(state: BrowseUiState, actions: BrowseActions) {
    val options = state.options
    val filter = state.filter
    val selectedLabel = stringResource(R.string.discover_filter_selected)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterMenu(
            stringResource(R.string.discover_filter_genre), filter.genreId?.let { id -> options?.genres?.firstOrNull { it.id == id }?.name ?: selectedLabel },
            options?.genres?.map { it.id to it.name }.orEmpty(), actions.onGenre,
        )
        FilterMenu(
            stringResource(R.string.discover_filter_year), filter.year?.toString(),
            options?.years?.map { it.toString() to it.toString() }.orEmpty(), { actions.onYear(it?.toIntOrNull()) },
        )
        FilterMenu(
            stringResource(R.string.discover_filter_rating), filter.rating,
            options?.ratings?.map { it to it }.orEmpty(), actions.onRating,
        )
        FilterMenu(
            stringResource(R.string.discover_filter_studio), filter.studioId?.let { id -> options?.studios?.firstOrNull { it.id == id }?.name ?: selectedLabel },
            options?.studios?.map { it.id to it.name }.orEmpty(), actions.onStudio,
        )
        FilterMenu(
            stringResource(R.string.discover_filter_tag), filter.tag,
            options?.tags?.map { it to it }.orEmpty(), actions.onTag,
        )
        if (!filter.isEmpty) {
            FilterChip(selected = false, onClick = actions.onClear, label = { Text(stringResource(R.string.action_clear_filters)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

/** One filter: a chip showing its name (or the value chosen) that opens a menu of "Any" and the values, [choices] as value to words. */
@Composable
private fun FilterMenu(name: String, chosen: String?, choices: List<Pair<String, String>>, onPick: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = if (chosen == null) name else stringResource(R.string.discover_filter_chosen, name, chosen)
    Box {
        FilterChip(
            selected = chosen != null,
            onClick = { open = true },
            enabled = choices.isNotEmpty() || chosen != null,
            modifier = Modifier.heightIn(min = 48.dp),
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        if (!LocalCovered.current) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuChoice(stringResource(R.string.discover_filter_any), selected = chosen == null) { open = false; onPick(null) }
            HorizontalDivider()
            choices.forEach { (value, words) ->
                MenuChoice(words, selected = words == chosen) { open = false; onPick(value) }
            }
        }
    }
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton },
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

/** "#" then A to Z in one scrolling row; the letter chosen is marked and tapping it again clears it. */
@Composable
internal fun LetterBar(chosen: String?, onLetter: (String?) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BROWSE_LETTERS.forEach { letter ->
            val words = if (letter == "#") stringResource(R.string.discover_letter_hash_cd) else stringResource(R.string.discover_letter_cd, letter)
            FilterChip(
                selected = letter == chosen,
                onClick = { onLetter(letter) },
                label = { Text(letter, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = words },
            )
        }
    }
}
