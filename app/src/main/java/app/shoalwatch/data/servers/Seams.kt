package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.net.CertificateInfo
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/*
 * Small interfaces over the concrete data layer, so ViewModels can be unit-tested with fakes, and so the UI
 * layer never sees an access token: these types carry no credential. Each interface has one production
 * implementation, named in its KDoc.
 */

/** A signed-in user as the UI sees them: no access token. */
data class AccountSummary(val serverId: String, val userId: String, val userName: String) {
    override fun toString() = "AccountSummary(<redacted>)"
}

/** The part of [ServerStore] the UI uses, without tokens. Implemented by [StoreCatalog]. */
interface ServerCatalog {
    val servers: Flow<List<Server>>
    val activeAccount: Flow<AccountSummary?>
    fun accounts(serverId: String): Flow<List<AccountSummary>>
    suspend fun server(id: String): Server?
    suspend fun upsertServer(s: Server)
    suspend fun removeServer(id: String)
    suspend fun setActive(serverId: String, userId: String)

    /** Completes once saved sign-ins have been loaded (see [ServerStore.awaitWarmed]). */
    suspend fun awaitWarmed()

    /**
     * Tries the token warm once more if the start-up one failed (see [ServerStore.retryWarmIfFailed]).
     * Returns true if sign-ins are still unavailable.
     */
    suspend fun retryWarmIfFailed(): Boolean
    suspend fun awaitActive(): Pair<Server, AccountSummary>?
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

/** The part of [AuthRepository] the UI uses, without tokens. Implemented by [AuthenticatorImpl]. */
interface Authenticator {
    suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo
    fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectProgress>
    suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray): AccountSummary
    suspend fun signOut(serverId: String, userId: String)
}

/** The part of [AddressSelector] the UI uses. */
interface ServerConnections {
    fun connection(server: Server): StateFlow<Connection>
    suspend fun awaitOnline(server: Server, timeoutMs: Long = AddressSelector.AWAIT_TIMEOUT_MS): Connection.Online?
    fun refresh()
}

/** Reads the certificate a server presents (see `HttpClientFactory.probeCertificate`); null for plain http. */
fun interface CertificateProber {
    suspend fun probe(address: ServerAddress): CertificateInfo?
}

private fun Account.summary() = AccountSummary(serverId, userId, userName)

class StoreCatalog(private val store: ServerStore) : ServerCatalog {
    override val servers = store.servers
    override val activeAccount = store.activeAccount.map { it?.summary() }
    override fun accounts(serverId: String) = store.accounts(serverId).map { list -> list.map { it.summary() } }
    override suspend fun server(id: String) = store.server(id)
    override suspend fun upsertServer(s: Server) = store.upsertServer(s)
    override suspend fun removeServer(id: String) = store.removeServer(id)
    override suspend fun setActive(serverId: String, userId: String) = store.setActive(serverId, userId)
    override suspend fun awaitWarmed() = store.awaitWarmed()
    override suspend fun retryWarmIfFailed() = store.retryWarmIfFailed()
    override suspend fun awaitActive() = store.awaitActive()?.let { (server, account) -> server to account.summary() }
}

class AuthenticatorImpl(private val repository: AuthRepository) : Authenticator {
    override suspend fun findServer(input: String, draft: ServerNetDraft) = repository.findServer(input, draft)

    override fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectProgress> =
        repository.quickConnect(server, via).map {
            when (it) {
                QuickConnectState.Starting -> QuickConnectProgress.Starting
                is QuickConnectState.AwaitingApproval -> QuickConnectProgress.AwaitingApproval(it.code)
                QuickConnectState.Authenticating -> QuickConnectProgress.Authenticating
                is QuickConnectState.Success -> QuickConnectProgress.Success(it.account.summary())
                QuickConnectState.TimedOut -> QuickConnectProgress.TimedOut
                is QuickConnectState.Failed -> QuickConnectProgress.Failed(it.error)
            }
        }

    override suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray) =
        repository.signInWithPassword(server, via, username, password).summary()

    override suspend fun signOut(serverId: String, userId: String) = repository.signOut(serverId, userId)
}
