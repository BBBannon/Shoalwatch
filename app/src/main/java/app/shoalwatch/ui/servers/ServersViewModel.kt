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
import androidx.compose.foundation.text.input.TextFieldState
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.security.Household
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.ui.lock.GrownUpAsk
import app.shoalwatch.ui.lock.GrownUpFlow
import app.shoalwatch.ui.lock.SwitchFlow
import app.shoalwatch.ui.lock.SwitchPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * [sync]: this profile's changes waiting to reach the server (the line under it). [expired]: the server rejected its sign-in; it
 * is listed with "Sign in again" so its waiting changes can still be sent, but it cannot be made active (Tasks 9-10 review I2).
 */
data class AccountItem(
    val userId: String, val userName: String, val isActive: Boolean, val sync: SyncStatus = SyncStatus.NONE, val expired: Boolean = false,
    /** Protected by a PIN: shown with a lock and "Protected". */
    val isProtected: Boolean = false,
)

/** One profile's changes not yet sent, for the Remove server dialog (a count and the profile's name; never an item). */
data class ProfileWaiting(val userName: String, val waiting: Int, val expired: Boolean)

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
    /**
     * [sharing]: the server shares downloads between profiles (the dialog then says shared ones stay for the others). [waiting]: this
     * profile's changes not yet on the server (the dialog says they are sent first, and any left are deleted).
     */
    data class SignOut(
        val serverId: String, val userId: String, val userName: String, val serverName: String, val sharing: Boolean = false, val waiting: Int = 0,
    ) : Confirm

    /**
     * [accountNames] are the sign-ins that will go with the server (expired ones too); [waiting] counts every one of their changes
     * not yet sent, and [profiles] says whose (only profiles with some; an expired one's cannot be sent first).
     */
    data class RemoveServer(
        val serverId: String, val serverName: String, val accountNames: List<String>, val waiting: Int = 0, val profiles: List<ProfileWaiting> = emptyList(),
    ) : Confirm
}

/** Said after a sign-out or removal that worked on this phone but could not reach the server to revoke the sign-in. */
enum class ServersNotice { SignedOutHereOnly, RemovedHereOnly }

