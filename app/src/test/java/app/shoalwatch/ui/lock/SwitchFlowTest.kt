package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The one switch flow for Home and Servers (Milestone 7 Task 6), on virtual time with the fake switcher. */
@RunWith(RobolectricTestRunner::class)
class SwitchFlowTest {
    private val anna = Session("s1", "anna")
    private val kid = Session("s1", "kid")
    private val switcher = FakeSwitcher().apply { protected += anna }
    private val errors = mutableListOf<AppError>()

    private fun TestScope.flow() = SwitchFlow(switcher, backgroundScope) { errors += it }

    @Test fun anUnprotectedProfileSwitchesAtOnceWithNoPrompt() = runTest {
        val f = flow()
        f.request("s1", "kid", "Kid")
        runCurrent()
        assertNull(f.prompt.value)
        assertEquals(listOf(kid), switcher.switchToCalls)
        assertTrue(switcher.pinCalls.isEmpty())
    }

    @Test fun aProtectedProfileAsksWithItsName() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        assertEquals("Anna", f.prompt.value?.userName)
        assertTrue(switcher.pinCalls.isEmpty())
    }

    @Test fun aWrongPinShowsTheAnswerEmptiesTheFieldAndKeepsThePrompt() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        f.pin.setTextAndPlaceCursorAtEnd("1111")
        f.submit()
        runCurrent()
        assertEquals(PinCheck.Wrong(4), f.prompt.value?.answer)
        assertEquals("", f.pin.text.toString())
        f.pin.setTextAndPlaceCursorAtEnd("4826")
        f.submit()
        runCurrent()
        assertNull("right: the prompt goes", f.prompt.value)
        assertEquals(listOf(anna to "1111", anna to "4826"), switcher.pinCalls)
        assertTrue(switcher.pinArrays.all { a -> a.all { it == '\u0000' } })
    }

    @Test fun dismissCallsNothing() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        f.pin.setTextAndPlaceCursorAtEnd("48")
        f.dismiss()
        runCurrent()
        assertNull(f.prompt.value)
        assertEquals("", f.pin.text.toString())
        assertTrue(switcher.pinCalls.isEmpty())
    }

    @Test fun aSecondSubmitWhileOneRunsMakesOneCall() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        switcher.hold = CompletableDeferred()
        f.pin.setTextAndPlaceCursorAtEnd("4826")
        f.submit()
        runCurrent()
        f.pin.setTextAndPlaceCursorAtEnd("4826")
        f.submit()
        runCurrent()
        switcher.hold!!.complete(Unit)
        runCurrent()
        assertEquals(1, switcher.pinCalls.size)
    }

    @Test fun aTryThatCannotBeSavedIsAPlainErrorAndTheGoneProfileIsReported() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        switcher.failure = AppError.of(AppError.Kind.UNKNOWN)
        f.pin.setTextAndPlaceCursorAtEnd("4826")
        f.submit()
        runCurrent()
        assertTrue(f.prompt.value!!.failed)
        switcher.failure = null
        switcher.gone += kid
        f.request("s1", "kid", "Kid")
        runCurrent()
        assertEquals(listOf(AppError.Kind.NOT_SIGNED_IN), errors.map { it.kind })
    }

    @Test fun thePinIsNeverInThePrompt() = runTest {
        val f = flow()
        f.request("s1", "anna", "Anna")
        runCurrent()
        f.pin.setTextAndPlaceCursorAtEnd("482615")
        val p = f.prompt.value!!
        assertEquals("SwitchPrompt(<redacted>)", p.toString())
        val fields = SwitchPrompt::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.apply { isAccessible = true }.get(p) }
        assertFalse(fields.any { it?.toString()?.contains("482615") == true })
        assertFalse(fields.any { it is CharArray || it is CharSequence && it !is String })
    }
}
