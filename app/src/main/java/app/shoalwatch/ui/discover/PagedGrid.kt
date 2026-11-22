package app.shoalwatch.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.SecondaryButton
import kotlinx.coroutines.flow.distinctUntilChanged

/** A poster grid with the shared ending of every live list: a spinner while a page loads, or "Try again" when it failed. */
@Composable
internal fun PagedPosterGrid(
    session: Session?,
    onEndVisible: () -> Unit,
    loadingMore: Boolean,
    moreFailed: Boolean,
    onRetryMore: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    // Another account never starts part way down the last one's list.
    val gridState: LazyGridState = key(session) { rememberLazyGridState() }
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val visible = info.visibleItemsInfo
            info.totalItemsCount > 0 && visible.isNotEmpty() && visible.last().index >= info.totalItemsCount - 1 - 2 * visible.size
        }.distinctUntilChanged().collect { if (it) onEndVisible() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        if (loadingMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                val more = stringResource(R.string.library_loading_more)
                Box(Modifier.fillMaxWidth().padding(8.dp).semantics { contentDescription = more }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
        if (moreFailed) {
            item(key = "more-failed", span = { GridItemSpan(maxLineSpan) }) {
                SecondaryButton(stringResource(R.string.action_retry), onRetryMore, Modifier.fillMaxWidth())
            }
        }
    }
}
