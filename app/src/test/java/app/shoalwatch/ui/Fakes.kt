package app.shoalwatch.ui

import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.QuickConnectProgress
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ReadHealth
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.data.servers.ServerSummary
import app.shoalwatch.data.servers.SignInRoute
import app.shoalwatch.data.servers.summary
import app.shoalwatch.net.CertificateInfo
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Instant
import java.util.Base64

/** Runs `viewModelScope` on a test dispatcher that starts coroutines eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = UnconfinedTestDispatcher()
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val PIN: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

fun address(text: String) = ServerAddress.parse(text)

fun testServer(
    id: String = "srv-1",
    home: String? = "http://192.168.1.10:8096",
    away: String? = "https://away.example",
    cleartext: Boolean = true,
    pin: String? = null,
    pinInvalid: Boolean = false,
    headers: Map<String, String> = emptyMap(),
    alias: String? = null,
) = Server(id, "Den", "10.10.3", home?.let(::address), away?.let(::address), cleartext, pin, alias, headers, pinInvalid)

fun serverInfo(
    at: String,
    id: String = "srv-1",
    version: String = "10.10.3",
    compat: ServerInfo.Compatibility = ServerInfo.compatibilityOf(version),
) = ServerInfo(id, "Den", version, address(at), compat)

fun certificate() = CertificateInfo("CN=den.example", "CN=den.example", Instant.parse("2030-01-01T00:00:00Z"), PIN)

/** A route as a fake: in production only `AuthRepository` makes them (and refuses any other). */
class FakeRoute(override val serverId: String, override val encrypted: Boolean = true) : SignInRoute

class FakeAuthenticator(private val catalog: FakeCatalog? = null) : Authenticator {
    val findCalls = mutableListOf<Pair<String, ServerNetDraft>>()
    var onFind: suspend (String, ServerNetDraft) -> ServerInfo = { text, _ -> serverInfo(if ("://" in text) text else "https://$text") }

    /** What [signInRoute] answers; null = nothing verified. */
    var routeAnswer: (String) -> SignInRoute? = { FakeRoute(it) }
    val routesAsked = mutableListOf<String>()

    val passwordCalls = mutableListOf<Triple<String, String, String>>() // serverId, user, password text
    val passwordRoutes = mutableListOf<SignInRoute>()
    var onPassword: suspend (String, String) -> AccountSummary = { id, u -> AccountSummary(id, "uid", u) }

    val signedOut = mutableListOf<Pair<String, String>>()
    var onSignOut: suspend (String, String) -> Unit = { _, _ -> }

    /** What sign-out and removal report: false = the server could not be reached to revoke. */
    var revoked = true
    val removedServers = mutableListOf<String>()

    var quickConnectFlow: (String) -> Flow<QuickConnectProgress> = { emptyFlow() }
    var quickConnectStarts = 0

    override suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo {
        findCalls += input to draft
        return onFind(input, draft)
    }

    override suspend fun signInRoute(serverId: String): SignInRoute? {
        routesAsked += serverId
        return routeAnswer(serverId)
    }

    override fun quickConnect(route: SignInRoute): Flow<QuickConnectProgress> {
        quickConnectStarts++
        return quickConnectFlow(route.serverId)
    }

    override suspend fun signInWithPassword(route: SignInRoute, username: String, password: CharArray): AccountSummary {
        passwordCalls += Triple(route.serverId, username, String(password))
        passwordRoutes += route
        return onPassword(route.serverId, username)
    }

    override suspend fun signOut(serverId: String, userId: String): Boolean {
        signedOut += serverId to userId
        onSignOut(serverId, userId)
        return revoked
    }

    override suspend fun removeServer(serverId: String): Boolean {
        removedServers += serverId
        catalog?.remove(serverId)
        return revoked
    }
}

class FakeProber : CertificateProber {
    var result: CertificateInfo? = certificate()
    val probed = mutableListOf<ServerAddress>()
    override suspend fun probe(address: ServerAddress): CertificateInfo? {
        probed += address
        return result
    }
}

