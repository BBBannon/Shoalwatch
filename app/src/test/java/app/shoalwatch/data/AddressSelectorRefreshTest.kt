package app.shoalwatch.data

import app.shoalwatch.data.servers.Connection
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** `refreshIfCurrent`: a failed request only re-probes its own server, and only if the connection it used is still the current one. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressSelectorRefreshTest : AddressSelectorTestBase() {
    @Test fun aFailureOfTheCurrentOnlineReprobesThatServerAlone() = runTest {
        val s = selector()
        val a = server(a = null)
        val bHome = ServerAddress.parse("http://192.168.1.11:8096")
        gateway.behaviour[bHome] = { "bbb" }
        val b = server(h = bHome, id = "bbb", a = null)
        val used = s.awaitOnline(a)!!
        val other = s.awaitOnline(b)!!
        val before = gateway.calls.size
        s.refreshIfCurrent("abc123", used)
        assertEquals("synchronously Checking", Connection.Checking(used), s.connection("abc123").value)
        assertSame("the other server is untouched", other, s.connection("bbb").value)
        settle()
        assertEquals("one probe, for one server", before + 1, gateway.calls.size)
        assertEquals(Connection.Online(home, true), s.connection("abc123").value)
    }

    @Test fun aFailureArrivingAfterTheSelectorMovedOnDoesNotInvalidateIt() = runTest {
        val s = selector()
        val used = s.awaitOnline(server(a = null))!!
        s.refresh()
        settle()
        val fresh = s.connection("abc123").value as Connection.Online
        assertEquals("equal by value, a different verification", used, fresh)
        val probes = gateway.calls.size
        s.refreshIfCurrent("abc123", used)
        runCurrent()
        assertSame("still the newer Online", fresh, s.connection("abc123").value)
        assertEquals("no new probe", probes, gateway.calls.size)
    }

    @Test fun aFailureWhileAlreadyCheckingDoesNothingMore() = runTest {
        val s = selector()
        val used = s.awaitOnline(server(a = null))!!
        gateway.behaviour[home] = hang
        s.refresh()
        runCurrent()
        val probes = gateway.calls.size
        s.refreshIfCurrent("abc123", used)
        runCurrent()
        assertEquals(Connection.Checking(used), s.connection("abc123").value)
        assertEquals(probes, gateway.calls.size)
    }
}
