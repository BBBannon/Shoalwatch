package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.shoalwatch.R
import androidx.compose.foundation.layout.statusBarsPadding

/**
 * A "Refresh" action for screen-reader and switch users, who cannot pull down: put it on the screen's title or heading. It is the
 * same call as the pull gesture, so it works whether or not anything has failed.
 */
@Composable
fun Modifier.refreshAction(onRefresh: () -> Unit): Modifier {
    val label = stringResource(R.string.action_refresh)
    return semantics { customActions = listOf(CustomAccessibilityAction(label) { onRefresh(); true }) }
}

/**
 * Pull to refresh. With [belowStatusBar] (a page whose picture runs under the status bar) the indicator is pushed down by the
 * status bar's height, so it is never hidden behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    belowStatusBar: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).then(if (belowStatusBar) Modifier.statusBarsPadding() else Modifier),
            )
        },
        content = content,
    )
}