class FakeCatalog(servers: List<Server> = emptyList(), accounts: List<AccountSummary> = emptyList(), active: AccountSummary? = null) : ServerCatalog {
    val serverList = MutableStateFlow(servers)
    val accountList = MutableStateFlow(accounts)
    /** Accounts whose sign-in the server rejected (kept for their waiting changes; never active). */
    val expiredList = MutableStateFlow<List<AccountSummary>>(emptyList())
    val activeFlow = MutableStateFlow(active)
    val warmed = CompletableDeferred<Unit>()
    val upserts = mutableListOf<Server>()
    val activated = mutableListOf<Pair<String, String>>()
    var upsertFailure: Throwable? = null
    var awaitActiveFailure: Throwable? = null
    var warmRetries = 0
    var warmStillFailing = false

    /** The same guard the real store uses: a failing read gives a fail-closed value and waits for [retryWarmIfFailed]. */
    private val reads = ReadHealth("FakeCatalog")
    private val readsBroken = MutableStateFlow<Exception?>(null)
    override val readFailed: StateFlow<Boolean> = reads.failed

    /** Every read now throws into its collector, as Room does for a damaged database, until [healReads]. */
    fun failReads(e: Exception = IllegalStateException("database disk image is malformed")) { readsBroken.value = e }

    /** The database works again; reads that failed stay failed until [retryWarmIfFailed] subscribes them again, as in the app. */
    fun healReads() { readsBroken.value = null }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> read(empty: T, source: () -> Flow<T>): Flow<T> =
        reads.guard("fake", empty) { readsBroken.flatMapLatest { e -> if (e != null) flow { throw e } else source() } }

    override val servers: Flow<List<ServerSummary>> = read(emptyList()) { serverList.map { list -> list.map { it.summary() } } }
    override fun observeServer(id: String): Flow<Server?> = read(null) { serverList.map { list -> list.firstOrNull { it.id == id } } }
    override val activeAccount: Flow<AccountSummary?> = read(null) { activeFlow }
    override fun activeSession(): Flow<Session?> = activeAccount.map { a -> a?.let { Session(it.serverId, it.userId) } }
    override fun accounts(serverId: String): Flow<List<AccountSummary>> = read(emptyList()) { accountList.map { l -> l.filter { it.serverId == serverId } } }
    override fun expiredAccounts(serverId: String): Flow<List<AccountSummary>> = read(emptyList()) { expiredList.map { l -> l.filter { it.serverId == serverId } } }
    override suspend fun server(id: String): Server? = serverList.value.firstOrNull { it.id == id }
    override suspend fun headerValues(serverId: String): Map<String, String> = server(serverId)?.customHeaders.orEmpty()

    override suspend fun upsertServer(s: Server) {
        upsertFailure?.let { throw it }
        upserts += s
        serverList.value = serverList.value.filter { it.id != s.id } + s
    }

    /** What the store does when `AuthRepository.removeServer` deletes a server. */
    fun remove(id: String) {
        serverList.value = serverList.value.filter { it.id != id }
        accountList.value = accountList.value.filter { it.serverId != id }
        if (activeFlow.value?.serverId == id) activeFlow.value = null
    }

    override suspend fun setActive(serverId: String, userId: String) {
        activated += serverId to userId
        activeFlow.value = accountList.value.first { it.serverId == serverId && it.userId == userId }
    }

    override suspend fun awaitWarmed() = warmed.await()

    override suspend fun retryWarmIfFailed(): Boolean {
        reads.retry()
        warmRetries++
        return warmStillFailing
    }

    override suspend fun awaitActive(): AccountSummary? {
        awaitWarmed()
        awaitActiveFailure?.let { throw it }
        val account = activeFlow.value ?: return null
        return account.takeIf { a -> serverList.value.any { it.id == a.serverId } }
    }
}

class FakeConnections : ServerConnections {
    val states = mutableMapOf<String, MutableStateFlow<Connection>>()
    var onlineAnswer: Connection.Online? = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    var refreshes = 0

    /** What was asked, in order, by [refresh] (`probe:<server>`) and by tests (`refresh`): shows which came first. */
    val log = mutableListOf<String>()

    fun stateFor(id: String) = states.getOrPut(id) { MutableStateFlow<Connection>(Connection.Checking()) }

    override fun connection(serverId: String): StateFlow<Connection> = stateFor(serverId)
    override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? = onlineAnswer
    override fun refresh() { refreshes++ }
    override fun refresh(serverId: String) { log += "probe:$serverId" }
    override fun refreshIfCurrent(serverId: String, failed: Connection.Online) = Unit
}

class FakeChooser(private val alias: String?) : ClientCertChooser {
    override suspend fun choose(): String? = alias
}
