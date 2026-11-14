package app.offlinefin.data.servers

import app.offlinefin.net.CertificateInfo
import app.offlinefin.net.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Small interfaces over the concrete data layer, so ViewModels can be unit-tested with fakes. Each is
 * implemented by exactly one production class (named in its KDoc); nothing else lives here.
 */

/** The part of [ServerStore] the UI uses. */
interface ServerCatalog {
    val servers: Flow<List<Server>>
    val activeAccount: Flow<Account?>
    fun accounts(serverId: String): Flow<List<Account>>
    suspend fun server(id: String): Server?
    suspend fun upsertServer(s: Server)
    suspend fun removeServer(id: String)
    suspend fun setActive(serverId: String, userId: String)

    /** Completes once saved sign-ins have been loaded (see [ServerStore.awaitWarmed]). */
    suspend fun awaitWarmed()
    suspend fun awaitActive(): Pair<Server, Account>?
}

/** The part of [AuthRepository] the UI uses. */
interface Authenticator {
    suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo
    fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectState>
    suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray): Account
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
