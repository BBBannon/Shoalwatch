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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")

    private fun server(h: ServerAddress? = home, a: ServerAddress? = away, id: String = "abc123", cleartext: Boolean = true) =
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

    /** Stands in for the store: the selector reads settings from here, and watches it. */
    private class FakeSource : ServerSettingsSource {
        val list = MutableStateFlow<List<Server>>(emptyList())
        var failure: Exception? = null
        override val servers = list
        override suspend fun server(id: String): Server? {
            failure?.let { throw it }
            return list.value.firstOrNull { it.id == id }
        }
        fun put(s: Server) { list.value = list.value.filter { it.id != s.id } + s }
        fun remove(id: String) { list.value = list.value.filter { it.id != id } }
    }

    private val gateway = FakeGateway()
    private val monitor = FakeMonitor()
    private val source = FakeSource()

    private fun TestScope.selector() = AddressSelector(gateway, monitor, backgroundScope, device, source)

    /** Saves [s] (as the store would) and returns its live connection, which the selector keys by id only. */
    private fun AddressSelector.connection(s: Server): StateFlow<Connection> {
        source.put(s)
        return connection(s.id)
    }

    private suspend fun AddressSelector.awaitOnline(s: Server, timeoutMs: Long = AddressSelector.AWAIT_TIMEOUT_MS): Connection.Online? {
        source.put(s)
        return awaitOnline(s.id, timeoutMs)
    }

    /** `advanceUntilIdle` ignores background-scope work, so move time on explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private val hang: suspend () -> String = { awaitCancellation() }
    private val fail: suspend () -> String = { throw AppError.of(AppError.Kind.NETWORK) }

    @Test fun startsAsChecking() = runTest {
        val flow = selector().connection(server())
        assertEquals(Connection.Checking(), flow.value)
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
        assertEquals(Connection.Checking(), flow.value)
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
        assertEquals(Connection.Offline(), flow.value)
    }

    @Test fun offlineWhenNeitherAnswersAfterTheAwayTimeout() = runTest {
        gateway.behaviour[home] = fail
        gateway.behaviour[away] = hang
        val flow = selector().connection(server())
        advanceTimeBy(4_999)
        assertEquals(Connection.Checking(), flow.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Connection.Offline(), flow.value)
        assertEquals(5_000, currentTime)
    }

    @Test fun aServerWithOnlyOneAddressProbesOnlyThatOne() = runTest {
        gateway.behaviour[away] = { "def456" }
        val onlyAway = selector().connection(server(h = null, id = "def456"))
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
        assertEquals(Connection.Offline(), flow.value)
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
        assertEquals(Connection.Offline(AppError.Kind.TLS_UNTRUSTED), flow.value)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun aNetworkChangeImmediatelyDropsToCheckingWithThePreviousOnlineAndOnlyAProbeRestoresIt() = runTest {
        val flow = selector().connection(server())
        runCurrent()
        val before = Connection.Online(home, true)
        assertEquals(before, flow.value)

        // The probe on the new network is slow, so Checking must be visible meanwhile.
        gateway.behaviour[home] = { delay(300); "abc123" }
        monitor.flow.emit(Unit)
        runCurrent()
        assertEquals("no window where the pre-change Online is still published", Connection.Checking(before), flow.value)
        advanceTimeBy(499)
        assertEquals(1, gateway.calls.count { it == home })
        assertEquals(Connection.Checking(before), flow.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("probe started after the debounce", 2, gateway.calls.count { it == home })
        assertEquals(Connection.Checking(before), flow.value)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(before, flow.value)

        gateway.behaviour[home] = fail
        monitor.flow.emit(Unit)
        runCurrent()
        assertEquals(Connection.Checking(before), flow.value)
        settle()
        assertEquals(Connection.Online(away, false), flow.value)
    }

    @Test fun aBurstOfNetworkChangesStartsOneProbeAfterTheDebounce() = runTest {
        val flow = selector().connection(server(a = null))
        runCurrent()
        repeat(5) {
            monitor.flow.emit(Unit)
            runCurrent()
            advanceTimeBy(100)
        }
        assertEquals(Connection.Checking(Connection.Online(home, true)), flow.value)
        assertEquals(1, gateway.calls.size)
        settle()
        assertEquals(2, gateway.calls.size)
        assertEquals(Connection.Online(home, true), flow.value)
    }

    @Test fun anInFlightProbeFromBeforeTheChangeCannotPublish() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<String>()
        gateway.behaviour[home] = { release.await() }
        val flow = selector().connection(server(a = null))
        runCurrent() // probe 1 is waiting on the old network
        monitor.flow.emit(Unit)
        runCurrent()
        release.complete("abc123") // the old-network answer arrives after the change
        runCurrent()
        assertEquals(Connection.Checking(), flow.value)
        settle()
        assertEquals(Connection.Online(home, true), flow.value)
    }

    @Test fun anInFlightProbeFromOldSettingsCannotPublishAfterASettingsChange() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<String>()
        gateway.behaviour[home] = { release.await() }
        val s = selector()
        val flow = s.connection(server(a = null))
        runCurrent()
        val newHome = ServerAddress.parse("http://192.168.2.20:8096")
        gateway.behaviour[newHome] = hang
        s.connection(server(h = newHome, a = null))
        release.complete("abc123")
        runCurrent()
        assertEquals(Connection.Checking(), flow.value)
    }

    @Test fun refreshImmediatelyDropsToCheckingToo() = runTest {
        val s = selector()
        val flow = s.connection(server(a = null))
        runCurrent()
        gateway.behaviour[home] = hang
        s.refresh()
        assertEquals("synchronously, before any probe", Connection.Checking(Connection.Online(home, true)), flow.value)
        runCurrent()
        assertEquals(Connection.Checking(Connection.Online(home, true)), flow.value)
    }

    @Test fun awaitOnlineReturnsTheCurrentVerifiedOnline() = runTest {
        val s = selector()
        assertEquals(Connection.Online(home, true), s.awaitOnline(server()))
    }

    @Test fun awaitOnlineAfterANetworkChangeWaitsForTheFreshProbeNotThePreChangeOnline() = runTest {
        val s = selector()
        s.connection(server())
        runCurrent()
        gateway.behaviour[home] = fail // on the new network the LAN address is gone
        monitor.flow.emit(Unit)
        runCurrent()
        val result = s.awaitOnline(server())
        assertEquals(Connection.Online(away, false), result)
    }

    @Test fun awaitOnlineIsNullWhenOffline() = runTest {
        gateway.behaviour[home] = fail
        gateway.behaviour[away] = fail
        assertNull(selector().awaitOnline(server()))
    }

    @Test fun awaitOnlineIsNullOnTimeout() = runTest {
        gateway.behaviour[home] = hang
        gateway.behaviour[away] = hang
        assertNull(selector().awaitOnline(server(), timeoutMs = 2_000))
        assertEquals(2_000, currentTime)
    }

    private fun failWith(kind: AppError.Kind): suspend () -> String = { throw AppError.of(kind) }

    @Test fun securityFailuresAreReportedAsTheOfflineReason() = runTest {
        listOf(
            AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.TLS_UNTRUSTED,
            AppError.Kind.CLEARTEXT_BLOCKED, AppError.Kind.CLIENT_CERT_REQUIRED,
        ).forEachIndexed { i, kind ->
            gateway.behaviour[home] = failWith(kind)
            gateway.behaviour[away] = fail
            val flow = selector().connection(server(id = "srv-$i"))
            runCurrent()
            assertEquals(kind.name, Connection.Offline(kind), flow.value)
        }
    }

    @Test fun ordinaryUnreachabilityHasNoReason() = runTest {
        listOf(AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_NOT_FOUND, AppError.Kind.SERVER_ERROR).forEachIndexed { i, kind ->
            gateway.behaviour[home] = failWith(kind)
            gateway.behaviour[away] = failWith(kind)
            val flow = selector().connection(server(id = "srv-$i"))
            runCurrent()
            assertEquals(kind.name, Connection.Offline(null), flow.value)
        }
        gateway.behaviour[home] = { "other" }
        gateway.behaviour[away] = hang
        val mismatch = selector().connection(server(id = "mismatch"))
        settle()
        assertEquals(Connection.Offline(null), mismatch.value)
    }

    @Test fun aTimeoutIsNoReasonEitherAndASecurityReasonWinsWhenTheAddressesDisagree() = runTest {
        gateway.behaviour[home] = hang // times out
        gateway.behaviour[away] = failWith(AppError.Kind.TLS_PIN_CHANGED)
        val a = selector().connection(server(id = "one"))
        settle()
        assertEquals(Connection.Offline(AppError.Kind.TLS_PIN_CHANGED), a.value)

        gateway.behaviour[home] = failWith(AppError.Kind.TLS_UNTRUSTED)
        gateway.behaviour[away] = failWith(AppError.Kind.NETWORK)
        val b = selector().connection(server(id = "two"))
        settle()
        assertEquals(Connection.Offline(AppError.Kind.TLS_UNTRUSTED), b.value)
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

    @Test fun changedAddressesInTheStoreAreReProbed() = runTest {
        val s = selector()
        val flow = s.connection(server())
        runCurrent()
        assertEquals(Connection.Online(home, true), flow.value)

        val newHome = ServerAddress.parse("http://192.168.2.20:8096")
        gateway.behaviour[newHome] = { delay(100); "abc123" }
        source.put(server(h = newHome))
        runCurrent()
        assertEquals("the old address is no longer shown", Connection.Checking(), flow.value)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(Connection.Online(newHome, true), flow.value)
    }

    @Test fun changedSecuritySettingsInTheStoreAreReProbedWithTheNewSettings() = runTest {
        val s = selector()
        s.connection(server(cleartext = true))
        runCurrent()
        source.put(server(cleartext = false))
        runCurrent()
        assertEquals(listOf(true, false), gateway.nets.map { it.allowCleartext }.distinct())
    }

    @Test fun everyProbeUsesThePinSavedNowWhoeverAsksForTheConnection() = runTest {
        val pin = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
        val s = selector()
        s.connection(server())
        runCurrent()
        source.put(server().copy(pinnedSpkiSha256 = pin))
        runCurrent()
        s.connection(server().id) // another screen, by id only: it cannot bring back an older copy
        s.refresh()
        runCurrent()
        assertEquals(pin, gateway.nets.last().pinnedSpkiSha256)
    }

    @Test fun aServerRemovedFromTheStoreGoesOfflineAndIsNeverProbedAgain() = runTest {
        val s = selector()
        val flow = s.connection(server())
        runCurrent()
        val before = gateway.calls.size
        source.remove(server().id)
        runCurrent()
        assertEquals(Connection.Offline(), flow.value)
        s.refresh()
        monitor.flow.emit(Unit)
        settle()
        assertEquals(Connection.Offline(), flow.value)
        assertEquals("no probe to the old addresses", before, gateway.calls.size)
    }

    @Test fun anIdThatIsNotSavedIsOfflineAndNothingIsSent() = runTest {
        val flow = selector().connection("not-saved")
        runCurrent()
        assertEquals(Connection.Offline(), flow.value)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun aStoreFailureIsOfflineAndTheLoopKeepsRunning() = runTest {
        val s = selector()
        source.put(server())
        source.failure = IllegalStateException("database closed")
        val flow = s.connection(server().id)
        runCurrent()
        assertEquals(Connection.Offline(), flow.value)
        source.failure = null
        s.refresh()
        runCurrent()
        assertEquals(Connection.Online(home, true), flow.value)
    }

    @Test fun forgetCancelsTheProbeAndStopsListening() = runTest {
        gateway.behaviour[home] = hang
        val s = selector()
        s.connection(server(a = null))
        runCurrent()
        s.forget("abc123")
        runCurrent()
        assertEquals(listOf(home), gateway.cancelled)
        monitor.flow.emit(Unit)
        s.refresh()
        settle()
        assertEquals(1, gateway.calls.size)
    }

    @Test fun aForgottenServerNoLongerShowsOnline() = runTest {
        val s = selector()
        val flow = s.connection(server(a = null))
        runCurrent()
        assertEquals(Connection.Online(home, isHome = true), flow.value)
        s.forget("abc123")
        assertEquals(Connection.Offline(), flow.value)
    }

    @Test fun anUnexpectedGatewayCrashIsJustUnreachable() = runTest {
        gateway.behaviour[home] = { throw IllegalStateException("boom") }
        gateway.behaviour[away] = { throw IllegalStateException("boom") }
        val flow = selector().connection(server())
        settle()
        assertEquals(Connection.Offline(), flow.value)
    }
}
