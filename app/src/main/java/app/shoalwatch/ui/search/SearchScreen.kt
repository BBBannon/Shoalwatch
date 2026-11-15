package app.shoalwatch.ui.search

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.height
import app.shoalwatch.ui.components.SkeletonBlock
import app.shoalwatch.ui.components.ScrollingColumn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.components.Tone
import app.shoalwatch.ui.components.titleLines
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.SearchRowUi
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.searchRow

/** What the search screen can ask for. The route wires these to the ViewModel and to navigation. */
class SearchActions(
    val onQueryChange: (String) -> Unit,
    val onClear: () -> Unit,
    val onRetry: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onOpenServers: () -> Unit,
)

/**
 * Search: a field at the top (focused the first time the tab opens, the keyboard's action is Search, a clear button when there
 * is text), then the results under Films, Series and Episodes, or the one plain sentence that fits the moment (type more,
 * nothing found, couldn't search). Offline it says it is searching what is saved. Nothing typed is kept anywhere.
 */
@Composable
fun SearchScreen(state: SearchUiState, actions: SearchActions) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (state.phase == SearchPhase.SIGNED_OUT) {
            SignedOutNotice(actions.onOpenServers)
            return@Column
        }
        ReadableBox {
            // The keyboard's inset (less what the bottom bar already takes) is applied here, so results are never under the keyboard.
            Column(Modifier.fillMaxSize().imePadding()) {
                SearchField(state, actions)
                RefreshLine(state.phase == SearchPhase.SEARCHING)
                Results(state, actions)
            }
        }
    }
}

@Composable
private fun SearchField(state: SearchUiState, actions: SearchActions) {
    // The box owns the text; the ViewModel is told on every change. It starts from what the ViewModel holds (a rotation), never from saved state.
    val text = remember { TextFieldState(initialText = state.query) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedBefore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { snapshotFlow { text.text.toString() }.collect { actions.onQueryChange(it) } }
    LaunchedEffect(state.query) {
        // Cleared from outside (a new account): the box follows.
        if (state.query.isEmpty() && text.text.isNotEmpty()) text.clearText()
    }
    LaunchedEffect(Unit) {
        if (!focusedBefore) {
            focusedBefore = true
            runCatching { focus.requestFocus() }
        }
    }
    OutlinedTextField(
        state = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp).focusRequester(focus),
        label = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.text.isNotEmpty()) {
                IconButton(onClick = { text.clearText(); actions.onClear() }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        onKeyboardAction = {
            keyboard?.hide()
            focusManager.clearFocus()
        },
    )
}

@Composable
private fun Results(state: SearchUiState, actions: SearchActions) {
    when (state.phase) {
        SearchPhase.IDLE -> ScrollingColumn {
            EmptyState(stringResource(R.string.nav_search), stringResource(R.string.search_prompt), icon = Icons.Filled.Search)
        }
        SearchPhase.SEARCHING -> Searching()
        SearchPhase.EMPTY -> ScrollingColumn {
            EmptyState(stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body, state.query.trim()), icon = Icons.Filled.Search)
        }
        SearchPhase.ERROR -> ScrollingColumn {
            EmptyState(stringResource(R.string.error_title), state.error, actionLabel = stringResource(R.string.action_retry), onAction = actions.onRetry)
        }
        else -> ResultList(state, actions)
    }
}

/** While a search runs: a few quiet rows the shape of results, and "Searching…" announced, never a blank screen. */
@Composable
private fun Searching() {
    val searching = stringResource(R.string.search_searching)
    Column(
        Modifier.fillMaxSize().padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = searching; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.width(48.dp), ratio = 2f / 3f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(18.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultList(state: SearchUiState, actions: SearchActions) {
    val strings = rememberStrings()
    val session = state.session ?: return
    val count = state.films.size + state.series.size + state.episodes.size
    val countText = pluralStringResource(R.plurals.search_results_count, count, count)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // How many results there are: shown, and announced politely when they arrive.
        item(key = "count") {
            Text(
                countText, Modifier.padding(horizontal = Gutter).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.offline) {
            item(key = "offline") {
                // NoticeCard merges its text into one element, which is what a live region announces.
                NoticeCard(
                    title = stringResource(R.string.search_offline), body = null, tone = Tone.Info,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                    announce = true,
                )
            }
        }
        group(R.string.search_heading_films, "films", state.films) { entry -> ResultRow(searchRow(entry, session, strings), entry, false, actions) }
        group(R.string.search_heading_series, "series", state.series) { entry -> ResultRow(searchRow(entry, session, strings), entry, false, actions) }
        group(R.string.search_heading_episodes, "episodes", state.episodes) { entry -> ResultRow(searchRow(entry, session, strings), entry, true, actions) }
    }
}

private fun LazyListScope.group(
    heading: Int,
    key: String,
    entries: List<ItemWithUserData>,
    row: @Composable (ItemWithUserData) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "heading-$key") { SectionHeader(stringResource(heading), Modifier.padding(top = 12.dp)) }
    items(entries.distinctBy { it.item.id }, key = { "$key-${it.item.id}" }) { row(it) }
}

@Composable
private fun ResultRow(row: SearchRowUi, entry: ItemWithUserData, wide: Boolean, actions: SearchActions) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { actions.onOpen(entry.destination()) }
            .padding(horizontal = Gutter, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = row.label },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide) Artwork(row.artwork, null, aspect = ArtworkAspect.WIDE, title = row.title, modifier = Modifier.width(96.dp))
        else Artwork(row.artwork, null, aspect = ArtworkAspect.POSTER, title = row.title, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(2), overflow = TextOverflow.Ellipsis)
            if (row.subtitle != null) {
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = titleLines(1), overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
