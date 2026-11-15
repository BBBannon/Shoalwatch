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


    // --- the follow-up refresh of a change that worked --------------------------------------------------------------

    @Test fun aChangeThatWorkedIsNotReportedAsFailedBecauseItsFollowUpRefreshFailed() = runTest {
        val playstate = FakePlaystate()
        val a = ItemActions(playstate, CoroutineScope(main.dispatcher), FakeConnections()) { BEN }
        a.setFavourite(BEN, "x", true) { throw AppError.of(AppError.Kind.OFFLINE) }
        assertEquals(listOf("favourite:ben:x:true"), playstate.calls)
        a.failures.test {
            assertEquals(ItemActions.REFRESH_AFTER_CHANGE_FAILED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(a.working.value.isEmpty())
    }

    @Test fun aChangeThatFailedIsStillReportedWithItsOwnMessageAndTheFollowUpDoesNotRun() = runTest {
        val playstate = FakePlaystate().apply { failWith = AppError.of(AppError.Kind.OFFLINE) }
        val a = ItemActions(playstate, CoroutineScope(main.dispatcher), FakeConnections()) { BEN }
        var followUp = 0
        a.setFavourite(BEN, "x", true) { followUp++ }
        a.failures.test {
            assertEquals(AppError.of(AppError.Kind.OFFLINE).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, followUp)
    }

    // --- overlapping pulls ------------------------------------------------------------------------------------------

    @Test fun twoOverlappingPullsKeepTheIndicatorUntilTheLastOneEnds() {
        val a = ItemActions(FakePlaystate(), CoroutineScope(main.dispatcher), FakeConnections()) { BEN }
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val second = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.pull(BEN) { first.await() }
        a.pull(BEN) { second.await() }
        assertEquals(setOf(BEN), a.pulling.value)
        first.complete(Unit)
        assertEquals("one pull is still running", setOf(BEN), a.pulling.value)
        second.complete(Unit)
        assertTrue(a.pulling.value.isEmpty())
    }

    @Test fun pullsOfTwoSessionsAreCountedApart() {
        val a = ItemActions(FakePlaystate(), CoroutineScope(main.dispatcher), FakeConnections()) { BEN }
        val ben = kotlinx.coroutines.CompletableDeferred<Unit>()
        val amy = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.pull(BEN) { ben.await() }
        a.pull(AMY) { amy.await() }
        assertEquals(setOf(BEN, AMY), a.pulling.value)
        ben.complete(Unit)
        assertEquals(setOf(AMY), a.pulling.value)
        amy.complete(Unit)
        assertTrue(a.pulling.value.isEmpty())
    }
}
