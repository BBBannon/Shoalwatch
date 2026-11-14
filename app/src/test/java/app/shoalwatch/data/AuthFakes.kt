package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.U1

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.AuthResult
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.JellyfinGateway
import app.shoalwatch.data.servers.QcSession
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records every call: its name, the address and configuration used, and the Authorization header it would send. */
internal class FakeGateway : JellyfinGateway {
    val calls = mutableListOf<String>()
    val nets = mutableListOf<ServerNetConfig>()
    val addresses = mutableListOf<ServerAddress>()
    val headers = mutableListOf<String?>()
    var info: suspend (ServerAddress) -> ServerInfo = { ServerInfo(S1, "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) }
    var enabled: () -> Boolean = { true }
    var polls: (Int) -> Boolean = { true }
    var pollCount = 0

    /** Runs after each poll with its index (to change the world between a poll and the next step). */
    var afterPoll: (Int) -> Unit = {}
    var auth: () -> AuthResult = { AuthResult(U1, "ben", "tok-1", S1) }
    var logout: suspend (ServerAddress) -> Unit = {}
    var lastPassword: String? = null
    var lastUsername: String? = null

    /** Calls by name with their address, e.g. `"logout" to away`. */
    val log get() = calls.zip(addresses)

    private fun record(name: String, a: ServerAddress, n: ServerNetConfig) { calls += name; addresses += a; nets += n; headers += n.authHeader() }

    override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo { record("info", address, net); return info(address) }
    override suspend fun quickConnectEnabled(address: ServerAddress, net: ServerNetConfig): Boolean { record("enabled", address, net); return enabled() }
    override suspend fun quickConnectInitiate(address: ServerAddress, net: ServerNetConfig): QcSession { record("initiate", address, net); return QcSession("secret", "123456") }
    override suspend fun quickConnectState(address: ServerAddress, net: ServerNetConfig, secret: String): Boolean {
        record("state", address, net); val n = pollCount++; return polls(n).also { afterPoll(n) }
    }
    override suspend fun authenticateWithQuickConnect(address: ServerAddress, net: ServerNetConfig, secret: String): AuthResult {
        record("authQc", address, net); return auth()
    }
    override suspend fun authenticateByName(address: ServerAddress, net: ServerNetConfig, username: String, password: String): AuthResult {
        record("authName", address, net); lastUsername = username; lastPassword = password; return auth()
    }
    override suspend fun logout(address: ServerAddress, net: ServerNetConfig) { record("logout", address, net); logout(address) }
}

/** The selector as `AuthRepository` sees it: [online] decides what `awaitOnline` returns for each server id. */
internal class StubConnections(var online: (String) -> Connection.Online?) : ServerConnections {
    val asked = mutableListOf<String>()
    private val flows = HashMap<String, MutableStateFlow<Connection>>()

    /** One live flow per server, starting from [online]; tests change it with [set] to simulate a network change. */
    override fun connection(serverId: String): StateFlow<Connection> = flows.getOrPut(serverId) { MutableStateFlow(online(serverId) ?: Connection.Offline()) }
    fun set(serverId: String, state: Connection) { (connection(serverId) as MutableStateFlow<Connection>).value = state }
    override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? { asked += serverId; return online(serverId) }
    override fun refresh() = Unit
    override fun refreshIfCurrent(serverId: String, failed: Connection.Online) = Unit
}

internal fun inMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
