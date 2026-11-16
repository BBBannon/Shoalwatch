package app.shoalwatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.HomeRow
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.browse.FAILED_LOCALLY
import app.shoalwatch.ui.browse.ItemActions
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.connectionRegained
import app.shoalwatch.ui.browse.forSession
import app.shoalwatch.ui.present.banner
import app.shoalwatch.ui.toView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home: the active account's Continue Watching, Next Up, latest-in-each-library and libraries, with the server name, a live
 * connection reading and the accounts of this server for the switcher.
 *
 * Everything is for the ACTIVE session and restarts when it changes: content read for another session is never shown (it is
 * dropped by comparing sessions, so even a late emission from the old flow cannot appear), and every action runs as the session
 * on screen at the time. A refresh is asked for when the user pulls or retries, when the connection to this server becomes
 * `Online` after being anything else, and, when [refreshOnOpen] is set (the Home reached by a successful sign-in), once on
 * opening. Collecting alone does not refresh: `Library` already refreshes what is due.
 *
 * If loading the saved sign-ins failed at start-up it retries once when Home opens (and on every connection Retry); if it still
 * fails that is shown rather than a silent signed-out state. The same failure state shows while a read of the saved servers and
 * sign-ins fails (`ServerCatalog.readFailed`); its Try again ([retry]) subscribes those reads again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val catalog: ServerCatalog,
    private val connections: ServerConnections,
    private val library: Library,
    playstate: Playstate,
    refreshOnOpen: Boolean = false,
) : ViewModel() {
    private val actions = ItemActions(playstate, viewModelScope, connections) { state.value.session }
    private val warmFailed = MutableStateFlow(false)

    /** Failure messages for a snackbar, each once. */
    val messages: Flow<String> = actions.failures

    private class Shell(
        val session: Session,
        val server: ServerSummary,
        val account: AccountSummary,
        val accounts: List<AccountSummary>,
        val connection: ConnectionView,
    )

    private val shell: Flow<Shell?> =
        combine(catalog.activeAccount, catalog.servers) { account, servers ->
            account?.let { a -> servers.firstOrNull { it.id == a.serverId }?.let { it to a } }
        }.flatMapLatest { active ->
            if (active == null) {
                flowOf(null)
            } else {
                val (server, account) = active
                combine(connections.connection(server.id), catalog.accounts(server.id)) { connection, accounts ->
                    Shell(Session(account.serverId, account.userId), server, account, accounts, connection.toView())
                }
            }
        }

    /** What [content] gives when reading Home failed locally: told apart by identity, so the screen shows one plain message. */
    private val failedHome = Home(emptyList(), FAILED_LOCALLY, HomeRow(emptyList(), FAILED_LOCALLY), HomeRow(emptyList(), FAILED_LOCALLY), emptyList())

    /** The library's Home for the active session; null until the new session's first answer, so nothing old lingers. */
    private val content: Flow<Pair<Session, Home>?> = catalog.activeSession().flatMapLatest { s ->
        if (s == null) {
            flowOf(null)
        } else {
            flow {
                emit(null)
                emitAll(actions.guard(s, library.home(s).map { s to it }) { s to failedHome })
            }
        }
    }

    val state: StateFlow<HomeUiState> =
        combine(shell, content, actions.working, combine(warmFailed, catalog.readFailed) { a, b -> a || b }, actions.pulling) { shell, content, working, failed, pulling ->
            val session = shell?.session
            build(shell, content?.takeIf { it.first == session }?.second, working.forSession(session), failed, session?.let { it in pulling } == true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        retryWarm()
        viewModelScope.launch {
            connectionRegained(catalog, connections).collect { s -> actions.attempt(s) { library.refresh(s, RefreshTarget.Home) } }
        }
        if (refreshOnOpen) {
            viewModelScope.launch {
                val s = catalog.activeSession().filterNotNull().first()
                actions.attempt(s) { library.refresh(s, RefreshTarget.Home) }
            }
        }
    }

    /** The connection chip's retry: another go at loading sign-ins, then a fresh probe of the server. */
    fun retry() {
        retryWarm()
        connections.refresh()
    }

    /** Pull-to-refresh and a banner's Retry: asks for Home again, always (it bypasses the failure cooldown). */
    fun refresh() {
        val s = state.value.session ?: return
        actions.pull(s) { library.refresh(s, RefreshTarget.Home) }
    }

    fun setPlayed(itemId: String, played: Boolean) {
        state.value.session?.let { actions.setPlayed(it, itemId, played) }
    }

    fun setFavourite(itemId: String, favourite: Boolean) {
        state.value.session?.let { actions.setFavourite(it, itemId, favourite) }
    }

    fun removeFromResume(itemId: String) {
        state.value.session?.let { actions.removeFromContinueWatching(it, itemId) }
    }

    /** Makes [userId] (one of [HomeUiState.accounts]) the active account; every screen then restarts as that user. */
    fun switchAccount(userId: String) {
        val serverId = state.value.serverId ?: return
        viewModelScope.launch { actions.attempt(state.value.session) { catalog.setActive(serverId, userId) } }
    }

    private fun retryWarm() {
        viewModelScope.launch { warmFailed.value = catalog.retryWarmIfFailed() }
    }

    private fun build(shell: Shell?, home: Home?, working: Set<Work>, failed: Boolean, pull: Boolean): HomeUiState {
        if (failed) return HomeUiState(phase = HomePhase.LOAD_FAILED)
        if (shell == null) return HomeUiState(phase = HomePhase.SIGNED_OUT)
        val base = HomeUiState(
            phase = HomePhase.LOADING, session = shell.session, serverId = shell.server.id, serverName = shell.server.name,
            userName = shell.account.userName, connection = shell.connection, accounts = shell.accounts, pulling = pull, working = working,
        )
        if (home == null) return base
        if (home === failedHome) return base.copy(phase = HomePhase.READY, localFailure = true)
        val freshness = listOf(home.resume.freshness, home.nextUp.freshness, home.viewsFreshness) + home.latest.map { it.second.freshness }
        // One banner when every failing part failed the same way; otherwise each failing row carries its own.
        val top = freshness.mapNotNull { (it as? Freshness.Stale)?.reason?.banner() }.distinct().singleOrNull()
        fun inline(f: Freshness) = if (top != null) null else (f as? Freshness.Stale)?.reason?.banner()
        fun row(items: List<ItemWithUserData>, f: Freshness) = RowUi(items, items.isEmpty() && f is Freshness.Refreshing, inline(f))
        return base.copy(
            phase = HomePhase.READY,
            views = home.views,
            viewsBanner = inline(home.viewsFreshness),
            resume = row(home.resume.items, home.resume.freshness),
            nextUp = row(home.nextUp.items, home.nextUp.freshness),
            latest = home.latest.map { (view, r) -> LatestUi(view, row(r.items, r.freshness)) },
            topBanner = top,
            refreshing = freshness.any { it is Freshness.Refreshing },
        )
    }
}
