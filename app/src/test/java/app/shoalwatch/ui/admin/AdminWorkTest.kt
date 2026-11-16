package app.shoalwatch.ui.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.testId
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminWorkTest {
    private val owner = Session(S1, U1)
    private val catalog = FakeCatalog(accounts = listOf(AccountSummary(S1, U1, "ben")), active = AccountSummary(S1, U1, "ben"))
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val ran = mutableListOf<Session>()
    private var done = 0

    private fun TestScope.work(): AdminWork {
        val guard = AdminGuard(catalog, rights, connections, backgroundScope)
        runCurrent()
        return AdminWork(guard, backgroundScope)
    }

    @Test fun runsOnceAsTheGatesSession() = runTest {
        val w = work()
        w.run("restart", { s -> ran += s }, { done++ })
        runCurrent()
        assertEquals(listOf(owner), ran)
        assertEquals(1, done)
        assertNull(w.problem.value)
    }

    @Test fun aSecondRunWhileOneRunsIsIgnored() = runTest {
        val w = work()
        val slow = CompletableDeferred<Unit>()
        w.run("a", { s -> ran += s; slow.await() })
        runCurrent()
        assertEquals("a", w.running.value)
        w.run("b", { s -> ran += s })
        runCurrent()
        assertEquals(1, ran.size)
        slow.complete(Unit)
        runCurrent()
        assertNull(w.running.value)
    }

    @Test fun offlineDoesNotRun() = runTest {
        val w = work()
        connections.stateFor(S1).value = Connection.Offline()
        runCurrent()
        w.run("restart", { s -> ran += s })
        runCurrent()
        assertEquals(0, ran.size)
        assertEquals(AdminProblem.OFFLINE, w.problem.value)
        w.dismissProblem()
        assertNull(w.problem.value)
    }

    @Test fun closedDoesNotRunAndSaysNothing() = runTest {
        val w = work()
        catalog.activeFlow.value = null
        runCurrent()
        w.run("restart", { s -> ran += s })
        runCurrent()
        assertEquals(0, ran.size)
        assertNull(w.problem.value)
    }

    @Test fun aFailureIsShownAndTheActionWasInvokedExactlyOnce() = runTest {
        val w = work()
        var invoked = 0
        w.run("restart", { invoked++; throw AppError.of(AppError.Kind.NETWORK) }, { done++ })
        runCurrent()
        assertEquals(1, invoked)
        assertEquals(AdminProblem.NETWORK, w.problem.value)
        assertEquals("onDone only after success", 0, done)
        assertNull(w.running.value)
    }

    @Test fun aFailureThatClosesTheGateShowsNothing() = runTest {
        val w = work()
        w.run("restart", { throw AppError.of(AppError.Kind.UNAUTHORISED) })
        runCurrent()
        assertNull(w.problem.value)
    }

    @Test fun anAnswerAfterTheGateClosedIsDropped() = runTest {
        val w = work()
        val slow = CompletableDeferred<Unit>()
        w.run("restart", { slow.await() }, { done++ })
        runCurrent()
        catalog.activeFlow.value = null
        runCurrent()
        slow.complete(Unit)
        runCurrent()
        assertEquals(0, done)
        assertTrue(w.running.value == null)
    }

    /** Confirmed, then waiting (for the server's address, say) when the profile is switched: the action is never sent. */
    @Test fun anActionStillWaitingWhenTheGateClosesIsNeverSent() = runTest {
        val w = work()
        val address = CompletableDeferred<Unit>()
        var sent = 0
        w.run("restart", { address.await(); sent++ }, { done++ })
        runCurrent()
        catalog.activeFlow.value = AccountSummary(S1, testId("kid"), "kid")
        runCurrent()
        address.complete(Unit)
        runCurrent()
        assertEquals("nothing is sent after the gate closed", 0, sent)
        assertEquals(0, done)
        assertNull(w.running.value)
        assertNull(w.problem.value)
    }
}
