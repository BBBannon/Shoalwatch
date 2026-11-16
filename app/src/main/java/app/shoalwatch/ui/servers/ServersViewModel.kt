package app.shoalwatch.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.ServerSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.Sync
import app.shoalwatch.data.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [sync]: this profile's changes waiting to reach the server (the line under it). */
data class AccountItem(val userId: String, val userName: String, val isActive: Boolean, val sync: SyncStatus = SyncStatus.NONE)

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
    /** The server whose card shows "Server admin": the active account's, only while its server confirms it is an administrator. */
    val adminServerId: String? = null,
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
    /** Who may see "Server admin" (Milestone 6); null shows it nowhere. */
    private val rights: AdminRights? = null,
    /** Each profile's waiting changes, sent first on sign-out and removal; null shows no line and sends nothing. */
    private val sync: Sync? = null,
) : ViewModel() {
    private data class Local(val confirm: Confirm? = null, val busy: Boolean = false, val error: String? = null, val notice: ServersNotice? = null)

    private val local = MutableStateFlow(Local())

    private val rows: Flow<List<Pair<ServerSummary, List<Pair<AccountSummary, SyncStatus>>>>> = catalog.servers.flatMapLatest { servers ->
        if (servers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(servers.map { withSync(catalog.accounts(it.id)) }) { lists -> servers.zip(lists.toList()) }
        }
    }

    /** Each account with its own session's sync status (read as that session only). */
    private fun withSync(accounts: Flow<List<AccountSummary>>): Flow<List<Pair<AccountSummary, SyncStatus>>> = accounts.flatMapLatest { list ->
        if (sync == null || list.isEmpty()) {
            flowOf(list.map { it to SyncStatus.NONE })
        } else {
            combine(list.map { a -> sync.status(Session(a.serverId, a.userId)).map { a to it } }) { it.toList() }
        }
    }

    /** The active session's server id while `rights` says exactly (that session, ADMIN); null at once on any switch. */
    private val adminServer: Flow<String?> = if (rights == null) flowOf(null) else catalog.activeSession().flatMapLatest { session ->
        if (session == null) flowOf(null) else rights.state.map { held -> session.serverId.takeIf { held == AdminState(session, AdminStatus.ADMIN) } }
    }

    init {
        // Ask the server once for each active session; a switch cancels the question for the one before.
        if (rights != null) viewModelScope.launch { catalog.activeSession().collectLatest { s -> if (s != null) rights.check(s) } }
    }

    val state: StateFlow<ServersUiState> =
        combine(rows, catalog.activeAccount, local, adminServer) { rows, active, local, adminServerId ->
            ServersUiState(
                loading = false,
                servers = rows.map { (server, accounts) ->
                    ServerItem(
                        id = server.id, name = server.name, version = server.version,
                        certificateChanged = server.pinInvalid,
                        accounts = accounts.map { (it, synced) -> AccountItem(it.userId, it.userName, it.serverId == active?.serverId && it.userId == active.userId, synced) },
                    )
                },
                confirm = local.confirm, busy = local.busy, error = local.error, notice = local.notice,
                adminServerId = adminServerId,
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
