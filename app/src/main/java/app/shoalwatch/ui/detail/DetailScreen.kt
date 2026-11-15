package app.shoalwatch.ui.detail

import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.text.TextStyle
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.present.Facts
import app.shoalwatch.ui.present.joinAll
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.refreshAction
import app.shoalwatch.ui.components.ScrollingColumn
import app.shoalwatch.ui.components.PullRefresh
import app.shoalwatch.ui.components.ErrorState
import app.shoalwatch.ui.components.BackBar
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.data.images.thumbArtwork
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
        // The navigation bar's inset is the Scaffold's: content ends above the bar and the snackbar sits above it. The status bar's
        // is not, because a picture runs up under it.
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val item = state.item
            val session = state.session
            when {
                state.phase == DetailPhase.SIGNED_OUT -> ScrollingColumn {
                    BackBar(actions.onBack)
                    SignedOutNotice(actions.onOpenServers)
                }
                state.phase == DetailPhase.GONE -> ScrollingColumn {
                    BackBar(actions.onBack)
                    EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body), actionLabel = stringResource(R.string.cd_back), onAction = actions.onBack)
                }
                // One message with its button; the banner is left out so there are not two.
                state.phase == DetailPhase.ERROR -> ScrollingColumn {
                    BackBar(actions.onBack)
                    ErrorState(state.banner ?: BannerKind.GENERIC, bannerActions(state, actions))
                }
                item == null || session == null -> Loading(actions.onBack)
                else -> {
                    PullRefresh(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize(), belowStatusBar = true) {
                        Content(state, item, session, actions, snackbar)
                    }
                    RefreshLine(state.refreshing && !state.pulling, Modifier.statusBarsPadding().align(Alignment.TopCenter))
                }
            }
        }
    }
}

private fun bannerActions(state: DetailUiState, actions: DetailActions) =
    BannerActions(actions.onRefresh, { state.session?.let { actions.onSignIn(it.serverId) } }, { state.session?.let { actions.onReview(it.serverId) } })

/** The first load: a Back bar and the shape of the page's text and button, so nothing jumps much. (Whether there is a picture is not known yet.) */
@Composable
private fun Loading(onBack: () -> Unit) {
    val loading = stringResource(R.string.cd_loading_content)
    ReadableBox {
        Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = loading }) {
            BackBar(onBack)
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
    val overview = remember(item.overview) { plainOverview(item.overview) }
    val title = item.displayName(strings)

    ReadableBox {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A picture only when there is a real one: a backdrop, or an episode's own still. Otherwise a plain Back bar, and a film
            // shows its poster beside the title (as a series does).
            val hero = if (item.kind == ItemKind.EPISODE) item.thumbArtwork(session) ?: item.primaryArtwork(session) else item.backdropArtwork(session)
            if (hero != null) HeroBackdrop(hero, title, actions.onBack) else BackBar(actions.onBack)
            state.banner?.let { FreshnessBanner(it, bannerActions(state, actions)) }
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.kind == ItemKind.EPISODE) {
                    val line = joinAll(strings, listOfNotNull(item.seriesName?.takeIf { it.isNotBlank() }, episodeCode(strings, item)))
                    if (line != null) Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (hero == null && item.kind != ItemKind.EPISODE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Artwork(item.primaryArtwork(session), null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.width(96.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { TitleAndFacts(title, facts, actions.onRefresh, MaterialTheme.typography.headlineSmall) }
                    }
                } else {
                    TitleAndFacts(title, facts, actions.onRefresh, MaterialTheme.typography.headlineMedium)
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

/** The title (a screen-reader heading with a Refresh action) and the facts under it. */
@Composable
private fun TitleAndFacts(title: String, facts: Facts, onRefresh: () -> Unit, style: TextStyle) {
    Text(title, style = style, modifier = Modifier.refreshAction(onRefresh).semantics { heading() })
    facts.text?.let { shown ->
        Text(shown, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = facts.spoken ?: shown })
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
                resume != null -> joinAll(rememberStrings(), listOfNotNull(resume, percentText)) ?: resume
                else -> stringResource(R.string.detail_unwatched_state)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (percent != null && !watched) ProgressBarThin(percent / 100f, Modifier.fillMaxWidth())
    }
}
