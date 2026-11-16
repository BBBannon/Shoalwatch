package app.shoalwatch.ui.home

import app.shoalwatch.ui.present.sheetTitle
import app.shoalwatch.ui.components.PhoneWideCardWidth
import app.shoalwatch.ui.components.TabletWideCardWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import app.shoalwatch.ui.components.ScrollingColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CollectMessages
import app.shoalwatch.ui.components.ConnectionChip
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.ItemActionsSheet
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.SheetActions
import app.shoalwatch.ui.components.SheetTarget
import app.shoalwatch.ui.components.SkeletonRow
import app.shoalwatch.ui.components.Tone
import app.shoalwatch.ui.components.isWideScreen
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.detailsDestination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.rememberStrings
import kotlinx.coroutines.flow.Flow

/** Everything Home can ask for. The route wires these to the ViewModel and to navigation. */
class HomeActions(
    val onOpenServers: () -> Unit,
    val onSwitchAccount: (userId: String) -> Unit,
    /** Pull-to-refresh and a banner's Retry. */
    val onRefresh: () -> Unit,
    /** The connection chip's Retry (and the empty state's). */
    val onRetryConnection: () -> Unit,
    val onOpenLibrary: (viewId: String) -> Unit,
    val onOpen: (Destination) -> Unit,
    val onSignIn: (serverId: String) -> Unit,
    val onReview: (serverId: String) -> Unit,
    val onRemoveFromResume: (itemId: String) -> Unit,
    val onSetPlayed: (itemId: String, played: Boolean) -> Unit,
    val onSetFavourite: (itemId: String, favourite: Boolean) -> Unit,
)

