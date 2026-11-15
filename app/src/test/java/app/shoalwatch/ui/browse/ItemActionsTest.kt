package app.shoalwatch.ui.browse

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class ItemActionsTest {
    @get:Rule val main = MainDispatcherRule()

    private val actions = ItemActions(FakePlaystate(), CoroutineScope(Job()), FakeConnections()) { BEN }

    @Test fun aFlowThatFailsIsReportedAndEndsWithTheFallback() = runTest {
        val seen = actions.guard(BEN, flow { emit(1); throw IllegalStateException("database disk image is malformed") }) { -1 }.toList()
        assertEquals(listOf(1, -1), seen)
        actions.failures.test {
            assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun cancellationIsNeverSwallowedOrReported() = runTest {
        try {
            actions.guard(BEN, flow<Int> { throw CancellationException("stopped") }) { -1 }.toList()
            fail("the cancellation should have propagated")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
        actions.failures.test { expectNoEvents(); cancelAndIgnoreRemainingEvents() }
    }

    @Test fun aHealthyFlowPassesThroughUntouched() = runTest {
        assertEquals(listOf(1, 2), actions.guard(BEN, flowOf(1, 2)) { -1 }.toList())
        actions.failures.test { expectNoEvents(); cancelAndIgnoreRemainingEvents() }
    }

    @Test fun aFailureOfAnAppErrorKeepsItsOwnMessage() = runTest {
        actions.guard(BEN, flow<Int> { throw AppError.of(AppError.Kind.OFFLINE) }) { -1 }.toList()
        actions.failures.test {
            assertEquals(AppError.of(AppError.Kind.OFFLINE).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun anErrorThatIsNotAnExceptionIsNotCaught() = runTest {
        try {
            actions.guard(BEN, flow<Int> { throw OutOfMemoryError() }) { -1 }.toList()
            fail("an Error must not be swallowed")
        } catch (e: OutOfMemoryError) {
            assertTrue(true)
        }
    }
}
