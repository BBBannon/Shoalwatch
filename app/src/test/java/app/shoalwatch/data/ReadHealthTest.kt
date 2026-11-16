package app.shoalwatch.data

import app.shoalwatch.data.servers.ReadHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A store read that fails gives a fail-closed value (never the data from before), is reported, and is subscribed again on Retry. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // AppLog uses android.util.Log
class ReadHealthTest {
    private val reads = ReadHealth("test")

    @Test fun aFailingReadGivesTheEmptyValueAndIsReportedNotThrown() = runTest {
        val seen = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reads.guard("the active account", null) { flow<String?> { emit("ben"); throw IllegalStateException("database disk image is malformed") } }
                .collect { seen += it }
        }
        assertEquals("the value from before the failure is replaced by nobody", listOf("ben", null), seen)
        assertTrue(reads.failed.value)
    }

    /** Review M4: Room throws while BUILDING a flow on a closed database, before any `catch` on the flow could see it. */
    @Test fun aReadThatThrowsWhileItsFlowIsBeingBuiltIsGuardedToo() = runTest {
        val seen = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reads.guard<String?>("the servers", null) { throw IllegalStateException("attempt to re-open an already-closed object") }.collect { seen += it }
        }
        assertEquals(listOf<String?>(null), seen)
        assertTrue(reads.failed.value)
    }

    @Test fun retrySubscribesAFailedReadAgainAndClearsTheReport() = runTest {
        var broken = true
        var subscriptions = 0
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reads.guard("servers", emptyList()) { flow { subscriptions++; if (broken) throw IllegalStateException("disk I/O error"); emit(listOf("Den")); awaitCancellation() } }
                .collect { seen += it }
        }
        assertEquals(listOf(emptyList<String>()), seen)
        broken = false
        assertTrue("there was something to retry", reads.retry())
        assertFalse(reads.failed.value)
        assertEquals(listOf(emptyList(), listOf("Den")), seen)
        assertEquals(2, subscriptions)
    }

    @Test fun aHealthyReadIsNeverRestartedByARetry() = runTest {
        var subscriptions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reads.guard("servers", emptyList<String>()) { flow { subscriptions++; emit(listOf("Den")); awaitCancellation() } }.collect {}
        }
        assertFalse("nothing had failed", reads.retry())
        assertEquals(1, subscriptions)
    }

    @Test fun aOneShotReadThatFailsGivesTheEmptyValue() = runTest {
        assertEquals(emptyList<String>(), reads.guard("servers", emptyList<String>()) { flow { throw IllegalStateException("disk full") } }.first())
        assertTrue(reads.failed.value)
    }

    @Test fun cancellationIsNeverSwallowed() = runTest {
        try {
            reads.guard("servers", emptyList<String>()) { flow { throw CancellationException("stopped") } }.toList()
            fail("the cancellation should have propagated")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
        assertFalse(reads.failed.value)
    }

    @Test fun anErrorIsNotCaught() = runTest {
        try {
            reads.guard("servers", emptyList<String>()) { flow { throw OutOfMemoryError() } }.toList()
            fail("an Error must not be swallowed")
        } catch (_: OutOfMemoryError) {
        }
    }
}
