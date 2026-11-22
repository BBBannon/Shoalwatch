package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.CertificateInfo
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.HouseholdGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Small interfaces over the concrete data layer, so ViewModels can be unit-tested with fakes, and so the UI
 * layer never sees an access token: these types carry no credential. Each interface has one production
 * implementation, named in its KDoc. Anything that acts on a server's security settings takes its id, never a
 * `Server`: the settings are always read from the store at the moment of use.
 */

/** A signed-in user as the UI sees them: no access token. */
data class AccountSummary(val serverId: String, val userId: String, val userName: String) {
    override fun toString() = "AccountSummary(<redacted>)"
}

/**
 * The part of [ServerStore] the UI uses, without tokens. Implemented by [StoreCatalog]. Removing a server is
 * [Authenticator.removeServer]. [servers], [activeAccount], [accounts] and [awaitActive] read no secret column, so
 * they never touch the Keystore; [server] and [observeServer] open the custom headers (off the main thread).
 */
interface ServerCatalog {
    val servers: Flow<List<ServerSummary>>
    val activeAccount: Flow<AccountSummary?>

    /** Who requests are made as: the active account, token-free. Waits for the token warm like [activeAccount]. */
    fun activeSession(): Flow<Session?>
    fun accounts(serverId: String): Flow<List<AccountSummary>>

    /**
     * The server's accounts whose sign-in the server rejected (a 401): kept, with their waiting changes, until signed in again,
     * but never active or usable (Tasks 9-10 review I2). Token-free like [accounts].
     */
    fun expiredAccounts(serverId: String): Flow<List<AccountSummary>> = flowOf(emptyList())
    suspend fun server(id: String): Server?

    /** The saved custom-header values of [serverId]: the only way they leave the catalog (review M-4). @throws HouseholdLockedException */
    suspend fun headerValues(serverId: String): Map<String, String>
    fun observeServer(id: String): Flow<Server?>
    suspend fun upsertServer(s: Server)
    suspend fun setActive(serverId: String, userId: String)

    /** Completes once saved sign-ins have been loaded (see [ServerStore.awaitWarmed]). */
    suspend fun awaitWarmed()

    /**
     * Tries the token warm once more if the start-up one failed (see [ServerStore.retryWarmIfFailed]).
     * Returns true if sign-ins are still unavailable.
     */
    suspend fun retryWarmIfFailed(): Boolean
    suspend fun awaitActive(): AccountSummary?

    /**
     * True while a read of [servers], [activeAccount], [accounts] or [observeServer] has failed (a damaged or full database):
     * those flows then show nothing (nobody active, empty lists, no server) instead of throwing into their collectors, and
     * [retryWarmIfFailed] subscribes them again. A nobody caused by this is not a sign-out (see `ClearBrowsingOnSessionChange`).
     */
    val readFailed: StateFlow<Boolean>
}

/** Quick Connect progress for the UI; [Success] carries no token. */
sealed interface QuickConnectProgress {
    data object Starting : QuickConnectProgress
    data class AwaitingApproval(val code: String) : QuickConnectProgress {
        override fun toString() = "AwaitingApproval(<redacted>)"
    }
    data object Authenticating : QuickConnectProgress
    data class Success(val account: AccountSummary) : QuickConnectProgress
    data object TimedOut : QuickConnectProgress
    data class Failed(val error: AppError) : QuickConnectProgress
}

/**
 * Where a sign-in will be sent, chosen by `AuthRepository` (see its KDoc): the UI cannot see or choose the
 * address, and `AuthRepository` refuses any route it did not issue. [encrypted] is false when the credential
 * would go over plain http, so the screen can say so before it is sent.
 */
interface SignInRoute {
    val serverId: String
    val encrypted: Boolean
}

/** The part of [AuthRepository] the UI uses, without tokens. Implemented by [AuthenticatorImpl]. */
interface Authenticator {
    suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo

    /**
     * Null when no address of the server is verified right now. Ask again before every attempt. [expectedUserId]: "Sign in
     * again" for that profile, so signing in as anyone else fails with `WRONG_ACCOUNT` and changes no active profile.
     */
    suspend fun signInRoute(serverId: String, expectedUserId: String? = null): SignInRoute?
    fun quickConnect(route: SignInRoute): Flow<QuickConnectProgress>
    suspend fun signInWithPassword(route: SignInRoute, username: String, password: CharArray): AccountSummary

    /** Revokes on the server when it can, removes here always. False if a sign-in may still be live on the server. */
    suspend fun signOut(serverId: String, userId: String): Boolean

    /** Revokes every sign-in on the server when it can, then removes the server here always. False if any may still be live. */
    suspend fun removeServer(serverId: String): Boolean
}

/** The part of [AddressSelector] the UI uses. */
interface ServerConnections {
    fun connection(serverId: String): StateFlow<Connection>
    suspend fun awaitOnline(serverId: String, timeoutMs: Long = AddressSelector.AWAIT_TIMEOUT_MS): Connection.Online?
    fun refresh()

    /**
     * Re-probes [serverId] alone, now: it goes to `Checking` at once, so a following [awaitOnline] waits for the result of this
     * probe instead of answering from an `Offline` that may be out of date. This is what a user's own refresh asks for.
     */
    fun refresh(serverId: String)

