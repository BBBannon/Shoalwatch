package app.shoalwatch.ui.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminGuardTest {
    private val ben = AccountSummary(S1, U1, "ben")
    private val kid = AccountSummary(S1, U2, "kid")
    private val owner = Session(S1, U1)
    private val catalog = FakeCatalog(accounts = listOf(ben, kid), active = ben)
    private val rights = FakeAdminRights()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val connections = FakeConnections().apply { stateFor(S1).value = online }

    private fun TestScope.guard(): AdminGuard = AdminGuard(catalog, rights, connections, backgroundScope).also { runCurrent() }

    private fun TestScope.liveOf(guard: AdminGuard): () -> Session? {
        var last: Session? = null
        backgroundScope.launch { guard.live.collect { last = it } }
        runCurrent()
        return { last }
    }

    @Test fun anAdministratorOpens() = runTest {
        assertEquals(AdminGate.Open(owner, online = true), guard().gate.value)
        assertEquals(1, rights.checks)
    }

    @Test fun nobodySignedInClosesWithoutAsking() = runTest {
        catalog.activeFlow.value = null
        assertEquals(AdminGate.Closed, guard().gate.value)
        assertEquals(0, rights.checks)
    }

    @Test fun notAdminOrUnknownCloses() = runTest {
        for (status in listOf(AdminStatus.NOT_ADMIN, AdminStatus.UNKNOWN)) {
            rights.answer = status
            assertEquals(status.name, AdminGate.Closed, guard().gate.value)
        }
    }

    @Test fun aSwitchClosesForGoodEvenIfTheOwnerComesBack() = runTest {
        val g = guard()
        catalog.activeFlow.value = kid
        runCurrent()
        assertEquals(AdminGate.Closed, g.gate.value)
        catalog.activeFlow.value = ben
        rights.held.value = AdminState(owner, AdminStatus.ADMIN)
        runCurrent()
        assertEquals(AdminGate.Closed, g.gate.value)
    }

    @Test fun losingTheRightsCloses() = runTest {
        val g = guard()
        rights.held.value = null
        runCurrent()
        assertEquals(AdminGate.Closed, g.gate.value)
        val h = guard()
        rights.held.value = AdminState(owner, AdminStatus.NOT_ADMIN)
        runCurrent()
        assertEquals(AdminGate.Closed, h.gate.value)
    }

    @Test fun offlineIsOpenButNotLive() = runTest {
        val g = guard()
        val live = liveOf(g)
        g.setVisible(true)
        runCurrent()
        assertEquals(owner, live())
        connections.stateFor(S1).value = Connection.Offline()
        runCurrent()
        assertEquals(AdminGate.Open(owner, online = false), g.gate.value)
        assertNull(live())
        connections.stateFor(S1).value = online
        runCurrent()
        assertEquals(owner, live())
        g.setVisible(false)
        runCurrent()
        assertNull("hidden", live())
    }

    @Test fun reCheckedEveryMinuteOnlyWhileVisibleAndOnline() = runTest {
        val g = guard()
        g.setVisible(true)
        advanceTimeBy(5 * 60_000L + 1)
        assertEquals("1 at open, then 5", 6, rights.checks)
        g.setVisible(false)
        advanceTimeBy(5 * 60_000L)
        assertEquals("none while hidden", 6, rights.checks)
        g.setVisible(true)
        connections.stateFor(S1).value = Connection.Offline()
        advanceTimeBy(5 * 60_000L)
        assertEquals("none while offline", 6, rights.checks)
    }

    @Test fun aReCheckThatSaysNoCloses() = runTest {
        val g = guard()
        g.setVisible(true)
        rights.answer = AdminStatus.NOT_ADMIN
        advanceTimeBy(60_001)
        assertEquals(AdminGate.Closed, g.gate.value)
    }

    @Test fun problemTable() = runTest {
        val g = guard()
        val table = mapOf(
            AppError.Kind.OFFLINE to AdminProblem.OFFLINE,
            AppError.Kind.NETWORK to AdminProblem.NETWORK,
            AppError.Kind.TIMEOUT to AdminProblem.NETWORK,
            AppError.Kind.SERVER_NOT_FOUND to AdminProblem.GONE,
            AppError.Kind.SERVER_ERROR to AdminProblem.SERVER,
            AppError.Kind.TLS_UNTRUSTED to AdminProblem.CONNECTION,
            AppError.Kind.TLS_PIN_CHANGED to AdminProblem.CONNECTION,
            AppError.Kind.CLEARTEXT_BLOCKED to AdminProblem.CONNECTION,
            AppError.Kind.CLIENT_CERT_REQUIRED to AdminProblem.CONNECTION,
            AppError.Kind.UNKNOWN to AdminProblem.OTHER,
            AppError.Kind.SERVER_MISMATCH to AdminProblem.OTHER,
        )
        for ((kind, problem) in table) assertEquals(kind.name, problem, g.problemFor(AppError.of(kind)))
        assertEquals(AdminProblem.OTHER, g.problemFor(IllegalArgumentException()))
        assertEquals(AdminGate.Open(owner, online = true), g.gate.value)
    }

    @Test fun signInProblemsClose() = runTest {
        for (kind in listOf(AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN)) {
            val g = guard()
            assertNull(g.problemFor(AppError.of(kind)))
            assertEquals(AdminGate.Closed, g.gate.value)
        }
    }

    @Test fun aRefusalWhileStillAdminStaysOpen() = runTest {
        val g = guard()
        assertEquals(AdminProblem.REFUSED, g.problemFor(AppError.of(AppError.Kind.FORBIDDEN)))
        assertEquals(AdminGate.Open(owner, online = true), g.gate.value)
        assertEquals("the refusal was re-checked", 2, rights.checks)
    }

    @Test fun aRefusalThatTheReCheckConfirmsCloses() = runTest {
        val g = guard()
        rights.answer = AdminStatus.NOT_ADMIN
        assertNull(g.problemFor(AppError.of(AppError.Kind.FORBIDDEN)))
        runCurrent()
        assertEquals(AdminGate.Closed, g.gate.value)
        assertEquals(listOf(owner), rights.lostCalls)
    }
}