data class ServersUiState(
    val loading: Boolean = true,
    val servers: List<ServerItem> = emptyList(),
    val confirm: Confirm? = null,
    val busy: Boolean = false,
    /** Sending the waiting changes before a sign-out or removal (at most 10 s): the confirm button shows a spinner. */
    val sending: Boolean = false,
    val error: String? = null,
    val notice: ServersNotice? = null,
    /** The server whose card shows "Server admin": the active account's, only while its server confirms it is an administrator. */
    val adminServerId: String? = null,
    /** A grown-up's PIN was given for Settings: the route opens Connection settings for this server, once. */
    val openSettings: String? = null,
    /** The data layer refused a household change (a race with the check): "A grown-up's PIN is needed. Try again." */
    val householdRace: Boolean = false,
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
    /** The only way this screen changes the active profile (null: rows switch nothing). */
    switcher: ProfileSwitcher? = null,
    /** The grown-up check before Settings, Remove server and signing out another protected profile (null: never asks). */
    household: Household? = null,
    /** The protected profiles (the rows' "Protected" marks). */
    protection: Flow<Set<Session>> = flowOf(emptySet()),
) : ViewModel() {
    private data class Local(
        val confirm: Confirm? = null, val busy: Boolean = false, val sending: Boolean = false, val error: String? = null, val notice: ServersNotice? = null,
        val openSettings: String? = null, val householdRace: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val switching = switcher?.let { SwitchFlow(it, viewModelScope) { e -> local.update { l -> l.copy(error = e.userMessage) } } }
    private val grownUp = household?.let { GrownUpFlow(it, viewModelScope) }

    val switchPrompt: StateFlow<SwitchPrompt?> = switching?.prompt ?: MutableStateFlow(null)
    val switchPin: TextFieldState = switching?.pin ?: TextFieldState()
    val grownUpAsk: StateFlow<GrownUpAsk?> = grownUp?.ask ?: MutableStateFlow(null)
    val grownUpPin: TextFieldState = grownUp?.pin ?: TextFieldState()

    private val rows: Flow<List<Pair<ServerSummary, List<Triple<AccountSummary, Boolean, SyncStatus>>>>> = catalog.servers.flatMapLatest { servers ->
        if (servers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(servers.map { withSync(usableThenExpired(it.id)) }) { lists -> servers.zip(lists.toList()) }
        }
    }

    /** The server's usable accounts, then those whose sign-in expired (true = expired). */
    private fun usableThenExpired(serverId: String): Flow<List<Pair<AccountSummary, Boolean>>> =
        combine(catalog.accounts(serverId), catalog.expiredAccounts(serverId)) { ok, expired -> ok.map { it to false } + expired.map { it to true } }

    /** Each account with its own session's sync status (read as that session only). */
    private fun withSync(accounts: Flow<List<Pair<AccountSummary, Boolean>>>): Flow<List<Triple<AccountSummary, Boolean, SyncStatus>>> = accounts.flatMapLatest { list ->
        if (sync == null || list.isEmpty()) {
            flowOf(list.map { (a, expired) -> Triple(a, expired, SyncStatus.NONE) })
        } else {
            combine(list.map { (a, expired) -> sync.status(Session(a.serverId, a.userId)).map { Triple(a, expired, it) } }) { it.toList() }
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
        combine(rows, catalog.activeAccount, local, adminServer, protection) { rows, active, local, adminServerId, protectedSet ->
            ServersUiState(
                loading = false,
                servers = rows.map { (server, accounts) ->
                    ServerItem(
                        id = server.id, name = server.name, version = server.version,
                        certificateChanged = server.pinInvalid,
                        accounts = accounts.map { (it, expired, synced) ->
                            AccountItem(
                                it.userId, it.userName, !expired && it.serverId == active?.serverId && it.userId == active.userId, synced, expired,
                                isProtected = Session(it.serverId, it.userId) in protectedSet,
                            )
                        },
                    )
                },
                confirm = local.confirm, busy = local.busy, sending = local.sending, error = local.error, notice = local.notice,
                adminServerId = adminServerId, openSettings = local.openSettings, householdRace = local.householdRace,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServersUiState())

    /** Only a usable account (an expired one is signed in again first), through the switcher: a protected one asks its PIN. */
    fun switchTo(serverId: String, userId: String) {
        val account = state.value.servers.firstOrNull { it.id == serverId }?.accounts?.firstOrNull { it.userId == userId } ?: return
        if (account.expired || account.isActive) return
        switching?.request(serverId, userId, account.userName)
    }

    fun submitSwitch() { switching?.submit() }
    fun dismissSwitch() { switching?.dismiss() }

    /** Connection settings are a household setting: a grown-up's PIN first, then the route opens them ([ServersUiState.openSettings]). */
    fun openSettings(serverId: String) = guard { local.update { it.copy(openSettings = serverId) } }
    fun settingsOpened() = local.update { it.copy(openSettings = null) }

    fun chooseGrownUp(who: AccountSummary) { grownUp?.choose(who) }
    fun submitGrownUp() { grownUp?.submit() }
    fun dismissGrownUp() { grownUp?.dismiss() }

    private fun guard(then: () -> Unit) {
        local.update { it.copy(householdRace = false) }
        grownUp?.guard(then) ?: then()
    }

    /** Signing out a protected profile that is not active asks for that profile's own PIN first; any other asks nothing. */
    fun askSignOut(serverId: String, userId: String) {
        val server = state.value.servers.firstOrNull { it.id == serverId } ?: return
        val account = server.accounts.firstOrNull { it.userId == userId } ?: return
        val flow = grownUp
        if (account.isProtected && !account.isActive && flow != null) {
            flow.guardSignOut(AccountSummary(serverId, userId, account.userName)) { askSignOutNow(serverId, userId) }
        } else {
            askSignOutNow(serverId, userId)
        }
    }

    private fun askSignOutNow(serverId: String, userId: String) {
        val server = state.value.servers.firstOrNull { it.id == serverId } ?: return
        val account = server.accounts.firstOrNull { it.userId == userId } ?: return
        val ask = Confirm.SignOut(serverId, userId, account.userName, server.name)
        local.update { it.copy(confirm = ask, error = null, notice = null) }
        viewModelScope.launch {
            val on = try { sharing(serverId).first() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            val waiting = sync?.waiting(Session(serverId, userId)) ?: 0
            local.update { if (it.confirm == ask) it.copy(confirm = ask.copy(sharing = on, waiting = waiting)) else it }
        }
    }

    /** Removing a server is a household setting: a grown-up's PIN first. */
    fun askRemoveServer(serverId: String) = guard { askRemoveServerNow(serverId) }

    private fun askRemoveServerNow(serverId: String) {
        val server = state.value.servers.firstOrNull { it.id == serverId } ?: return
        val ask = Confirm.RemoveServer(serverId, server.name, server.accounts.map { a -> a.userName })
        local.update { it.copy(confirm = ask, error = null, notice = null) }
        val sync = sync ?: return
        viewModelScope.launch {
            val profiles = server.accounts.map { ProfileWaiting(it.userName, sync.waiting(Session(serverId, it.userId)), it.expired) }
            val waiting = profiles.sumOf { it.waiting }
            local.update { if (it.confirm == ask) it.copy(confirm = ask.copy(waiting = waiting, profiles = profiles.filter { p -> p.waiting > 0 })) else it }
        }
    }

    /** Ignored while the confirmed action runs: it happens anyway, so the dialog stays until it has (Tasks 9-10 review M6). */
    fun dismissConfirm() = local.update { if (it.busy) it else it.copy(confirm = null) }
    fun dismissError() = local.update { it.copy(error = null) }
    fun dismissNotice() = local.update { it.copy(notice = null) }

    /** Carries out the action the user confirmed. */
    fun confirm() {
        val pending = local.value.confirm ?: return
        if (local.value.busy) return // a second tap must not run it twice
        val server = state.value.servers.firstOrNull { it.id == (pending as? Confirm.RemoveServer)?.serverId }
        run {
            when (pending) {
                is Confirm.SignOut -> afterSending(listOf(Session(pending.serverId, pending.userId))) {
                    ServersNotice.SignedOutHereOnly.takeUnless { auth.signOut(pending.serverId, pending.userId) }
                }
                // An expired profile cannot send: only the usable ones are tried first (the dialog says so).
                is Confirm.RemoveServer -> afterSending(server?.accounts.orEmpty().filterNot { it.expired }.map { Session(pending.serverId, it.userId) }) {
                    ServersNotice.RemovedHereOnly.takeUnless { auth.removeServer(pending.serverId) }
                }
            }
        }
    }

    /**
     * Sends what [sessions] have waiting, each as itself and all in parallel, for [SEND_FIRST_MS] in all, then runs [then] whatever
     * happened (whatever is still waiting goes with the account). If this screen goes away while sending, [then] still runs: a
     * confirmed sign-out always happens.
     */
    private suspend fun afterSending(sessions: List<Session>, then: suspend () -> ServersNotice?): ServersNotice? {
        val sync = sync
        if (sync != null && sessions.isNotEmpty()) {
            local.update { it.copy(sending = true) }
            try {
                withTimeoutOrNull(SEND_FIRST_MS) {
                    coroutineScope { sessions.map { s -> async { sendQuietly(sync, s) } }.awaitAll() }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { then() }
                throw e
            } finally {
                local.update { it.copy(sending = false) }
            }
        }
        return then()
    }

    private suspend fun sendQuietly(sync: Sync, s: Session) {
        try {
            sync.sendNow(s, SEND_FIRST_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The seam never throws; whatever is left goes with the account.
        }
    }

    private companion object {
        /** How long a sign-out or removal waits for the changes to be sent first. */
        const val SEND_FIRST_MS = 10_000L
    }

    /** Runs [action]; what it returns is shown as the notice. */
    private fun run(action: suspend () -> ServersNotice?) {
        local.update { it.copy(busy = true, error = null, notice = null, householdRace = false) }
        viewModelScope.launch {
            var notice: ServersNotice? = null
            var race = false
            val failure = try {
                notice = action()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: HouseholdLockedException) {
                race = true // refused below the screen: nothing changed
                null
            } catch (e: Exception) {
                AppError.from(e).userMessage
            }
            local.update { it.copy(busy = false, confirm = null, error = failure, notice = notice, householdRace = race) }
        }
    }
}