    /**
     * [refresh] for [serverId], but only if it is `Offline` right now: what a user's own refresh asks for first. A server whose
     * connection nobody has asked for (or one that has been removed) is not probed, and no probing is started for it.
     */
    fun refreshIfOffline(serverId: String) {
        if (connection(serverId).value is Connection.Offline) refresh(serverId)
    }

    /**
     * Re-probes [serverId] alone, but only if [failed] (the very `Online` instance a request used, as returned by
     * [awaitOnline]) is still its current state. A failure that arrives after the state has already moved on (a newer
     * probe, or `Checking`) says nothing about the new state and must not discard it.
     */
    fun refreshIfCurrent(serverId: String, failed: Connection.Online)
}

/** Reads the certificate a server presents (see `HttpClientFactory.probeCertificate`); null for plain http. */
fun interface CertificateProber {
    suspend fun probe(address: ServerAddress): CertificateInfo?
}

private fun Account.summary() = AccountSummary(serverId, userId, userName)

/** What [StoreCatalog] shows instead of a custom-header value: a control character, which no saved value can hold (`validateCustomHeader`). */
val HIDDEN_HEADER_VALUE: String = Char(1).toString()

/**
 * [setActive] refuses a protected profile ([isProtected]; only `ProfileSwitch` makes one active, after its PIN), and editing a
 * saved server needs the household check ([household]). The defaults keep tests that need neither unchanged.
 */
class StoreCatalog(
    private val store: ServerStore,
    private val isProtected: suspend (Session) -> Boolean = { false },
    private val household: suspend () -> HouseholdGuard? = { null },
) : ServerCatalog {
    override val servers = store.summaries
    override val activeAccount = store.activeAccount
    override fun activeSession(): Flow<Session?> = store.activeAccount.map { a -> a?.let { Session(it.serverId, it.userId) } }.distinctUntilChanged()
    override fun accounts(serverId: String) = store.accounts(serverId)
    override fun expiredAccounts(serverId: String) = store.accounts(serverId, expired = true)
    /** Header values are hidden here ([HIDDEN_HEADER_VALUE], names kept): they come out only through [headerValues] (review M-4). */
    override suspend fun server(id: String) = store.server(id)?.withHiddenHeaders()
    override fun observeServer(id: String) = store.observeServer(id).map { it?.withHiddenHeaders() }

    override suspend fun headerValues(serverId: String): Map<String, String> {
        household()?.require()
        return store.server(serverId)?.customHeaders.orEmpty()
    }

    /** A hidden value (a server read here and saved back) keeps the saved one; a hidden name with none saved is dropped. */
    override suspend fun upsertServer(s: Server) {
        val saved = store.server(s.id)
        if (saved != null) household()?.require()
        store.upsertServer(s.copy(customHeaders = s.customHeaders.mapNotNull { (name, value) ->
            if (value != HIDDEN_HEADER_VALUE) name to value else saved?.customHeaders?.get(name)?.let { name to it }
        }.toMap()))
    }

    private fun Server.withHiddenHeaders() = copy(customHeaders = customHeaders.mapValues { HIDDEN_HEADER_VALUE })

    /** @throws ProfileProtectedException for a protected profile; nothing is set. */
    override suspend fun setActive(serverId: String, userId: String) {
        if (isProtected(Session(serverId, userId))) throw ProfileProtectedException()
        store.setActive(serverId, userId)
    }
    override suspend fun awaitWarmed() = store.awaitWarmed()
    override suspend fun retryWarmIfFailed() = store.retryWarmIfFailed()
    override suspend fun awaitActive() = store.awaitActive()
    override val readFailed = store.readFailed
}

/**
 * Removing a server, and signing out a protected profile that is not active, need the household check ([household]); removing a
 * server also needs the own PIN of each protected profile on it that is not the active one (final review M5).
 */
class AuthenticatorImpl(
    private val repository: AuthRepository,
    private val household: suspend () -> HouseholdGuard? = { null },
) : Authenticator {
    override suspend fun findServer(input: String, draft: ServerNetDraft) = repository.findServer(input, draft)

    override suspend fun signInRoute(serverId: String, expectedUserId: String?) = repository.signInRoute(serverId, expectedUserId)

    override fun quickConnect(route: SignInRoute): Flow<QuickConnectProgress> =
        repository.quickConnect(route).map {
            when (it) {
                QuickConnectState.Starting -> QuickConnectProgress.Starting
                is QuickConnectState.AwaitingApproval -> QuickConnectProgress.AwaitingApproval(it.code)
                QuickConnectState.Authenticating -> QuickConnectProgress.Authenticating
                is QuickConnectState.Success -> QuickConnectProgress.Success(it.account.summary())
                QuickConnectState.TimedOut -> QuickConnectProgress.TimedOut
                is QuickConnectState.Failed -> QuickConnectProgress.Failed(it.error)
            }
        }

    override suspend fun signInWithPassword(route: SignInRoute, username: String, password: CharArray) =
        repository.signInWithPassword(route, username, password).summary()

    /** @throws app.shoalwatch.security.HouseholdLockedException before anything is sent or removed. */
    override suspend fun signOut(serverId: String, userId: String): Boolean {
        household()?.requireSignOut(Session(serverId, userId))
        return repository.signOut(serverId, userId)
    }

    /** @throws app.shoalwatch.security.HouseholdLockedException before anything is sent or removed. */
    override suspend fun removeServer(serverId: String): Boolean {
        household()?.requireRemoveServer(serverId)
        return repository.removeServer(serverId)
    }
}
