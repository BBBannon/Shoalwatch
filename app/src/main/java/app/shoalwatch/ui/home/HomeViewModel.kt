package app.shoalwatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.toView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    /** No active account: the screen offers the servers list instead. */
    val signedOut: Boolean = false,
    val serverId: String? = null,
    val serverName: String? = null,
    val userName: String? = null,
    val connection: ConnectionView? = null,
    /** Saved sign-ins could not be loaded (even after a retry): shown with a Retry instead of a silent signed-out state. */
    val loadFailed: Boolean = false,
)

/**
 * The active server, user and live connection. Pulls nothing from the server. If loading the saved sign-ins
 * failed at start-up it retries once when Home opens (and again on every Retry); if it still fails, that is
 * shown rather than a silent signed-out state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val catalog: ServerCatalog,
    private val connections: ServerConnections,
) : ViewModel() {
    private val warmFailed = MutableStateFlow(false)

    val state: StateFlow<HomeUiState> =
        combine(
            combine(catalog.activeAccount, catalog.servers) { account, servers ->
                account?.let { a -> servers.firstOrNull { it.id == a.serverId }?.let { it to a } }
            }.flatMapLatest { active ->
                if (active == null) {
                    flowOf(HomeUiState(loading = false, signedOut = true))
                } else {
                    val (server, account) = active
                    connections.connection(server.id).map {
                        HomeUiState(false, false, server.id, server.name, account.userName, it.toView())
                    }
                }
            },
            warmFailed,
        ) { home, failed -> home.copy(loadFailed = failed) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        retryWarm()
    }

    /** The connection chip's retry: another go at loading sign-ins, then a fresh probe of the server. */
    fun retry() {
        retryWarm()
        connections.refresh()
    }

    private fun retryWarm() {
        viewModelScope.launch { warmFailed.value = catalog.retryWarmIfFailed() }
    }
}
