package app.shoalwatch.ui.servers

import app.shoalwatch.data.admin.AdminAccess
import app.shoalwatch.data.admin.AdminMe
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.admin.FakeAdminRights
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** "Server admin" shows on the active account's server only while its own server confirms it is an administrator. */
class ServersAdminEntryTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary("s1", "u1", "ben")
    private val kid = AccountSummary("s1", "u2", "kid")
    private val catalog = FakeCatalog(listOf(testServer("s1"), testServer("s2")), listOf(ben, kid), ben)
    private val rights = FakeAdminRights()

    @Test fun withoutTheRightsSeamItNeverShows() {
        val vm = ServersViewModel(catalog, FakeAuthenticator())
        assertNull(vm.state.value.adminServerId)
        assertEquals(0, rights.checks)
    }

    @Test fun anAdministratorSeesItOnTheirServer() {
        val vm = ServersViewModel(catalog, FakeAuthenticator(), rights)
        assertEquals("s1", vm.state.value.adminServerId)
        assertEquals(1, rights.checks)
    }

    @Test fun notAdminOrUnknownHidesIt() {
        for (status in listOf(AdminStatus.NOT_ADMIN, AdminStatus.UNKNOWN)) {
            rights.answer = status
            rights.held.value = null
            assertNull(status.name, ServersViewModel(catalog, FakeAuthenticator(), rights).state.value.adminServerId)
        }
    }

    @Test fun aSwitchHidesItAtOnceThenAsksForTheNewSession() {
        val vm = ServersViewModel(catalog, FakeAuthenticator(), rights)
        assertEquals("s1", vm.state.value.adminServerId)
        val answer = CompletableDeferred<AdminStatus>()
        rights.checkAnswer = { answer.await() }
        catalog.activeFlow.value = kid
        assertNull("hidden before the new check answers", vm.state.value.adminServerId)
        assertEquals(2, rights.checks)
        answer.complete(AdminStatus.NOT_ADMIN)
        assertNull(vm.state.value.adminServerId)
    }

    @Test fun anAnswerForTheOldSessionAfterTheSwitchNeverShowsIt() {
        val late = CompletableDeferred<AdminStatus>()
        rights.checkAnswer = { s -> if (s == Session("s1", "u1")) late.await() else AdminStatus.NOT_ADMIN }
        val vm = ServersViewModel(catalog, FakeAuthenticator(), rights)
        catalog.activeFlow.value = kid
        late.complete(AdminStatus.ADMIN)
        rights.held.value = AdminState(Session("s1", "u1"), AdminStatus.ADMIN) // even if it lands in the rights
        assertNull(vm.state.value.adminServerId)
    }

    @Test fun itFollowsTheRightsGoingAway() {
        val vm = ServersViewModel(catalog, FakeAuthenticator(), rights)
        assertEquals("s1", vm.state.value.adminServerId)
        rights.held.value = AdminState(Session("s1", "u1"), AdminStatus.NOT_ADMIN)
        assertNull(vm.state.value.adminServerId)
    }

    /**
     * The real AdminAccess hears of a switch from its own collector, which may run after the Servers screen's. A check that
     * arrives first must wait for it, not answer "not an administrator" for good.
     */
    @Test fun withTheRealRightsAnAdministratorSeesItAfterASwitchHeardLate() {
        val access = AdminAccess { s -> AdminMe(s.userId, isAdministrator = s.userId == "u1", isDisabled = false) }
        access.sessionChanged(Session("s1", "u2"))
        catalog.activeFlow.value = kid
        val vm = ServersViewModel(catalog, FakeAuthenticator(), access)
        assertNull("kid is not an administrator", vm.state.value.adminServerId)
        catalog.activeFlow.value = ben // the screen hears first
        access.sessionChanged(Session("s1", "u1")) // the graph's collector, later
        assertEquals("s1", vm.state.value.adminServerId)
        catalog.activeFlow.value = kid
        access.sessionChanged(Session("s1", "u2"))
        assertNull("still never for kid", vm.state.value.adminServerId)
    }
}
