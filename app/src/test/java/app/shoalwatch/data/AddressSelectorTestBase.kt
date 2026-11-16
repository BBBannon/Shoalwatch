package app.shoalwatch.data

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.AuthResult
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGateway
import app.shoalwatch.data.servers.QcSession
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerSettingsSource
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fakes and helpers shared by the address selector tests. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AddressSelectorTestBase {
    protected val home = ServerAddress.parse("http://192.168.1.10:8096")
    protected val away = ServerAddress.parse("https://away.example")
    protected val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")

    protected fun server(h: ServerAddress? = home, a: ServerAddress? = away, id: String = "abc123", cleartext: Boolean = true) =
        Server(id, "Den", "10.10.3", h, a, cleartext, null, null, emptyMap())

    protected class FakeGateway : JellyfinGateway {
        /** What each address does when probed; default: answers at once with the right id. */
        val behaviour = mutableMapOf<ServerAddress, suspend () -> String>()
        val calls = mutableListOf<ServerAddress>()
        val cancelled = mutableListOf<ServerAddress>()
        val nets = mutableListOf<ServerNetConfig>()
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo {
            calls += address
            nets += net
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                val id = (behaviour[address] ?: { "abc123" })()
                return ServerInfo(id, "Den", "10.10.3", address, ServerInfo.Compatibility.SUPPORTED)
            } catch (e: CancellationException) {
                cancelled += address
                throw e
            } finally {
                inFlight--
            }
        }

        override suspend fun quickConnectEnabled(address: ServerAddress, net: ServerNetConfig): Boolean = error("not probed")
        override suspend fun quickConnectInitiate(address: ServerAddress, net: ServerNetConfig): QcSession = error("not probed")
        override suspend fun quickConnectState(address: ServerAddress, net: ServerNetConfig, secret: String): Boolean = error("not probed")
        override suspend fun authenticateWithQuickConnect(address: ServerAddress, net: ServerNetConfig, secret: String): AuthResult = error("not probed")
        override suspend fun authenticateByName(address: ServerAddress, net: ServerNetConfig, username: String, password: String): AuthResult = error("not probed")
        override suspend fun logout(address: ServerAddress, net: ServerNetConfig) = error("not probed")
    }

    protected class FakeMonitor : NetworkMonitor {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val changes = flow
    }

    /** Stands in for the store: the selector reads settings from here, and watches it. */
    protected class FakeSource : ServerSettingsSource {
        val list = MutableStateFlow<List<Server>>(emptyList())
        var failure: Exception? = null

        /** How many times the selector has subscribed to [servers]. */
        var subscriptions = 0

        /** The next N emissions throw instead of being delivered (the store's flow failing). */
        var failOnEmit = 0

        /** Every subscription throws at once while true. */
        var failOnSubscribe = false
        override val servers: Flow<List<Server>> = flow {
            subscriptions++
            if (failOnSubscribe) throw IllegalStateException("store flow failed")
            list.collect {
                if (failOnEmit > 0) { failOnEmit--; throw IllegalStateException("store flow failed") }
                emit(it)
            }
        }
        /** How many times a server has been read with [server]. */
        var reads = 0
        override suspend fun server(id: String): Server? {
            reads++
            failure?.let { throw it }
            return list.value.firstOrNull { it.id == id }
        }
        fun put(s: Server) { list.value = list.value.filter { it.id != s.id } + s }
        fun remove(id: String) { list.value = list.value.filter { it.id != id } }
    }

    protected val gateway = FakeGateway()
    protected val monitor = FakeMonitor()
    protected val source = FakeSource()

    /** Whether the app is in the foreground, as the selector is told. */
    protected val foreground = MutableStateFlow(true)

    protected fun TestScope.selector() = AddressSelector(gateway, monitor, backgroundScope, device, source, foreground)

    /** Saves [s] (as the store would) and returns its live connection, which the selector keys by id only. */
    protected fun AddressSelector.connection(s: Server): StateFlow<Connection> {
        source.put(s)
        return connection(s.id)
    }

    protected suspend fun AddressSelector.awaitOnline(s: Server, timeoutMs: Long = AddressSelector.AWAIT_TIMEOUT_MS): Connection.Online? {
        source.put(s)
        return awaitOnline(s.id, timeoutMs)
    }

    /** `advanceUntilIdle` ignores background-scope work, so move time on explicitly. */
    protected fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    protected val hang: suspend () -> String = { awaitCancellation() }
    protected val fail: suspend () -> String = { throw AppError.of(AppError.Kind.NETWORK) }
}
