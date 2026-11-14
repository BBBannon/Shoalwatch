package app.offlinefin.data

import app.offlinefin.core.AppError
import app.offlinefin.data.servers.AddressSelector
import app.offlinefin.data.servers.AuthResult
import app.offlinefin.data.servers.Connection
import app.offlinefin.data.servers.DeviceIdentity
import app.offlinefin.data.servers.JellyfinGateway
import app.offlinefin.data.servers.QcSession
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerInfo
import app.offlinefin.net.NetworkMonitor
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class AddressSelectorTest {
    private val home = ServerAddress.parse("http://192.168.1.10:8096")
    private val away = ServerAddress.parse("https://away.example")
    private val device = DeviceIdentity("dev-1", "Pixel", "OfflineFin", "0.1")

    private fun server(h: ServerAddress? = home, a: ServerAddress? = away, id: String = "abc-123", cleartext: Boolean = true) =
        Server(id, "Den", "10.10.3", h, a, cleartext, null, null, emptyMap())

    private class FakeGateway : JellyfinGateway {
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

    private class FakeMonitor : NetworkMonitor {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val changes = flow
    }

    private val gateway = FakeGateway()
    private val monitor = FakeMonitor()

    private fun TestScope.selector() = AddressSelector(gateway, monitor, backgroundScope, device)

    /** `advanceUntilIdle` ignores background-scope work, so move time on explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private val hang: suspend () -> String = { awaitCancellation() }
    private val fail: suspend () -> String = { throw AppError.of(AppError.Kind.NETWORK) }

    @Test fun startsAsChecking() = runTest {
        val flow = selector().connection(server())
        assertEquals(Connection.Checking, flow.value)
    }

    @Test fun homeWinsWhenBothAnswerAndAwayIsCancelled() = runTest {
        gateway.behaviour[away] = { delay(500); "abc123" }
        val flow = selector().connection(server())
        runCurrent()
        assertEquals(Connection.Online(home, isHome = true), flow.value)
        assertEquals(0, currentTime)
        assertTrue(away in gateway.calls)
        assertEquals(listOf(away), gateway.cancelled)
    }

    @Test fun awayIsUsedOnceHomeTimesOut() = runTest {
        gateway.behaviour[home] = hang
        val flow = selector().connection(server())
        advanceTimeBy(1_499)
        assertEquals(Connection.Checking, flow.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Connection.Online(away, isHome = false), flow.value)
        assertEquals(1_500, currentTime)
        assertEquals("the timed-out home call is cancelled", listOf(home), gateway.cancelled)
    }

    @Test fun awayIsUsedAtOnceWhenHomeFails() = runTest {
        gateway.behaviour[home] = fail
        val flow = selector().connection(server())
        runCurrent()
        assertEquals(Connection.Online(away, isHome = false), flow.value)
    }

    @Test fun aHomeAddressWithADifferentServerIdIsIgnored() = runTest {
        gateway.behaviour[home] = { "other-server" }
        val flow = selector().connection(server())
        runCurrent()
        assertEquals(Connection.Online(away, isHome = false), flow.value)
    }

    @Test fun anAwayAddressWithADifferentServerIdIsNeverOnline() = runTest {
        gateway.behaviour[home] = fail
        gateway.behaviour[away] = { "" }
        val flow = selector().connection(server())
        settle()
        assertEquals(Connection.Offline, flow.value)
    }

    @Test fun idsDifferingOnlyByCaseAndDashesMatch() = runTest {
        gateway.behaviour[home] = { "ABC123" }
        val flow = selector().connection(server(id = "abc-123"))
        runCurrent()
        assertEquals(Connection.Online(home, isHome = true), flow.value)
    }

    @Test fun offlineWhenNeitherAnswersAfterTheAwayTimeout() = runTest {
        gateway.behaviour[home] = fail
        gateway.behaviour[away] = hang
        val flow = selector().connection(server())
        advanceTimeBy(4_999)
        assertEquals(Connection.Checking, flow.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Connection.Offline, flow.value)
        assertEquals(5_000, currentTime)
    }

    @Test fun aServerWithOnlyOneAddressProbesOnlyThatOne() = runTest {
        val onlyAway = selector().connection(server(h = null))
        val onlyHome = selector().connection(server(a = null, id = "abc123"))
        runCurrent()
        assertEquals(Connection.Online(away, isHome = false), onlyAway.value)
        assertEquals(Connection.Online(home, isHome = true), onlyHome.value)
        assertEquals(setOf(home, away), gateway.calls.toSet())
        assertEquals(2, gateway.calls.size)
    }

    @Test fun aServerWithNoAddressIsOffline() = runTest {
        val flow = selector().connection(server(h = null, a = null))
        runCurrent()
        assertEquals(Connection.Offline, flow.value)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun probesAreAnonymous() = runTest {
        selector().connection(server())
        runCurrent()
        assertTrue(gateway.nets.isNotEmpty())
        gateway.nets.forEach {
            assertNull(it.authKey)
            assertFalse("Token=" in it.authHeader()!!)
            assertEquals(setOf("192.168.1.10:8096", "away.example:443"), it.authorities)
        }
    }

    @Test fun aServerWithADamagedPinIsOfflineAndNothingIsSent() = runTest {
        val flow = selector().connection(server().copy(pinInvalid = true))
        runCurrent()
        assertEquals(Connection.Offline, flow.value)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun reProbesOnNetworkChangeAndKeepsShowingOnlineMeanwhile() = runTest {
        val flow = selector().connection(server())
        runCurrent()
        assertEquals(Connection.Online(home, true), flow.value)

        gateway.behaviour[home] = { delay(300); "abc123" }
        monitor.flow.emit(Unit)
        runCurrent()
        assertEquals("not Checking while re-probing", Connection.Online(home, true), flow.value)
        settle()
        assertEquals(Connection.Online(home, true), flow.value)

        gateway.behaviour[home] = fail
        monitor.flow.emit(Unit)
        settle()
        assertEquals(Connection.Online(away, false), flow.value)
        assertEquals(3, gateway.calls.count { it == home })
    }

    @Test fun refreshReProbes() = runTest {
        val s = selector()
        val flow = s.connection(server())
        runCurrent()
        gateway.behaviour[home] = fail
        s.refresh()
        runCurrent()
        assertEquals(Connection.Online(away, false), flow.value)
    }

    @Test fun aBurstOfRequestsDuringAProbeCausesExactlyOneMoreProbeAndNeverOverlaps() = runTest {
        gateway.behaviour[home] = { delay(1_000); "abc123" }
        val s = selector()
        s.connection(server(a = null))
        runCurrent()
        repeat(5) {
            s.refresh()
            monitor.flow.emit(Unit)
        }
        advanceTimeBy(999)
        assertEquals("the first probe is still running", 1, gateway.calls.size)
        settle()
        assertEquals(2, gateway.calls.size)
        assertEquals(1, gateway.maxInFlight)
    }

    @Test fun sameServerCallsShareOneProbeLoop() = runTest {
        val s = selector()
        val a = s.connection(server())
        val b = s.connection(server())
        runCurrent()
        assertTrue(a === b)
        assertEquals(1, gateway.calls.count { it == home })
    }

    @Test fun changedAddressesForTheSameServerIdAreReProbed() = runTest {
        val s = selector()
        val flow = s.connection(server())
        runCurrent()
        assertEquals(Connection.Online(home, true), flow.value)

        val newHome = ServerAddress.parse("http://192.168.2.20:8096")
        val again = s.connection(server(h = newHome))
        assertTrue(flow === again)
        assertEquals(Connection.Checking, again.value)
        runCurrent()
        assertEquals(Connection.Online(newHome, true), again.value)
    }

    @Test fun changedSecuritySettingsAreReProbedWithTheNewSettings() = runTest {
        val s = selector()
        s.connection(server(cleartext = true))
        runCurrent()
        s.connection(server(cleartext = false))
        runCurrent()
        assertEquals(listOf(true, false), gateway.nets.map { it.allowCleartext }.distinct())
    }

    @Test fun forgetCancelsTheProbeAndStopsListening() = runTest {
        gateway.behaviour[home] = hang
        val s = selector()
        s.connection(server(a = null))
        runCurrent()
        s.forget("abc-123")
        runCurrent()
        assertEquals(listOf(home), gateway.cancelled)
        monitor.flow.emit(Unit)
        s.refresh()
        settle()
        assertEquals(1, gateway.calls.size)
    }

    @Test fun anUnexpectedGatewayCrashIsJustUnreachable() = runTest {
        gateway.behaviour[home] = { throw IllegalStateException("boom") }
        gateway.behaviour[away] = { throw IllegalStateException("boom") }
        val flow = selector().connection(server())
        settle()
        assertEquals(Connection.Offline, flow.value)
    }
}
