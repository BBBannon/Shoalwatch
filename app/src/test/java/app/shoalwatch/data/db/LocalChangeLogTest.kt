package app.shoalwatch.data.db

import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-local record of what an action changed: ordered by a sequence number, expired by a monotonic clock. */
class LocalChangeLogTest {
    private val mono = TestMonotonic()
    private val log = LocalChangeLog(mono)
    private val a = Session("srv", "ann")
    private val b = Session("srv", "bob")
    private val minute = 60_000L

    @Test fun aRequestStampedBeforeAChangeIsOlderAndOneStampedAtOrAfterItIsNot() {
        val before = log.stamp()
        log.record(a, "x")
        assertTrue("stamped before the change: dropped", log.newerThan(a, "x", before))
        assertFalse("stamped straight after the change: applies", log.newerThan(a, "x", log.stamp()))
    }

    @Test fun itIsPerUserAndPerItem() {
        val before = log.stamp()
        log.record(a, "x")
        assertFalse(log.newerThan(b, "x", before))
        assertFalse(log.newerThan(a, "y", before))
    }

    @Test fun everyChangeTakesTheNextNumberWhateverTheTime() {
        val first = log.stamp()
        log.record(a, "x")
        log.record(a, "y")
        log.record(a, "z")
        assertEquals(first + 3, log.stamp())
    }

    @Test fun anEntryYoungerThanTenMinutesIsNeverDropped() {
        val before = log.stamp()
        log.record(a, "x")
        mono.ms += 9 * minute + 59_000
        repeat(50) { log.record(b, "filler$it") } // the pruning that happens when something is recorded
        assertTrue(log.touched(a, "x"))
        assertTrue(log.newerThan(a, "x", before))
    }

    @Test fun anEntryOlderThanTenMinutesOnTheMonotonicClockIsForgotten() {
        val before = log.stamp()
        log.record(a, "x")
        mono.ms += 10 * minute + 1
        log.record(b, "y")
        assertFalse(log.touched(a, "x"))
        assertFalse(log.newerThan(a, "x", before))
    }

    @Test fun theRecordIsBoundedAndKeepsTheNewest() {
        val before = log.stamp()
        repeat(6_000) { log.record(a, "item$it") }
        assertTrue(log.size <= 5_000)
        assertTrue(log.newerThan(a, "item5999", before))
        assertFalse("the oldest were evicted first", log.touched(a, "item0"))
    }

    @Test fun aListRemovalIsRememberedUntilForgotten() {
        log.record(a, "x", removedFrom = "resume")
        assertTrue(log.removedFrom(a, "x", "resume"))
        assertFalse(log.removedFrom(a, "x", "nextUp"))
        log.forgetRemoval(a, "x", "resume")
        assertFalse(log.removedFrom(a, "x", "resume"))
    }
}
