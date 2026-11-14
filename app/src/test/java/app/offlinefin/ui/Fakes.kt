package app.offlinefin.ui

import app.offlinefin.data.servers.Account
import app.offlinefin.data.servers.Authenticator
import app.offlinefin.data.servers.CertificateProber
import app.offlinefin.data.servers.Connection
import app.offlinefin.data.servers.QuickConnectState
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.ServerConnections
import app.offlinefin.data.servers.ServerInfo
import app.offlinefin.data.servers.ServerNetDraft
import app.offlinefin.net.CertificateInfo
import app.offlinefin.net.ServerAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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

class FakeAuthenticator : Authenticator {
    val findCalls = mutableListOf<Pair<String, ServerNetDraft>>()
    var onFind: suspend (String, ServerNetDraft) -> ServerInfo = { text, _ -> serverInfo(if ("://" in text) text else "https://$text") }

    val passwordCalls = mutableListOf<Triple<String, String, String>>() // serverId, user, password text
    var onPassword: suspend (Server, String) -> Account = { s, u -> Account(s.id, "uid", u, "token") }

    val signedOut = mutableListOf<Pair<String, String>>()
    var onSignOut: suspend (String, String) -> Unit = { _, _ -> }

    var quickConnectFlow: (Server) -> Flow<QuickConnectState> = { emptyFlow() }
    var quickConnectStarts = 0

    override suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo {
        findCalls += input to draft
        return onFind(input, draft)
    }

    override fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectState> {
        quickConnectStarts++
        return quickConnectFlow(server)
    }

    override suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray): Account {
        passwordCalls += Triple(server.id, username, String(password))
        return onPassword(server, username)
    }

    override suspend fun signOut(serverId: String, userId: String) {
        signedOut += serverId to userId
        onSignOut(serverId, userId)
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

class FakeCatalog(servers: List<Server> = emptyList(), accounts: List<Account> = emptyList(), active: Account? = null) : ServerCatalog {
    val serverList = MutableStateFlow(servers)
    val accountList = MutableStateFlow(accounts)
    val activeFlow = MutableStateFlow(active)
    val warmed = CompletableDeferred<Unit>()
    val upserts = mutableListOf<Server>()
    val removedServers = mutableListOf<String>()
    val activated = mutableListOf<Pair<String, String>>()
    var upsertFailure: Throwable? = null

    override val servers: Flow<List<Server>> = serverList
    override val activeAccount: Flow<Account?> = activeFlow
    override fun accounts(serverId: String): Flow<List<Account>> = accountList.map { l -> l.filter { it.serverId == serverId } }
    override suspend fun server(id: String): Server? = serverList.value.firstOrNull { it.id == id }

    override suspend fun upsertServer(s: Server) {
        upsertFailure?.let { throw it }
        upserts += s
        serverList.value = serverList.value.filter { it.id != s.id } + s
    }

    override suspend fun removeServer(id: String) {
        removedServers += id
        serverList.value = serverList.value.filter { it.id != id }
        accountList.value = accountList.value.filter { it.serverId != id }
        if (activeFlow.value?.serverId == id) activeFlow.value = null
    }

    override suspend fun setActive(serverId: String, userId: String) {
        activated += serverId to userId
        activeFlow.value = accountList.value.first { it.serverId == serverId && it.userId == userId }
    }

    override suspend fun awaitWarmed() = warmed.await()

    override suspend fun awaitActive(): Pair<Server, Account>? {
        awaitWarmed()
        val account = activeFlow.value ?: return null
        return serverList.value.firstOrNull { it.id == account.serverId }?.let { it to account }
    }
}

class FakeConnections : ServerConnections {
    val states = mutableMapOf<String, MutableStateFlow<Connection>>()
    var onlineAnswer: Connection.Online? = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    var refreshes = 0

    fun stateFor(id: String) = states.getOrPut(id) { MutableStateFlow<Connection>(Connection.Checking()) }

    override fun connection(server: Server): StateFlow<Connection> = stateFor(server.id)
    override suspend fun awaitOnline(server: Server, timeoutMs: Long): Connection.Online? = onlineAnswer
    override fun refresh() { refreshes++ }
}

class FakeChooser(private val alias: String?) : ClientCertChooser {
    override suspend fun choose(): String? = alias
}
