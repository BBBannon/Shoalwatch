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

/** Review I6: the selector reads each server from the store when it probes and watches it for changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressSelectorStoreTest : AddressSelectorTestBase() {
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
}
