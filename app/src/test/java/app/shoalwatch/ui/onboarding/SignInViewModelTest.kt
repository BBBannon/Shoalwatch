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
        connections.onlineAnswer = null
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
        connections.onlineAnswer = null
        val vm = vm()
        vm.submit()
        assertTrue(auth.passwordCalls.isEmpty())
        assertEquals(AppError.of(AppError.Kind.NETWORK).userMessage, vm.state.value.error)
        assertEquals("", vm.password.text.toString())
    }

    @Test fun aSecurityProblemOnTheConnectionIsReportedAsThat() {
        connections.onlineAnswer = null
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

    @Test fun aServerRemovedMeanwhileIsReported() {
        catalog.serverList.value = emptyList()
        val vm = vm()
        assertTrue(vm.state.value.serverMissing)
        assertNull(vm.state.value.serverName)
    }
}
