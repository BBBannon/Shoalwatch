package app.shoalwatch.ui

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.nav.StartupState
import app.shoalwatch.ui.nav.StartupViewModel
import app.shoalwatch.ui.servers.Confirm
import app.shoalwatch.ui.servers.ServersNotice
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

    @Test fun switchingAccountGoesThroughTheSwitcherAndMovesTheMarker() {
        val catalog = catalog()
        val switcher = app.shoalwatch.ui.lock.FakeSwitcher(catalog)
        val vm = ServersViewModel(catalog, auth, switcher = switcher)
        vm.switchTo("s1", "u2")
        assertEquals(listOf(app.shoalwatch.data.servers.Session("s1", "u2")), switcher.switchToCalls)
        assertTrue("never the catalog's setActive", catalog.activated.isEmpty())
        assertEquals("amy", vm.state.value.servers[0].accounts.single { it.isActive }.userName)
    }

    // --- Milestone 7: protected profiles and the grown-up check ------------------------------------------------------

    private val amyS = app.shoalwatch.data.servers.Session("s1", "u2")
    private val catS = app.shoalwatch.data.servers.Session("s2", "u3")

    private fun guarded(catalog: FakeCatalog, household: app.shoalwatch.ui.lock.FakeHousehold, switcher: app.shoalwatch.ui.lock.FakeSwitcher = app.shoalwatch.ui.lock.FakeSwitcher(catalog)) =
        ServersViewModel(
            catalog, FakeAuthenticator(catalog), switcher = switcher, household = household,
            protection = kotlinx.coroutines.flow.MutableStateFlow(setOf(amyS, catS)),
        )

    @Test fun aProtectedRowIsMarkedAndAsksItsPinBeforeSwitching() {
        val catalog = catalog()
        val switcher = app.shoalwatch.ui.lock.FakeSwitcher(catalog).apply { protected += amyS }
        val vm = guarded(catalog, app.shoalwatch.ui.lock.FakeHousehold(ask = false), switcher)
        assertTrue(vm.state.value.servers[0].accounts.single { it.userName == "amy" }.isProtected)
        assertFalse(vm.state.value.servers[0].accounts.single { it.userName == "ben" }.isProtected)
        vm.switchTo("s1", "u2")
        assertEquals("amy", vm.switchPrompt.value?.userName)
        assertEquals("ben", vm.state.value.servers[0].accounts.single { it.isActive }.userName)
        vm.switchPin.edit { append("4826") }
        vm.submitSwitch()
        assertNull(vm.switchPrompt.value)
        assertEquals("amy", vm.state.value.servers[0].accounts.single { it.isActive }.userName)
        assertTrue(catalog.activated.isEmpty())
    }

    @Test fun settingsRemoveServerAndSigningOutAnotherProtectedProfileRunOnlyAfterARightPin() {
        val catalog = catalog()
        val household = app.shoalwatch.ui.lock.FakeHousehold(ask = true, grownUps = listOf(amy, other))
        val vm = guarded(catalog, household)
        vm.openSettings("s1")
        assertEquals(listOf(amy, other), vm.grownUpAsk.value?.grownUps)
        assertNull("several grown-ups: none chosen yet", vm.grownUpAsk.value?.chosen)
        vm.chooseGrownUp(other)
        vm.grownUpPin.edit { append("1111") }
        vm.submitGrownUp()
        assertNull(vm.state.value.openSettings)
        vm.grownUpPin.edit { append("4826") }
        vm.submitGrownUp()
        assertEquals("s1", vm.state.value.openSettings)
        vm.settingsOpened()

        vm.askRemoveServer("s1")
        assertNull("no confirm before the PIN", vm.state.value.confirm)
        vm.dismissGrownUp()
        assertNull(vm.state.value.confirm)
        vm.askRemoveServer("s1")
        vm.chooseGrownUp(amy)
        vm.grownUpPin.edit { append("4826") }
        vm.submitGrownUp()
        assertTrue(vm.state.value.confirm is Confirm.RemoveServer)
        vm.dismissConfirm()

        vm.askSignOut("s1", "u2") // amy: protected and not active, so her own PIN
        val ask = vm.grownUpAsk.value!!
        assertTrue(ask.signOut)
        assertEquals(amy, ask.chosen)
        assertNull(vm.state.value.confirm)
        vm.grownUpPin.edit { append("4826") }
        vm.submitGrownUp()
        assertEquals(amyS, household.confirms.last().first)
        assertTrue(vm.state.value.confirm is Confirm.SignOut)
    }

    /** Final review M5: removing a server signs its profiles out, so a protected one that is not active is asked for by its own PIN. */
    @Test fun removingAServerWithAnotherProtectedProfileAsksThatProfilesOwnPin() {
        val catalog = catalog()
        val household = app.shoalwatch.ui.lock.FakeHousehold(ask = true, grownUps = listOf(amy, other))
        val vm = guarded(catalog, household)
        vm.askRemoveServer("s2") // cat: protected, on s2 and not active
        val ask = vm.grownUpAsk.value!!
        assertTrue(ask.signOut)
        assertEquals(other, ask.chosen)
        assertNull(vm.state.value.confirm)
        vm.grownUpPin.edit { append("4826") }
        vm.submitGrownUp()
        assertEquals(catS, household.confirms.last().first)
        assertTrue(vm.state.value.confirm is Confirm.RemoveServer)
    }

    @Test fun aServerWithTwoOtherProtectedProfilesIsNotRemovedUntilOneIsSignedOut() {
        val dan = AccountSummary("s1", "u4", "dan")
        val catalog = FakeCatalog(servers = listOf(testServer("s1"), testServer("s2")), accounts = listOf(ben, amy, dan, other), active = ben)
        val household = app.shoalwatch.ui.lock.FakeHousehold(ask = true, grownUps = listOf(amy, dan, other))
        val vm = ServersViewModel(
            catalog, FakeAuthenticator(catalog), switcher = app.shoalwatch.ui.lock.FakeSwitcher(catalog), household = household,
            protection = kotlinx.coroutines.flow.MutableStateFlow(setOf(amyS, app.shoalwatch.data.servers.Session("s1", "u4"), catS)),
        )
        vm.askRemoveServer("s1")
        assertNull("no one PIN can cover both", vm.grownUpAsk.value)
        assertNull(vm.state.value.confirm)
        assertTrue(vm.state.value.signOutFirst)
        vm.askSignOut("s1", "u4") // the way on: sign one out with its own PIN
        assertFalse(vm.state.value.signOutFirst)
    }

    @Test fun signingOutTheActiveOrAnUnprotectedProfileAsksNothing() {
        val catalog = catalog(active = amy)
        val household = app.shoalwatch.ui.lock.FakeHousehold(ask = true, grownUps = listOf(amy, other))
        val vm = guarded(catalog, household)
        vm.askSignOut("s1", "u2") // amy is active
        assertNull(vm.grownUpAsk.value)
        assertTrue(vm.state.value.confirm is Confirm.SignOut)
        vm.dismissConfirm()
        vm.askSignOut("s1", "u1") // ben is not protected
        assertNull(vm.grownUpAsk.value)
        assertTrue(vm.state.value.confirm is Confirm.SignOut)
    }

    @Test fun aRefusalFromBelowTheScreenSaysAGrownUpIsNeededAndChangesNothing() {
        val catalog = catalog()
        val auth = object : app.shoalwatch.data.servers.Authenticator by FakeAuthenticator(catalog) {
            override suspend fun removeServer(serverId: String): Boolean = throw app.shoalwatch.security.HouseholdLockedException()
        }
        val vm = ServersViewModel(catalog, auth, household = app.shoalwatch.ui.lock.FakeHousehold(ask = false))
        vm.askRemoveServer("s1")
        vm.confirm()
        assertTrue(vm.state.value.householdRace)
        assertNull(vm.state.value.error)
        assertEquals(listOf("s1", "s2"), vm.state.value.servers.map { it.id })
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
        val auth = FakeAuthenticator(catalog)
        val vm = ServersViewModel(catalog, auth)
        vm.askRemoveServer("s1")
        val ask = vm.state.value.confirm as Confirm.RemoveServer
        assertEquals("Den", ask.serverName)
        assertEquals(listOf("amy", "ben"), ask.accountNames.sorted())
        assertTrue(auth.removedServers.isEmpty())
        vm.confirm()
        assertEquals("removal goes through the revoking path", listOf("s1"), auth.removedServers)
        assertEquals(listOf("s2"), vm.state.value.servers.map { it.id })
        assertNull(vm.state.value.confirm)
        assertNull(vm.state.value.notice)
    }

    @Test fun aRemovalThatCouldNotReachTheServerSaysSo() {
        val catalog = catalog()
        val auth = FakeAuthenticator(catalog).apply { revoked = false }
        val vm = ServersViewModel(catalog, auth)
        vm.askRemoveServer("s1")
        vm.confirm()
        assertEquals(ServersNotice.RemovedHereOnly, vm.state.value.notice)
        assertEquals(listOf("s2"), vm.state.value.servers.map { it.id })
        assertNull(vm.state.value.error)
        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    @Test fun aSignOutThatCouldNotReachTheServerSaysSo() {
        auth.revoked = false
        val vm = ServersViewModel(catalog(), auth)
        vm.askSignOut("s1", "u2")
        vm.confirm()
        assertEquals(listOf("s1" to "u2"), auth.signedOut)
        assertEquals(ServersNotice.SignedOutHereOnly, vm.state.value.notice)
        assertNull(vm.state.value.error)
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
