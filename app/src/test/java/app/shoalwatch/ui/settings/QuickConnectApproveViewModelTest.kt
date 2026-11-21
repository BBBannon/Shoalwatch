package app.shoalwatch.ui.settings

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ApproveOutcome
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuickConnectApproveViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ben = AccountSummary(S1, U1, "ben")
    private val anna = AccountSummary(S1, U2, "anna")
    private val catalog = FakeCatalog(accounts = listOf(ben, anna), active = ben)
    private val asked = mutableListOf<Pair<Session, String>>()
    private var answer = CompletableDeferred(ApproveOutcome.APPROVED)
    private var protectedProfile = false
    private val pinsChecked = mutableListOf<Pair<Session, String>>()
    private val vm = QuickConnectApproveViewModel(
        catalog, { s, code -> asked += s to code; answer.await() }, { protectedProfile },
        { s, pin -> pinsChecked += s to String(pin); if (String(pin) == "1234") PinCheck.Correct else PinCheck.Wrong(2) },
    )

    @Test fun aProtectedProfileMustTypeItsPinBeforeAnythingIsSent() {
        protectedProfile = true
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        assertTrue(vm.state.value.pinAsk)
        assertTrue("nothing sent yet", asked.isEmpty())
        vm.pin.setTextAndPlaceCursorAtEnd("1111")
        vm.submitPin()
        assertEquals(PinCheck.Wrong(2), vm.state.value.pinAnswer)
        assertTrue("a wrong PIN sends nothing", asked.isEmpty())
        assertEquals("", vm.pin.text.toString())
        vm.pin.setTextAndPlaceCursorAtEnd("1234")
        vm.submitPin()
        assertEquals(listOf(Session(S1, U1) to "1234"), pinsChecked.drop(1))
        assertEquals(listOf(Session(S1, U1) to "123456"), asked)
        assertFalse(vm.state.value.pinAsk)
        assertEquals(ApproveOutcome.APPROVED, vm.state.value.outcome)
    }

    @Test fun cancellingThePinSendsNothing() {
        protectedProfile = true
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        vm.pin.setTextAndPlaceCursorAtEnd("12")
        vm.cancelPin()
        assertFalse(vm.state.value.pinAsk)
        assertEquals("", vm.pin.text.toString())
        assertTrue(asked.isEmpty())
        assertTrue(pinsChecked.isEmpty())
    }

    @Test fun anUnprotectedProfileIsNotAskedForAPin() {
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        assertFalse(vm.state.value.pinAsk)
        assertEquals(1, asked.size)
        assertTrue(pinsChecked.isEmpty())
    }

    @Test fun itSaysWhoseSignInTheOtherDeviceGets() {
        assertEquals("ben", vm.state.value.userName)
        assertEquals(Session(S1, U1), vm.state.value.session)
    }

    @Test fun approvingSendsTheTypedCodeForTheActiveProfileAndClearsItAfter() {
        vm.code.setTextAndPlaceCursorAtEnd("123 456")
        assertTrue(vm.codeReady)
        vm.approve()
        assertEquals(listOf(Session(S1, U1) to "123 456"), asked)
        assertEquals(ApproveOutcome.APPROVED, vm.state.value.outcome)
        assertFalse(vm.state.value.running)
        assertEquals("", vm.code.text.toString())
    }

    @Test fun oneAtATime() {
        answer = CompletableDeferred()
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        vm.approve()
        assertEquals(1, asked.size)
        assertTrue(vm.state.value.running)
    }

    @Test fun aFailureKeepsTheCodeSoItCanBeFixed() {
        answer = CompletableDeferred(ApproveOutcome.NOT_FOUND)
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        assertEquals(ApproveOutcome.NOT_FOUND, vm.state.value.outcome)
        assertEquals("123456", vm.code.text.toString())
    }

    @Test fun aProfileChangeClearsTheCodeAndDropsTheAnswer() {
        answer = CompletableDeferred()
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        catalog.activeFlow.value = anna
        assertEquals("", vm.code.text.toString())
        answer.complete(ApproveOutcome.APPROVED)
        assertNull(vm.state.value.outcome)
        assertEquals("anna", vm.state.value.userName)
        assertFalse(vm.state.value.running)
    }

    @Test fun withNobodySignedInNothingIsSent() {
        catalog.activeFlow.value = null
        vm.code.setTextAndPlaceCursorAtEnd("123456")
        vm.approve()
        assertTrue(asked.isEmpty())
    }
}
