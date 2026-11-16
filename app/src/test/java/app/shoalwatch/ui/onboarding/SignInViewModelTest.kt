package app.shoalwatch.ui.onboarding

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.QuickConnectProgress
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.FakeRoute
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = FakeAuthenticator()
    private val catalog = FakeCatalog(servers = listOf(testServer()))
    private val connections = FakeConnections()
    private val qc = MutableSharedFlow<QuickConnectProgress>()

    private fun vm(): SignInViewModel {
        auth.quickConnectFlow = { qc }
        return SignInViewModel("srv-1", auth, catalog, connections)
    }

    private fun SignInViewModel.submit(user: String = "ben", pass: String = "hunter2") {
        usePassword()
        onUsernameChange(user)
        password.setTextAndPlaceCursorAtEnd(pass)
        signInWithPassword()
    }

    // --- Quick Connect ------------------------------------------------------------------------------------------

    @Test fun quickConnectStartsFirstAndShowsTheCodeWithACountdown() {
        val vm = vm()
        assertEquals(SignInMode.QuickConnect, vm.state.value.mode)
        assertEquals("Den", vm.state.value.serverName)
        assertEquals(1, auth.quickConnectStarts)
        main.dispatcher.scheduler.runCurrent()
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.AwaitingApproval("ABC123")) }
        assertEquals(QuickConnectUi.Code("ABC123", 300), vm.state.value.quickConnect)
        main.dispatcher.scheduler.advanceTimeBy(3_100)
        assertEquals(QuickConnectUi.Code("ABC123", 297), vm.state.value.quickConnect)
    }

    @Test fun approvalSignsInAndStopsTheCountdown() {
        val vm = vm()
        kotlinx.coroutines.runBlocking {
            qc.emit(QuickConnectProgress.AwaitingApproval("ABC123"))
            qc.emit(QuickConnectProgress.Authenticating)
        }
        assertEquals(QuickConnectUi.Authenticating, vm.state.value.quickConnect)
        main.dispatcher.scheduler.advanceTimeBy(5_000)
        assertEquals(QuickConnectUi.Authenticating, vm.state.value.quickConnect)
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.Success(AccountSummary("srv-1", "u1", "ben"))) }
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun timeoutIsShownWithAWayToStartAgain() {
        val vm = vm()
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.TimedOut) }
        assertEquals(QuickConnectUi.TimedOut, vm.state.value.quickConnect)
        vm.retryQuickConnect()
        assertEquals(2, auth.quickConnectStarts)
        assertEquals(QuickConnectUi.Starting, vm.state.value.quickConnect)
    }

    @Test fun quickConnectSwitchedOffFallsBackToThePasswordWithoutAnErrorBanner() {
        val vm = vm()
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.Failed(AppError.of(AppError.Kind.QUICK_CONNECT_DISABLED))) }
        assertEquals(SignInMode.Password, vm.state.value.mode)
        assertTrue(vm.state.value.quickConnectUnavailable)
    }

    @Test fun otherQuickConnectFailuresShowExactlyTheAppErrorMessage() {
        val vm = vm()
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.Failed(AppError.of(AppError.Kind.SERVER_MISMATCH))) }
        assertEquals(QuickConnectUi.Failed(AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage), vm.state.value.quickConnect)
        assertEquals(SignInMode.QuickConnect, vm.state.value.mode)
    }

    @Test fun anUnreachableServerNeverStartsQuickConnect() {
        auth.routeAnswer = { null }
        val vm = vm()
        assertEquals(0, auth.quickConnectStarts)
        assertEquals(QuickConnectUi.Failed(AppError.of(AppError.Kind.NETWORK).userMessage), vm.state.value.quickConnect)
    }

    @Test fun switchingToPasswordCancelsPolling() {
        var polling = false
        auth.quickConnectFlow = {
            flow {
                polling = true
                try { awaitCancellation() } finally { polling = false }
            }
        }
        val vm = SignInViewModel("srv-1", auth, catalog, connections)
        assertTrue(polling)
        vm.usePassword()
        assertFalse("polling must stop", polling)
        assertEquals(SignInMode.Password, vm.state.value.mode)
    }

    @Test fun leavingTheScreenCancelsPolling() {
        var polling = false
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                auth.quickConnectFlow = {
                    flow {
                        polling = true
                        try { awaitCancellation() } finally { polling = false }
                    }
                }
                SignInViewModel("srv-1", auth, catalog, connections)
            }
        }
        ViewModelProvider.create(store, factory)[SignInViewModel::class]
        assertTrue(polling)
        store.clear() // what navigating away does
        assertFalse("polling must stop", polling)
    }

    @Test fun returningToQuickConnectStartsANewAttempt() {
        val vm = vm()
        vm.usePassword()
        vm.useQuickConnect()
        assertEquals(2, auth.quickConnectStarts)
        assertEquals(SignInMode.QuickConnect, vm.state.value.mode)
    }

    // --- password -----------------------------------------------------------------------------------------------

    @Test fun thePasswordIsPassedOnceAndClearedFromTheFieldAtOnce() {
        val vm = vm()
        vm.submit()
        assertEquals(listOf(Triple("srv-1", "ben", "hunter2")), auth.passwordCalls)
        assertEquals("", vm.password.text.toString())
        assertTrue(vm.state.value.signedIn)
        assertFalse("hunter2" in vm.state.value.toString())
    }

    @Test fun neitherTheUsernameNorTheCodeAppearsInPrintedState() {
        val vm = vm()
        vm.onUsernameChange("benjamin-secret")
        assertFalse("benjamin-secret" in vm.state.value.toString())
        assertFalse("ABC123" in QuickConnectUi.Code("ABC123", 5).toString())
        assertFalse("ABC123" in QuickConnectProgress.AwaitingApproval("ABC123").toString())
    }

    @Test fun theUsernameIsTrimmed() {
        val vm = vm()
        vm.submit(user = "  ben  ")
        assertEquals("ben", auth.passwordCalls.single().second)
    }

    @Test fun aFailedSignInShowsTheAppErrorMessageAndStillClearsThePassword() {
        auth.onPassword = { _, _ -> throw AppError.of(AppError.Kind.UNAUTHORISED) }
        val vm = vm()
        vm.submit()
        assertEquals(AppError.of(AppError.Kind.UNAUTHORISED).userMessage, vm.state.value.error)
        assertEquals("", vm.password.text.toString())
        assertFalse(vm.state.value.submitting)
        assertFalse(vm.state.value.signedIn)
    }

    @Test fun unexpectedFailuresNeverLeakTheirText() {
        auth.onPassword = { _, _ -> throw IOException("POST http://10.1.2.3/Users password=hunter2") }
        val vm = vm()
        vm.submit()
        assertEquals(AppError.from(IOException("x")).userMessage, vm.state.value.error)
        assertFalse("hunter2" in vm.state.value.error.orEmpty())
    }

    @Test fun anUnreachableServerFailsBeforeAnyPasswordIsSent() {
        auth.routeAnswer = { null }
        val vm = vm()
        vm.submit()
        assertTrue(auth.passwordCalls.isEmpty())
        assertEquals(AppError.of(AppError.Kind.NETWORK).userMessage, vm.state.value.error)
        assertEquals("", vm.password.text.toString())
    }

    @Test fun aSecurityProblemOnTheConnectionIsReportedAsThat() {
        auth.routeAnswer = { null }
        connections.stateFor("srv-1").value = Connection.Offline(AppError.Kind.TLS_PIN_CHANGED)
        val vm = vm()
        vm.submit()
        assertEquals(AppError.of(AppError.Kind.TLS_PIN_CHANGED).userMessage, vm.state.value.error)
    }

    @Test fun blankFieldsSubmitNothing() {
        val vm = vm()
        vm.submit(user = " ", pass = "x")
        assertTrue(auth.passwordCalls.isEmpty())
        vm.submit(user = "ben", pass = "")
        assertTrue(auth.passwordCalls.isEmpty())
    }

    // --- where the sign-in goes (review I2) ---------------------------------------------------------------------

    @Test fun anHttpOnlyServerIsTheBaselineAtOpenSoNothingIsBlockedAndQuickConnectStartsAtOnce() {
        auth.routeAnswer = { FakeRoute(it, encrypted = false) }
        val vm = vm()
        assertTrue("the notice is up from the first frame", vm.state.value.unencrypted)
        assertEquals("Quick Connect starts without an extra tap", 1, auth.quickConnectStarts)
        assertFalse(vm.state.value.quickConnect is QuickConnectUi.Failed)
    }

    @Test fun anHttpOnlyServerWithQuickConnectOffGoesStraightToThePasswordAndTheFirstSubmitSends() {
        auth.routeAnswer = { FakeRoute(it, encrypted = false) }
        val vm = vm()
        kotlinx.coroutines.runBlocking { qc.emit(QuickConnectProgress.Failed(AppError.of(AppError.Kind.QUICK_CONNECT_DISABLED))) }
        assertEquals(SignInMode.Password, vm.state.value.mode)
        assertTrue(vm.state.value.quickConnectUnavailable)
        assertTrue(vm.state.value.unencrypted)
        vm.onUsernameChange("ben")
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.signInWithPassword()
        assertEquals(1, auth.passwordCalls.size)
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun usingQuickConnectInsteadIsBlockedOnceWhenTheSecureRouteHasGoneAway() {
        val vm = vm()
        vm.usePassword()
        assertFalse(vm.state.value.unencrypted)
        auth.routeAnswer = { FakeRoute(it, encrypted = false) }
        val before = auth.quickConnectStarts
        vm.useQuickConnect()
        assertEquals("blocked once", before, auth.quickConnectStarts)
        assertTrue(vm.state.value.unencrypted)
        assertTrue(vm.state.value.quickConnect is QuickConnectUi.Failed)
        vm.useQuickConnect()
        assertEquals(before + 1, auth.quickConnectStarts)
    }

    @Test fun quickConnectIsNotStartedWhenTheRouteTurnsUnencryptedUnderAnEncryptedScreen() {
        val vm = vm()
        assertEquals(1, auth.quickConnectStarts)
        assertFalse(vm.state.value.unencrypted)
        auth.routeAnswer = { FakeRoute(it, encrypted = false) } // https stopped answering
        vm.retryQuickConnect()
        assertEquals("not started", 1, auth.quickConnectStarts)
        assertTrue("notice now shown", vm.state.value.unencrypted)
        assertTrue(vm.state.value.quickConnect is QuickConnectUi.Failed)
        vm.retryQuickConnect() // second explicit request, notice seen
        assertEquals(2, auth.quickConnectStarts)
    }

    // --- the route flips from https to http at submit (review F1) ---------------------------------------------

    @Test fun aRouteThatTurnsUnencryptedAtSubmitSendsNothingShowsTheNoticeAndKeepsThePassword() {
        val vm = vm()
        vm.usePassword()
        vm.onUsernameChange("ben")
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        assertFalse(vm.state.value.unencrypted)
        auth.routeAnswer = { FakeRoute(it, encrypted = false) } // https stopped answering after the user last looked
        vm.signInWithPassword()
        assertTrue("no credential call was made", auth.passwordCalls.isEmpty())
        assertTrue("the notice is now up", vm.state.value.unencrypted)
        assertEquals("the typed password stays in the field", "hunter2", vm.password.text.toString())
        assertFalse(vm.state.value.submitting)
        assertFalse(vm.state.value.signedIn)
        assertTrue("an inline message tells the user what to do", vm.state.value.error != null)
        assertFalse("hunter2" in vm.state.value.error.orEmpty())
    }

    @Test fun theNextExplicitSubmitSendsOnceTheNoticeHasBeenShown() {
        val vm = vm()
        vm.usePassword()
        vm.onUsernameChange("ben")
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        val httpOnly = FakeRoute("srv-1", encrypted = false)
        auth.routeAnswer = { httpOnly }
        vm.signInWithPassword()
        vm.signInWithPassword()
        assertEquals(listOf(Triple("srv-1", "ben", "hunter2")), auth.passwordCalls)
        assertTrue(auth.passwordRoutes.single() === httpOnly)
        assertEquals("", vm.password.text.toString())
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun aRouteThatWasAlreadyUnencryptedWhenTheNoticeShowedSendsOnTheFirstSubmit() {
        auth.routeAnswer = { FakeRoute(it, encrypted = false) }
        val vm = vm()
        vm.submit() // usePassword() refreshes the notice, then the submit goes straight through
        assertEquals(1, auth.passwordCalls.size)
        assertTrue(vm.state.value.unencrypted)
        assertTrue(vm.state.value.signedIn)
    }

    @Test fun anEncryptedRouteIsNotFlagged() {
        val vm = vm()
        assertFalse(vm.state.value.unencrypted)
        vm.submit()
        assertFalse(vm.state.value.unencrypted)
    }

    @Test fun thePasswordGoesOnTheRouteAuthRepositoryGaveAndTheFlagFollowsIt() {
        val vm = vm()
        assertFalse(vm.state.value.unencrypted)
        val httpOnly = FakeRoute("srv-1", encrypted = false)
        auth.routeAnswer = { httpOnly } // the https address stopped answering
        vm.submit() // usePassword() shows the notice first, so this submit may go ahead
        assertTrue(auth.passwordRoutes.single() === httpOnly)
        assertTrue("flagged for the attempt that went over http", vm.state.value.unencrypted)
    }

    @Test fun switchingToThePasswordAsksForAFreshRouteSoTheNoticeIsRightWhileTyping() {
        val vm = vm()
        auth.routeAnswer = { FakeRoute(it, encrypted = false) }
        vm.usePassword()
        assertTrue(vm.state.value.unencrypted)
        assertTrue(auth.passwordCalls.isEmpty())
    }

    @Test fun aServerRemovedMeanwhileIsReported() {
        catalog.serverList.value = emptyList()
        val vm = vm()
        assertTrue(vm.state.value.serverMissing)
        assertNull(vm.state.value.serverName)
    }
}
