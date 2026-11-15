package app.shoalwatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CollectMessages
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.ExpandableText
import app.shoalwatch.ui.components.FavouriteButton
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.HeroBackdrop
import app.shoalwatch.ui.components.OverlayBackButton
import app.shoalwatch.ui.components.ProgressBarThin
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.components.SkeletonBlock
import app.shoalwatch.ui.components.WatchedButton
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.detailFacts
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.episodeCode
import app.shoalwatch.ui.present.percentWatched
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.resumeLabel
import app.shoalwatch.ui.present.wideArtwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What the detail page can ask for. The route wires these to the ViewModel and to navigation. */
class DetailActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onSignIn: (serverId: String) -> Unit,
    val onReview: (serverId: String) -> Unit,
    val onOpenServers: () -> Unit,
    val onSetPlayed: (played: Boolean) -> Unit,
    val onSetFavourite: (favourite: Boolean) -> Unit,
)

/**
 * A film or an episode. Play is always there; until the player arrives (the next milestone) pressing it says so in a snackbar:
 * nothing is faked and the button is never silently dead. Content is capped at a readable width and centred on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(state: DetailUiState, actions: DetailActions, messages: Flow<String>, snackbar: SnackbarHostState) {
    CollectMessages(messages, snackbar)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val item = state.item
            val session = state.session
            when {
                state.phase == DetailPhase.SIGNED_OUT -> Column(Modifier.statusBarsPadding()) {
                    OverlayBackButton(actions.onBack, Modifier.padding(8.dp))
                    SignedOutNotice(actions.onOpenServers)
                }
                state.phase == DetailPhase.GONE -> Column(Modifier.statusBarsPadding()) {
                    OverlayBackButton(actions.onBack, Modifier.padding(8.dp))
                    EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body), actionLabel = stringResource(R.string.cd_back), onAction = actions.onBack)
                }
                state.phase == DetailPhase.ERROR -> Column(Modifier.statusBarsPadding()) {
                    OverlayBackButton(actions.onBack, Modifier.padding(8.dp))
                    state.banner?.let { FreshnessBanner(it, bannerActions(state, actions)) }
                    EmptyState(stringResource(R.string.error_title), stringResource(R.string.error_body))
                }
                item == null || session == null -> Column(Modifier.fillMaxSize()) { Loading(actions.onBack) }
                else -> Column(Modifier.fillMaxSize()) {
                    RefreshLine(state.refreshing && !state.pulling, Modifier.statusBarsPadding())
                    PullToRefreshBox(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        Content(state, item, session, actions, snackbar)
                    }
                }
            }
        }
    }
}

private fun bannerActions(state: DetailUiState, actions: DetailActions) =
    BannerActions(actions.onRefresh, { state.session?.let { actions.onSignIn(it.serverId) } }, { state.session?.let { actions.onReview(it.serverId) } })

/** The first load: the same shape as the finished page (wide picture, title bars, a button), so nothing jumps. */
@Composable
private fun Loading(onBack: () -> Unit) {
    val loading = stringResource(R.string.cd_loading_content)
    ReadableBox {
        Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = loading }) {
            HeroBackdrop(null, "", onBack)
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.width(220.dp).height(32.dp))
                SkeletonBlock(Modifier.width(140.dp).height(18.dp))
                SkeletonBlock(Modifier.fillMaxWidth().height(56.dp), corner = 12.dp)
                SkeletonBlock(Modifier.fillMaxWidth().height(16.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(16.dp))
            }
        }
    }
}

@Composable
private fun Content(state: DetailUiState, entry: ItemWithUserData, session: Session, actions: DetailActions, snackbar: SnackbarHostState) {
    val strings = rememberStrings()
    val item = entry.item
    val data = entry.userData
    val scope = rememberCoroutineScope()
    val later = stringResource(R.string.detail_playback_later)
    val facts = detailFacts(strings, item)
    val percent = percentWatched(item, data)
    val resume = resumeLabel(strings, data?.positionMs ?: 0)
    val watched = data?.played == true
    val overview = plainOverview(item.overview)
    val title = item.displayName(strings)

    ReadableBox {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroBackdrop(wideArtwork(item, session), title, actions.onBack)
            state.banner?.let { FreshnessBanner(it, bannerActions(state, actions)) }
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.kind == ItemKind.EPISODE) {
                    val line = listOfNotNull(item.seriesName?.takeIf { it.isNotBlank() }, episodeCode(strings, item)).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                facts.text?.let { shown ->
                    Text(shown, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = facts.spoken ?: shown })
                }
                WatchState(watched, resume, percent?.let { strings.get(R.string.state_percent_watched, it.toString()) }, percent)
                Button(
                    onClick = { scope.launch { snackbar.showSnackbar(later) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.detail_play), style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WatchedButton(
                        stringResource(if (watched) R.string.sheet_mark_unwatched else R.string.sheet_mark_watched),
                        watched = watched, working = state.working.contains(Work(item.id, ActionKind.PLAYED)),
                        onClick = { actions.onSetPlayed(!watched) }, modifier = Modifier.weight(1f),
                    )
                    FavouriteButton(data?.favourite == true, state.working.contains(Work(item.id, ActionKind.FAVOURITE)), { actions.onSetFavourite(data?.favourite != true) })
                }
                val seriesId = item.seriesId
                if (item.kind == ItemKind.EPISODE && seriesId != null) {
                    TextButton(onClick = { actions.onOpen(Destination.Series(seriesId, item.seasonId ?: item.parentId)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sheet_go_series), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (overview.isNotEmpty()) {
                    Text(stringResource(R.string.detail_overview_heading), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    ExpandableText(overview)
                }
            }
        }
    }
}

/** Watched state in words and a symbol, never by colour alone: "Watched", "Resume from 23 min" (with a thin bar and the percentage), or "Not watched yet". */
@Composable
private fun WatchState(watched: Boolean, resume: String?, percentText: String?, percent: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (watched) Icons.Filled.Check else Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            val text = when {
                watched -> stringResource(R.string.detail_watched_state)
                resume != null -> listOfNotNull(resume, percentText).joinToString(" · ")
                else -> stringResource(R.string.detail_unwatched_state)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (percent != null && !watched) ProgressBarThin(percent / 100f, Modifier.fillMaxWidth())
    }
}
