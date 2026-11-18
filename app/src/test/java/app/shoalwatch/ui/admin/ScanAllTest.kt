package app.shoalwatch.ui.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminSamples.fullScanIdle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round 5 (re-review M1): "the full scan finished" is told only for the profile whose scan was seen running. */
class ScanAllTest {
    private val first = Session(S1, U1)
    private val second = Session(S1, U2)
    private val finished = mutableListOf<Session>()
    private val scan = ScanAll(FakeAdmin(), finished::add)
    private val running = fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 10)

    @Test fun aFinishIsToldOnceForTheSameProfile() {
        scan.keep(first, running); scan.keep(first, running); scan.keep(first, fullScanIdle); scan.keep(first, fullScanIdle)
        assertEquals(listOf(first), finished)
    }

    @Test fun anotherProfilesIdleAnswerIsNotAFinish() {
        scan.keep(first, running)
        scan.keep(second, fullScanIdle) // the gate's session changed between two answers
        assertEquals(emptyList<Session>(), finished)
        scan.keep(second, running); scan.keep(second, fullScanIdle)
        assertEquals(listOf(second), finished)
    }

    @Test fun anUnknownAnswerOrADropIsNotAFinish() {
        scan.keep(first, running); scan.keep(first, null); scan.keep(first, fullScanIdle)
        scan.keep(first, running); scan.drop(); scan.keep(first, fullScanIdle)
        assertEquals(emptyList<Session>(), finished)
    }
}
