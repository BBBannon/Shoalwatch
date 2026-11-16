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
class AddressSelectorTest : AddressSelectorTestBase() {
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
