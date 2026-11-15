package app.shoalwatch.data.db

import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-local record of what an action changed, and when: what a server answer asked for earlier must not undo. */
class LocalChangeLogTest {
    private var now = 1_000_000L
    private val log = LocalChangeLog { now }
    private val a = Session("srv", "ann")
    private val b = Session("srv", "bob")
    private val minute = 60_000L

    @Test fun anAnswerAskedForBeforeOrAtTheChangeIsOlderAndOneAskedAfterIsNot() {
        log.record(a, "x")
        assertTrue(log.newerThan(a, "x", now - 1))
        assertTrue("the same millisecond counts as the change being newer", log.newerThan(a, "x", now))
        assertFalse(log.newerThan(a, "x", now + 1))
    }

    @Test fun itIsPerUserAndPerItem() {
        log.record(a, "x")
        assertFalse(log.newerThan(b, "x", now - 1))
        assertFalse(log.newerThan(a, "y", now - 1))
    }

    @Test fun anEntryYoungerThanTenMinutesIsNeverDropped() {
        log.record(a, "x")
        val at = now
        now += 9 * minute + 59_000
        repeat(50) { log.record(b, "filler$it") } // the pruning that happens when something is recorded
        assertEquals(at, log.changedAt(a, "x"))
        assertTrue(log.newerThan(a, "x", at))
    }

    @Test fun anEntryOlderThanTenMinutesIsForgotten() {
        log.record(a, "x")
        val at = now
        now += 10 * minute + 1
        log.record(b, "y")
        assertNull(log.changedAt(a, "x"))
        assertFalse(log.newerThan(a, "x", at))
    }

    @Test fun theRecordIsBoundedAndKeepsTheNewest() {
        repeat(6_000) { log.record(a, "item$it") }
        assertTrue(log.size <= 5_000)
        assertEquals(now, log.changedAt(a, "item5999"))
    }

    @Test fun aListRemovalIsRememberedUntilForgotten() {
        log.record(a, "x", removedFrom = "resume")
        assertTrue(log.removedFrom(a, "x", "resume"))
        assertFalse(log.removedFrom(a, "x", "nextUp"))
        log.forgetRemoval(a, "x", "resume")
        assertFalse(log.removedFrom(a, "x", "resume"))
    }
}
