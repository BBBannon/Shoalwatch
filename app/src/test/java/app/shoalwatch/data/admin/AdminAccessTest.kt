package app.shoalwatch.data.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminAccessTest {
    private val ben = Session(S1, U1)
    private val other = Session(S1, U2)
    private var calls = 0
    private var answer: suspend (Session) -> AdminMe = { s -> AdminMe(s.userId, isAdministrator = true, isDisabled = false) }
    private val access = AdminAccess { s -> calls++; answer(s) }

    private fun fails(kind: AppError.Kind): suspend (Session) -> AdminMe = { throw AppError.of(kind) }

    @Test fun theFirstCheckWaitsForTheActiveSession() = runTest {
        val check = async { access.check(ben) }
        runCurrent()
        assertFalse(check.isCompleted)
        access.sessionChanged(ben)
        assertEquals(AdminStatus.ADMIN, check.await())
    }

    @Test fun withNoActiveSessionAfterFiveSecondsTheAnswerIsNo() = runTest {
        val check = async { access.check(ben) }
        advanceTimeBy(5_001)
        assertEquals(AdminStatus.NOT_ADMIN, check.await())
        assertEquals(0, calls)
    }

    @Test fun aSessionThatIsNotTheActiveOneSendsNothing() = runTest {
        access.sessionChanged(other)
        assertEquals(AdminStatus.NOT_ADMIN, access.check(ben))
        assertEquals(0, calls)
        assertNull(access.state.value)
    }

    @Test fun anAdministrator() = runTest {
        access.sessionChanged(ben)
        assertEquals(AdminStatus.ADMIN, access.check(ben))
        assertEquals(AdminState(ben, AdminStatus.ADMIN), access.state.value)
        assertTrue(access.allows(ben))
        assertFalse("another session on the same server", access.allows(other))
    }

    @Test fun everythingElseIsNotAdmin() = runTest {
        access.sessionChanged(ben)
        val answers = listOf(
            AdminMe(U1, isAdministrator = false, isDisabled = false),
            AdminMe(U1, isAdministrator = true, isDisabled = true),
            AdminMe(U2, isAdministrator = true, isDisabled = false),
            AdminMe(null, isAdministrator = true, isDisabled = false),
        )
        for (me in answers) {
            answer = { me }
            assertEquals(AdminStatus.NOT_ADMIN, access.check(ben))
            assertEquals(AdminState(ben, AdminStatus.NOT_ADMIN), access.state.value)
            assertFalse(access.allows(ben))
        }
    }

    @Test fun refusalsAreNotAdmin() = runTest {
        access.sessionChanged(ben)
        for (kind in listOf(AppError.Kind.UNAUTHORISED, AppError.Kind.FORBIDDEN, AppError.Kind.NOT_SIGNED_IN)) {
            access.check(ben)
            answer = fails(kind)
            assertEquals(kind.name, AdminStatus.NOT_ADMIN, access.check(ben))
            assertFalse(access.allows(ben))
            answer = { s -> AdminMe(s.userId, isAdministrator = true, isDisabled = false) }
        }
    }

    @Test fun beingUnreachableKeepsAnEarlierAnswer() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR, AppError.Kind.TLS_UNTRUSTED)) {
            answer = fails(kind)
            assertEquals(kind.name, AdminStatus.ADMIN, access.check(ben))
            assertTrue(access.allows(ben))
        }
    }

    @Test fun beingUnreachableWithNoEarlierAnswerIsUnknown() = runTest {
        access.sessionChanged(ben)
        answer = fails(AppError.Kind.OFFLINE)
        assertEquals(AdminStatus.UNKNOWN, access.check(ben))
        assertNull(access.state.value)
    }

    @Test fun anAnswerArrivingAfterASwitchIsDropped() = runTest {
        access.sessionChanged(ben)
        val gate = CompletableDeferred<Unit>()
        answer = { s -> gate.await(); AdminMe(s.userId, isAdministrator = true, isDisabled = false) }
        val check = async { access.check(ben) }
        runCurrent()
        access.sessionChanged(other)
        gate.complete(Unit)
        assertEquals(AdminStatus.NOT_ADMIN, check.await())
        assertNull(access.state.value)
        assertFalse(access.allows(ben))
    }

    @Test fun aSwitchClearsTheStateAtOnce() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        access.sessionChanged(ben) // even the same account again: every change forgets
        assertNull(access.state.value)
        access.check(ben)
        access.sessionChanged(null)
        assertNull(access.state.value)
        assertFalse(access.allows(ben))
    }

    @Test fun lostTakesAwayTheRights() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        access.lost(other)
        assertTrue("lost for a session that is not held changes nothing", access.allows(ben))
        access.lost(ben)
        assertFalse(access.allows(ben))
        assertEquals(AdminState(ben, AdminStatus.NOT_ADMIN), access.state.value)
    }

    @Test fun nothingPrintsAnId() {
        assertFalse(AdminMe(U1, true, false).toString().contains(U1))
        assertFalse(AdminState(ben, AdminStatus.ADMIN).toString().contains(U1))
        assertFalse(AdminState(ben, AdminStatus.ADMIN).toString().contains(S1))
    }
}
