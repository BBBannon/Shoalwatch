package app.shoalwatch.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.ServerSummary
import app.shoalwatch.data.servers.ServerCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountItem(val userId: String, val userName: String, val isActive: Boolean)

data class ServerItem(
    val id: String,
    val name: String,
    val version: String,
    /** The saved certificate pin was damaged: the user must review the certificate again. */
    val certificateChanged: Boolean,
    val accounts: List<AccountItem>,
)

/** A destructive action waiting for the user's explicit confirmation. */
sealed interface Confirm {
    /** [sharing]: the server shares downloads between profiles (the dialog then says shared ones stay for the others). */
    data class SignOut(val serverId: String, val userId: String, val userName: String, val serverName: String, val sharing: Boolean = false) : Confirm

    /** [accountNames] are the sign-ins that will go with the server. */
    data class RemoveServer(val serverId: String, val serverName: String, val accountNames: List<String>) : Confirm
}

/** Said after a sign-out or removal that worked on this phone but could not reach the server to revoke the sign-in. */
enum class ServersNotice { SignedOutHereOnly, RemovedHereOnly }

data class ServersUiState(
    val loading: Boolean = true,
    val servers: List<ServerItem> = emptyList(),
    val confirm: Confirm? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: ServersNotice? = null,
)

/**
 * Servers and their accounts: switch the active account, sign out, remove a server (both after confirming).
 * Signing out and removing always succeed on this phone; if the server could not be reached to revoke the
 * sign-in there too, [ServersUiState.notice] says so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModel(
    private val catalog: ServerCatalog,
    private val auth: Authenticator,
    /** "Share downloads between profiles" for a server (the sign-out dialog's second line). */
    private val sharing: (String) -> Flow<Boolean> = { flowOf(false) },
) : ViewModel() {
    private data class Local(val confirm: Confirm? = null, val busy: Boolean = false, val error: String? = null, val notice: ServersNotice? = null)

    private val local = MutableStateFlow(Local())

    private val rows: Flow<List<Pair<ServerSummary, List<AccountSummary>>>> = catalog.servers.flatMapLatest { servers ->
        if (servers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(servers.map { catalog.accounts(it.id) }) { lists -> servers.zip(lists.toList()) }
        }
    }

    val state: StateFlow<ServersUiState> =
        combine(rows, catalog.activeAccount, local) { rows, active, local ->
            ServersUiState(
                loading = false,
                servers = rows.map { (server, accounts) ->
                    ServerItem(
                        id = server.id, name = server.name, version = server.version,
                        certificateChanged = server.pinInvalid,
                        accounts = accounts.map { AccountItem(it.userId, it.userName, it.serverId == active?.serverId && it.userId == active.userId) },
                    )
                },
                confirm = local.confirm, busy = local.busy, error = local.error, notice = local.notice,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServersUiState())

    fun switchTo(serverId: String, userId: String) = run { catalog.setActive(serverId, userId); null }

    fun askSignOut(serverId: String, userId: String) {
        val server = state.value.servers.firstOrNull { it.id == serverId } ?: return
        val account = server.accounts.firstOrNull { it.userId == userId } ?: return
        val ask = Confirm.SignOut(serverId, userId, account.userName, server.name)
        local.update { it.copy(confirm = ask, error = null, notice = null) }
        viewModelScope.launch {
            val on = try { sharing(serverId).first() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            local.update { if (it.confirm == ask) it.copy(confirm = ask.copy(sharing = on)) else it }
        }
    }

    fun askRemoveServer(serverId: String) {
        val server = state.value.servers.firstOrNull { it.id == serverId } ?: return
        local.update { it.copy(confirm = Confirm.RemoveServer(serverId, server.name, server.accounts.map { a -> a.userName }), error = null, notice = null) }
    }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }
    fun dismissError() = local.update { it.copy(error = null) }
    fun dismissNotice() = local.update { it.copy(notice = null) }

    /** Carries out the action the user confirmed. */
    fun confirm() {
        val pending = local.value.confirm ?: return
        if (local.value.busy) return // a second tap must not run it twice
        run {
            when (pending) {
                is Confirm.SignOut -> ServersNotice.SignedOutHereOnly.takeUnless { auth.signOut(pending.serverId, pending.userId) }
                is Confirm.RemoveServer -> ServersNotice.RemovedHereOnly.takeUnless { auth.removeServer(pending.serverId) }
            }
        }
    }

    /** Runs [action]; what it returns is shown as the notice. */
    private fun run(action: suspend () -> ServersNotice?) {
        local.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            var notice: ServersNotice? = null
            val failure = try {
                notice = action()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppError.from(e).userMessage
            }
            local.update { it.copy(busy = false, confirm = null, error = failure, notice = notice) }
        }
    }
}
