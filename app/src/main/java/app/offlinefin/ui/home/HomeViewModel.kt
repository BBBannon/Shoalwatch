package app.offlinefin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.ServerConnections
import app.offlinefin.ui.ConnectionView
import app.offlinefin.ui.toView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val loading: Boolean = true,
    /** No active account: the screen offers the servers list instead. */
    val signedOut: Boolean = false,
    val serverId: String? = null,
    val serverName: String? = null,
    val userName: String? = null,
    val connection: ConnectionView? = null,
)

/** The active server, user and live connection. Pulls nothing from the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    catalog: ServerCatalog,
    private val connections: ServerConnections,
) : ViewModel() {
    val state: StateFlow<HomeUiState> =
        combine(catalog.activeAccount, catalog.servers) { account, servers ->
            account?.let { a -> servers.firstOrNull { it.id == a.serverId }?.let { it to a } }
        }.flatMapLatest { active ->
            if (active == null) {
                flowOf(HomeUiState(loading = false, signedOut = true))
            } else {
                val (server, account) = active
                connections.connection(server).map {
                    HomeUiState(false, false, server.id, server.name, account.userName, it.toView())
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun retry() = connections.refresh()
}
