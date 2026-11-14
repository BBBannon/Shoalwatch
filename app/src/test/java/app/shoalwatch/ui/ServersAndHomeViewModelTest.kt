package app.shoalwatch.ui

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.nav.StartupState
import app.shoalwatch.ui.nav.StartupViewModel
import app.shoalwatch.ui.servers.Confirm
import app.shoalwatch.ui.servers.ServersViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServersAndHomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary("s1", "u1", "ben")
    private val amy = AccountSummary("s1", "u2", "amy")
    private val other = AccountSummary("s2", "u3", "cat")
    private val auth = FakeAuthenticator()
    private val connections = FakeConnections()

    private fun catalog(active: AccountSummary? = ben) = FakeCatalog(
        servers = listOf(testServer("s1"), testServer("s2", pinInvalid = true)),
        accounts = listOf(ben, amy, other),
        active = active,
    )

    // --- start-up routing ---------------------------------------------------------------------------------------

    @Test fun startupShowsNothingUntilTheWarmHasFinished() {
        val catalog = catalog()
        val vm = StartupViewModel(catalog)
        assertEquals(StartupState.Loading, vm.state.value)
        catalog.warmed.complete(Unit)
        assertEquals(StartupState.Home, vm.state.value)
    }

    @Test fun startupWithNothingSavedGoesToWelcome() {
        val catalog = FakeCatalog()
        val vm = StartupViewModel(catalog)
        assertEquals(StartupState.Loading, vm.state.value)
        catalog.warmed.complete(Unit)
        assertEquals(StartupState.Welcome, vm.state.value)
    }

    @Test fun startupWithServersButNobodyActiveStillGoesHome() {
        val catalog = catalog(active = null)
        catalog.warmed.complete(Unit)
        assertEquals(StartupState.Home, StartupViewModel(catalog).state.value)
    }

    @Test fun aFailingReadShowsAFailureScreenNotWelcome() {
        val catalog = catalog()
        catalog.awaitActiveFailure = IllegalStateException("db unreadable 10.1.2.3")
        catalog.warmed.complete(Unit)
        assertEquals(StartupState.Failed, StartupViewModel(catalog).state.value)
    }

    @Test fun retryTriesTheWarmAgainAndReloads() {
        val catalog = catalog()
        catalog.awaitActiveFailure = IllegalStateException("db")
        catalog.warmed.complete(Unit)
        val vm = StartupViewModel(catalog)
        catalog.awaitActiveFailure = null
        vm.retry()
        assertEquals(1, catalog.warmRetries)
        assertEquals(StartupState.Home, vm.state.value)
    }

    // --- servers list -------------------------------------------------------------------------------------------

    @Test fun theListShowsServersWithTheirAccountsAndMarksTheActiveOne() {
        val vm = ServersViewModel(catalog(), auth)
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("s1", "s2"), state.servers.map { it.id })
        assertEquals(listOf("amy" to false, "ben" to true), state.servers[0].accounts.sortedBy { it.userName }.map { it.userName to it.isActive })
        assertEquals(listOf("cat" to false), state.servers[1].accounts.map { it.userName to it.isActive })
        assertFalse(state.servers[0].certificateChanged)
        assertTrue("a damaged pin is flagged", state.servers[1].certificateChanged)
    }

    @Test fun switchingAccountCallsThroughAndMovesTheMarker() {
        val catalog = catalog()
        val vm = ServersViewModel(catalog, auth)
        vm.switchTo("s1", "u2")
        assertEquals(listOf("s1" to "u2"), catalog.activated)
        assertEquals("amy", vm.state.value.servers[0].accounts.single { it.isActive }.userName)
    }

    @Test fun signingOutNeedsConfirmationThenRevokesAndUpdatesTheList() {
        val catalog = catalog()
        auth.onSignOut = { s, u -> catalog.accountList.value = catalog.accountList.value.filterNot { it.serverId == s && it.userId == u } }
        val vm = ServersViewModel(catalog, auth)
        vm.askSignOut("s1", "u2")
        assertEquals(Confirm.SignOut("s1", "u2", "amy", "Den"), vm.state.value.confirm)
        assertTrue("nothing happens before confirming", auth.signedOut.isEmpty())
        vm.dismissConfirm()
        assertNull(vm.state.value.confirm)
        assertTrue(auth.signedOut.isEmpty())
        vm.askSignOut("s1", "u2")
        vm.confirm()
        assertEquals(listOf("s1" to "u2"), auth.signedOut)
        assertNull(vm.state.value.confirm)
        assertEquals(listOf("ben"), vm.state.value.servers[0].accounts.map { it.userName })
    }

    @Test fun removingAServerListsWhatGoesWithItAndOnlyActsOnConfirm() {
        val catalog = catalog()
        val vm = ServersViewModel(catalog, auth)
        vm.askRemoveServer("s1")
        val ask = vm.state.value.confirm as Confirm.RemoveServer
        assertEquals("Den", ask.serverName)
        assertEquals(listOf("amy", "ben"), ask.accountNames.sorted())
        assertTrue(catalog.removedServers.isEmpty())
        vm.confirm()
        assertEquals(listOf("s1"), catalog.removedServers)
        assertEquals(listOf("s2"), vm.state.value.servers.map { it.id })
        assertNull(vm.state.value.confirm)
    }

    @Test fun aDoubleTapOnConfirmRunsTheActionOnce() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        auth.onSignOut = { _, _ -> gate.await() }
        val vm = ServersViewModel(catalog(), auth)
        vm.askSignOut("s1", "u2")
        vm.confirm()
        vm.confirm()
        gate.complete(Unit)
        assertEquals(1, auth.signedOut.size)
    }

    @Test fun aFailedSignOutShowsTheAppErrorMessage() {
        auth.onSignOut = { _, _ -> throw IllegalStateException("boom 10.1.2.3") }
        val vm = ServersViewModel(catalog(), auth)
        vm.askSignOut("s1", "u1")
        vm.confirm()
        assertEquals(AppError.from(IllegalStateException()).userMessage, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    // --- home ---------------------------------------------------------------------------------------------------

    @Test fun homeShowsTheActiveServerUserAndLiveConnection() {
        val vm = HomeViewModel(catalog(), connections)
        assertEquals("Den", vm.state.value.serverName)
        assertEquals("ben", vm.state.value.userName)
        assertEquals(ConnectionView.Kind.CHECKING, vm.state.value.connection?.kind)
        connections.stateFor("s1").value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
        assertEquals(ConnectionView.Kind.HOME, vm.state.value.connection?.kind)
        connections.stateFor("s1").value = Connection.Online(address("https://away.example"), isHome = false)
        assertEquals(ConnectionView.Kind.AWAY, vm.state.value.connection?.kind)
    }

    @Test fun homeRetriesAFailedWarmOnStartAndReportsIfItStillFails() {
        val catalog = catalog()
        catalog.warmStillFailing = true
        val vm = HomeViewModel(catalog, connections)
        assertEquals(1, catalog.warmRetries)
        assertTrue(vm.state.value.loadFailed)
    }

    @Test fun theConnectionRetryAlsoRetriesTheWarmAndClearsTheFailure() {
        val catalog = catalog()
        catalog.warmStillFailing = true
        val vm = HomeViewModel(catalog, connections)
        catalog.warmStillFailing = false
        vm.retry()
        assertEquals(2, catalog.warmRetries)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(1, connections.refreshes)
    }

    @Test fun homeWithNobodySignedInSaysSo() {
        val vm = HomeViewModel(catalog(active = null), connections)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.signedOut)
    }

    @Test fun retryRefreshesTheConnection() {
        val vm = HomeViewModel(catalog(), connections)
        vm.retry()
        assertEquals(1, connections.refreshes)
    }

    // --- the connection chip, every variant ---------------------------------------------------------------------

    private val home = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val away = Connection.Online(address("https://away.example"), isHome = false)

    @Test fun onlineVariants() {
        assertEquals(ConnectionView(ConnectionView.Kind.HOME), home.toView())
        assertEquals(ConnectionView(ConnectionView.Kind.AWAY), away.toView())
    }

    @Test fun checkingKeepsWhatItWasSubtly() {
        assertEquals(ConnectionView(ConnectionView.Kind.CHECKING), Connection.Checking().toView())
        assertEquals(ConnectionView.Kind.HOME, Connection.Checking(home).toView().previous)
        assertEquals(ConnectionView.Kind.AWAY, Connection.Checking(away).toView().previous)
    }

    @Test fun everyOfflineReasonCarriesItsPlainEnglishMessage() {
        val expected = mapOf(
            null to AppError.Kind.NETWORK,
            AppError.Kind.TLS_PIN_CHANGED to AppError.Kind.TLS_PIN_CHANGED,
            AppError.Kind.TLS_UNTRUSTED to AppError.Kind.TLS_UNTRUSTED,
            AppError.Kind.CLEARTEXT_BLOCKED to AppError.Kind.CLEARTEXT_BLOCKED,
            AppError.Kind.CLIENT_CERT_REQUIRED to AppError.Kind.CLIENT_CERT_REQUIRED,
        )
        for ((reason, message) in expected) {
            val view = Connection.Offline(reason).toView()
            assertEquals(ConnectionView.Kind.OFFLINE, view.kind)
            assertEquals(reason, view.reason)
            assertEquals("reason $reason", AppError.of(message).userMessage, view.detail)
        }
    }

    @Test fun onlyChangedAndUntrustedCertificatesAskForAReview() {
        assertTrue(Connection.Offline(AppError.Kind.TLS_PIN_CHANGED).toView().needsCertificateReview)
        assertTrue(Connection.Offline(AppError.Kind.TLS_UNTRUSTED).toView().needsCertificateReview)
        assertFalse(Connection.Offline(AppError.Kind.CLEARTEXT_BLOCKED).toView().needsCertificateReview)
        assertFalse(Connection.Offline().toView().needsCertificateReview)
        assertFalse(home.toView().needsCertificateReview)
    }
}