/**
 * Home. The top bar carries the server name, a compact connection chip and the account button. Below, in order: Continue
 * Watching, Next Up, "Latest in <library>" for each library, then the libraries; a row with nothing in it is left out. Pull down
 * to refresh. Long-press a card, or use its dots, for the item actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, messages: Flow<String>, snackbar: SnackbarHostState) {
    CollectMessages(messages, snackbar, state.session)
    when (state.phase) {
        HomePhase.SIGNED_OUT -> Placeholder(actions) {
            NoticeCard(stringResource(R.string.home_signed_out_title), stringResource(R.string.home_signed_out_body), tone = Tone.Info)
            PrimaryButton(stringResource(R.string.home_manage), actions.onOpenServers)
        }
        HomePhase.LOAD_FAILED -> Placeholder(actions) {
            NoticeCard(stringResource(R.string.startup_failed_title), stringResource(R.string.startup_failed_body), tone = Tone.Danger)
            PrimaryButton(stringResource(R.string.action_retry), actions.onRetryConnection)
        }
        else -> Column(Modifier.fillMaxSize()) {
            HomeTopBar(state, actions)
            RefreshLine(state.refreshing && !state.pulling)
            PullToRefreshBox(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                HomeList(state, actions)
            }
        }
    }
}

@Composable
private fun Placeholder(actions: HomeActions, content: @Composable () -> Unit) {
    // Scrolls: the notice, its text and the button together are taller than a small screen at a large text size.
    ScrollingColumn {
        PlaceholderTopBar(actions)
        ReadableBox {
            Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Composable
private fun HomeList(state: HomeUiState, actions: HomeActions) {
    val strings = rememberStrings()
    val session = state.session
    var sheetId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    var sheetFromResume by rememberSaveable(session) { mutableStateOf(false) }
    val wide = isWideScreen()
    val banner = BannerActions(actions.onRefresh, { state.serverId?.let(actions.onSignIn) }, { state.serverId?.let(actions.onReview) })
    val skeleton = state.phase == HomePhase.LOADING || state.stillLoading

    ReadableBox {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // The list's first item never changes. Rows that arrive ABOVE the item the list is showing push the list down to keep that
            // item in view, so Home opened part way down with its first heading off the top. With this anchor at the very top, new
            // rows land below it and Home stays at the top until the user has scrolled. It is 1 dp tall, not 0: a list does not count an item
            // with no height as the one in view, so it would not anchor anything.
            item(key = "top") { Spacer(Modifier.height(1.dp)) }
            // The space between items belongs to the items (each but the first has it above), not to the list: spacing the list would
            // also put 20 dp between the anchor and the first row.
            val gaps = RowGaps()
            val connection = state.connection
            if (connection != null && connection.needsCertificateReview && state.serverId != null) {
                val gap = gaps.next()
                item(key = "certificate") { Box(gap) { CertificateNotice(connection, onReview = { actions.onReview(state.serverId) }) } }
            }
            state.topBanner?.let { kind ->
                val gap = gaps.next()
                item(key = "banner") { Box(gap) { FreshnessBanner(kind, banner) } }
            }
            if (skeleton) {
                val wideGap = gaps.next()
                item(key = "skeleton-wide") { Box(wideGap) { SkeletonRow(16f / 9f, if (wide) TabletWideCardWidth else PhoneWideCardWidth) } }
                val posterGap = gaps.next()
                item(key = "skeleton-poster") { Box(posterGap) { SkeletonRow(2f / 3f, if (wide) 152.dp else 128.dp) } }
            } else if (state.localFailure) {
                // Reading what is saved failed on this phone: one message that says so, not a refresh banner as well.
                val gap = gaps.next()
                item(key = "empty") {
                    Box(gap) {
                        EmptyState(
                            stringResource(R.string.home_local_failure_title), stringResource(R.string.home_local_failure_body),
                            actionLabel = stringResource(R.string.action_retry), onAction = actions.onRefresh,
                        )
                    }
                }
            } else if (!state.hasContent) {
                val gap = gaps.next()
                item(key = "empty") {
                    Box(gap) {
                        EmptyState(
                            stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body),
                            actionLabel = stringResource(R.string.action_retry), onAction = actions.onRefresh,
                        )
                    }
                }
            }
            if (session != null && !skeleton) {
                wideRow(R.string.home_continue, "resume", state.resume, session, strings, state, banner, actions, wide, gaps) { id -> sheetId = id; sheetFromResume = true }
                wideRow(R.string.home_next_up, "next", state.nextUp, session, strings, state, banner, actions, wide, gaps) { id -> sheetId = id; sheetFromResume = false }
                state.latest.forEach { latest ->
                    posterRow(latest.view, latest.row, session, strings, state, banner, actions, wide, gaps) { id -> sheetId = id; sheetFromResume = false }
                }
                if (state.views.isNotEmpty()) librariesSection(state, actions, strings, wide, gaps)
            }
        }
    }

    val target = sheetId?.let { id -> findEntry(state, id) }?.let { entry ->
        SheetTarget(
            itemId = entry.item.id, title = entry.item.sheetTitle(strings), played = entry.userData?.played == true,
            favourite = entry.userData?.favourite == true, canRemoveFromResume = sheetFromResume,
            seriesId = entry.item.seriesId.takeIf { entry.item.kind == ItemKind.EPISODE },
        )
    }
    if (target != null) {
        ItemActionsSheet(
            target,
            SheetActions(
                onRemoveFromResume = actions.onRemoveFromResume,
                onSetPlayed = actions.onSetPlayed,
                onSetFavourite = actions.onSetFavourite,
                onGoToSeries = { actions.onOpen(Destination.Series(it)) },
                onDetails = { id -> findEntry(state, id)?.let { actions.onOpen(it.detailsDestination()) } },
            ),
            onDismiss = { sheetId = null },
        )
    }
}

private fun findEntry(state: HomeUiState, id: String): ItemWithUserData? =
    (state.resume.items + state.nextUp.items + state.latest.flatMap { it.row.items }).firstOrNull { it.item.id == id }

@Composable
private fun CertificateNotice(connection: ConnectionView, onReview: () -> Unit) {
    NoticeCard(
        title = stringResource(if (connection.certificateChanged) R.string.cert_changed_title else R.string.cert_untrusted_alert_title),
        body = stringResource(if (connection.certificateChanged) R.string.cert_changed_body else R.string.cert_untrusted_alert_body),
        tone = Tone.Danger,
        modifier = Modifier.padding(horizontal = Gutter),
    ) { PrimaryButton(stringResource(R.string.cert_review), onReview) }
}


