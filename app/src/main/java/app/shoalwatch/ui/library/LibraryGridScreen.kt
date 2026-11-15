package app.shoalwatch.ui.library

import app.shoalwatch.ui.present.sheetTitle
import app.shoalwatch.ui.components.refreshAction
import app.shoalwatch.ui.components.ErrorState
import app.shoalwatch.ui.components.ScrollingColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Sort
import app.shoalwatch.ui.browse.isWorking
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CollectMessages
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.ItemActionsSheet
import app.shoalwatch.ui.components.PosterCard
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.SheetActions
import app.shoalwatch.ui.components.SheetTarget
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.components.SkeletonBlock
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.detailsDestination
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.posterCard
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.totalLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the grid can ask for. The route wires these to the ViewModel and to navigation. */
class LibraryGridActions(
    val onBack: () -> Unit,
    val onSetSort: (Sort) -> Unit,
    val onSetFilter: (ItemFilter) -> Unit,
    val onClearFilters: () -> Unit,
    val onEndVisible: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onSignIn: (serverId: String) -> Unit,
    val onReview: (serverId: String) -> Unit,
    val onOpenServers: () -> Unit,
    val onSetPlayed: (itemId: String, played: Boolean) -> Unit,
    val onSetFavourite: (itemId: String, favourite: Boolean) -> Unit,
)

/**
 * A library as one continuously scrolling poster grid: no pages, no buttons. A compact bar of sort and filter controls scrolls
 * away with the posters; the total shows quietly beside it. Cards are the same shape as everywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryGridScreen(state: LibraryGridUiState, actions: LibraryGridActions, messages: Flow<String>, snackbar: SnackbarHostState) {
    CollectMessages(messages, snackbar)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // One line with an ellipsis: a fixed-height bar cannot hold two lines at a large text size. The whole name is in the sheet and the item's page.
                title = { Text(state.title, Modifier.refreshAction(actions.onRefresh).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                GridPhase.SIGNED_OUT -> ScrollingColumn { SignedOutNotice(actions.onOpenServers) }
                GridPhase.GONE -> ScrollingColumn {
                    EmptyState(
                        stringResource(R.string.gone_title), stringResource(R.string.gone_body),
                        actionLabel = stringResource(R.string.cd_back), onAction = actions.onBack,
                    )
                }
                else -> {
                    RefreshLine(state.refreshing && !state.pulling)
                    PullToRefreshBox(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        Grid(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid(state: LibraryGridUiState, actions: LibraryGridActions) {
    val strings = rememberStrings()
    val session = state.session
    val gridState = rememberLazyGridState()
    var sheetId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    val loading = stringResource(R.string.cd_loading_content)
    val banner = BannerActions(actions.onRefresh, { session?.let { actions.onSignIn(it.serverId) } }, { session?.let { actions.onReview(it.serverId) } })

    // Tell the ViewModel whether the end of the grid is within about two screens, so it loads the next page ahead of the reader.
    LaunchedEffect(gridState, actions) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val visible = info.visibleItemsInfo
            info.totalItemsCount > 0 && visible.isNotEmpty() && visible.last().index >= info.totalItemsCount - 1 - 2 * visible.size
        }.distinctUntilChanged().collect(actions.onEndVisible)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // With nothing to show and a failed load, the error state below is the one message (with its button), so no banner too.
                if (!state.showError) state.banner?.let { FreshnessBanner(it, banner, padded = false) }
                SortFilterBar(state, actions)
            }
        }
        if (state.showSkeleton) {
            items((0 until 12).toList(), key = { "skeleton-$it" }) { n ->
                Column(
                    if (n == 0) Modifier.semantics { contentDescription = loading } else Modifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(), ratio = 2f / 3f)
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).size(width = 90.dp, height = 16.dp))
                }
            }
        }
        items(state.items.distinctBy { it.item.id }, key = { it.item.id }) { entry ->
            if (session != null) {
                val card = remember(entry, session, strings) { posterCard(entry, session, strings) }
                PosterCard(
                    card, onClick = { actions.onOpen(entry.destination()) }, onMenu = { sheetId = entry.item.id },
                    working = state.working.isWorking(entry.item.id),
                )
            }
        }
        if (state.refreshing && state.canLoadMore && state.items.isNotEmpty()) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                val more = stringResource(R.string.library_loading_more)
                Box(Modifier.fillMaxWidth().padding(8.dp).semantics { contentDescription = more }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
        if (state.showEmpty || state.showError) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                when {
                    state.showError -> state.banner?.let { ErrorState(it, banner) }
                    state.filtered -> EmptyState(
                        stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_filtered),
                        actionLabel = stringResource(R.string.action_clear_filters), onAction = actions.onClearFilters,
                    )
                    else -> EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
                }
            }
        }
    }

    val entry: ItemWithUserData? = sheetId?.let { id -> state.items.firstOrNull { it.item.id == id } }
    if (entry != null) {
        val kind = entry.item.kind
        ItemActionsSheet(
            SheetTarget(
                itemId = entry.item.id, title = entry.item.sheetTitle(strings), played = entry.userData?.played == true,
                favourite = entry.userData?.favourite == true, canRemoveFromResume = false, seriesId = null,
                canMarkPlayed = kind == ItemKind.MOVIE || kind == ItemKind.SERIES || kind == ItemKind.EPISODE,
            ),
            SheetActions(
                onRemoveFromResume = {},
                onSetPlayed = actions.onSetPlayed,
                onSetFavourite = actions.onSetFavourite,
                onGoToSeries = {},
                onDetails = { actions.onOpen(entry.detailsDestination()) },
            ),
            onDismiss = { sheetId = null },
        )
    }
}

@Composable
private fun SortFilterBar(state: LibraryGridUiState, actions: LibraryGridActions) {
    val strings = rememberStrings()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        totalLabel(strings, state.kind, state.total)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SortFilterChips(state.sort, state.filter, actions.onSetSort, actions.onSetFilter)
    }
}
