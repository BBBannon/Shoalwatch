package app.shoalwatch.ui.home

import app.shoalwatch.ui.browse.Selection
import app.shoalwatch.ui.browse.rememberCardSelect
import app.shoalwatch.ui.components.PhoneWideCardWidth
import app.shoalwatch.ui.components.TabletWideCardWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.isWorking
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CardRow
import app.shoalwatch.ui.components.CardShape
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.PosterCard
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.components.WideCard
import app.shoalwatch.ui.components.icon
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.label
import app.shoalwatch.ui.present.posterCard
import app.shoalwatch.ui.present.wideCard

/** The 20 dp between rows, handed out in the order the rows are added: the first row gets none, so it sits at the top of the list. */
internal class RowGaps {
    private var first = true

    fun next(): Modifier = if (first) { first = false; Modifier } else Modifier.padding(top = 20.dp)
}

/** A row of wide cards under [titleRes], with its own banner if this row alone failed. Nothing at all when the row is empty and fine. */
internal fun LazyListScope.wideRow(
    titleRes: Int,
    key: String,
    row: RowUi,
    session: Session,
    strings: Strings,
    state: HomeUiState,
    banner: BannerActions,
    actions: HomeActions,
    wide: Boolean,
    gaps: RowGaps,
    selection: Selection? = null,
    onMenu: (itemId: String) -> Unit,
) {
    if (row.items.isEmpty() && row.banner == null) return
    val gap = gaps.next()
    item(key = key) {
        Column(gap, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            row.banner?.let { FreshnessBanner(it, banner) }
            if (row.items.isNotEmpty()) {
                SectionHeader(stringResource(titleRes))
                CardRow(row.items.distinctBy { it.item.id }, key = { it.item.id }) { entry ->
                    val card = remember(entry, session, strings) { wideCard(entry, session, strings) }
                    WideCard(
                        card, onClick = { actions.onOpen(entry.destination()) }, onMenu = { onMenu(entry.item.id) },
                        width = if (wide) TabletWideCardWidth else PhoneWideCardWidth, working = state.working.isWorking(entry.item.id),
                        select = rememberCardSelect(selection, entry.item.id, card.title),
                    )
                }
            }
        }
    }
}

/** "Latest in <library>": a row of poster cards. */
internal fun LazyListScope.posterRow(
    view: LibraryView,
    latest: RowUi,
    session: Session,
    strings: Strings,
    state: HomeUiState,
    banner: BannerActions,
    actions: HomeActions,
    wide: Boolean,
    gaps: RowGaps,
    selection: Selection? = null,
    onMenu: (itemId: String) -> Unit,
) {
    if (latest.items.isEmpty() && latest.banner == null) return
    val gap = gaps.next()
    item(key = "latest-${view.id}") {
        Column(gap, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            latest.banner?.let { FreshnessBanner(it, banner) }
            if (latest.items.isNotEmpty()) {
                SectionHeader(stringResource(R.string.home_latest, view.name.ifBlank { stringResource(R.string.untitled) }))
                CardRow(latest.items.distinctBy { it.item.id }, key = { it.item.id }) { entry ->
                    val card = remember(entry, session, strings) { posterCard(entry, session, strings) }
                    PosterCard(
                        card, onClick = { actions.onOpen(entry.destination()) }, onMenu = { onMenu(entry.item.id) },
                        width = if (wide) 152.dp else 128.dp, working = state.working.isWorking(entry.item.id),
                        select = rememberCardSelect(selection, entry.item.id, card.title),
                    )
                }
            }
        }
    }
}

/** The libraries as large tappable cards: two across on a wide screen. */
internal fun LazyListScope.librariesSection(state: HomeUiState, actions: HomeActions, strings: Strings, wide: Boolean, gaps: RowGaps) {
    val gap = gaps.next()
    item(key = "libraries") {
        Column(gap, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // With no library (an offline first run, a profile with no library access) only the Playlists card shows: no "Libraries"
            // heading over it, and no banner under the empty message, which has its own Try again (T7 review R3).
            if (state.views.isNotEmpty()) {
                state.viewsBanner?.let { FreshnessBanner(it, BannerActions(actions.onRefresh, { state.serverId?.let(actions.onSignIn) }, { state.serverId?.let(actions.onReview) })) }
                SectionHeader(stringResource(R.string.home_libraries))
            }
            // The libraries, then the Playlists card as the last of them (Milestone 11).
            val cards = state.views.map<LibraryView, @Composable (Modifier) -> Unit> { view -> { m -> LibraryCard(view, strings, { actions.onOpenLibrary(view.id) }, m) } } +
                listOf<@Composable (Modifier) -> Unit>({ m -> PlaylistsCard(actions.onOpenPlaylists, m) })
            for (group in cards.chunked(if (wide) 2 else 1)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (card in group) card(Modifier.weight(1f))
                    if (group.size == 1 && wide) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun LibraryCard(view: LibraryView, strings: Strings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = view.name.ifBlank { stringResource(R.string.untitled) }
    val kind = view.kind.label(strings)
    // "Films, Films" would say the same word twice: when the name is the kind, say it once.
    val description = if (name == kind) name else stringResource(R.string.library_card_cd, name, kind)
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp).semantics { contentDescription = description },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(view.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Playlists: the card at the end of the libraries that opens the profile's playlists. */
@Composable
internal fun PlaylistsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    app.shoalwatch.ui.lists.ListCard(stringResource(R.string.lists_playlists_title), stringResource(R.string.lists_home_card_subtitle), onClick, modifier)
}
