package app.shoalwatch.data.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 10: who may delete from the server (`allowsDelete`) and the held answer (`known`), from the same `/Users/Me` read. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteRightsTest {
    private val ben = Session(S1, U1)
    private val other = Session(S1, U2)
    private var calls = 0
    private var answer: suspend (Session) -> AdminMe = { s -> AdminMe(s.userId, isAdministrator = false, isDisabled = false, canDelete = true) }
    private val access = AdminAccess { s -> calls++; answer(s) }

    @Test fun aProfileAllowedToDeleteMayDeleteButIsNoAdministrator() = runTest {
        access.sessionChanged(ben)
        assertEquals(AdminStatus.NOT_ADMIN, access.check(ben))
        assertTrue(access.allowsDelete(ben))
        assertFalse(access.allows(ben))
        assertFalse("another profile", access.allowsDelete(other))
    }

    @Test fun anAdministratorWithoutTheDeletionPolicyIsNotAssumedToMayDelete() = runTest {
        // Jellyfin's `BaseItem.CanDelete(user)` reads the deletion policy only; being an administrator does not imply it (review 1b).
        answer = { s -> AdminMe(s.userId, isAdministrator = true, isDisabled = false) }
        access.sessionChanged(ben)
        access.check(ben)
        assertTrue(access.allows(ben))
        assertFalse(access.allowsDelete(ben))
        answer = { s -> AdminMe(s.userId, isAdministrator = true, isDisabled = false, canDelete = true) }
        access.check(ben)
        assertTrue(access.allowsDelete(ben))
    }

    @Test fun aRecheckThatChangesOnlyTheDeleteRightIsHeard() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        assertEquals(AdminState(ben, AdminStatus.NOT_ADMIN, mayDelete = true), access.state.value)
        answer = { s -> AdminMe(s.userId, isAdministrator = false, isDisabled = false, canDelete = false) }
        access.check(ben)
        assertEquals("the held state itself changes, so collectors hear it", AdminState(ben, AdminStatus.NOT_ADMIN, mayDelete = false), access.state.value)
        assertFalse(access.allowsDelete(ben))
    }

    @Test fun aLostAnswerDropsTheDeleteRightToo() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        access.lost(ben)
        assertFalse(access.allowsDelete(ben))
        assertFalse(access.state.value!!.mayDelete)
    }

    @Test fun neitherMayNot() = runTest {
        answer = { s -> AdminMe(s.userId, isAdministrator = false, isDisabled = false) }
        access.sessionChanged(ben)
        access.check(ben)
        assertFalse(access.allowsDelete(ben))
    }

    @Test fun aDisabledOrOtherAccountMayNot() = runTest {
        answer = { _ -> AdminMe(U2, isAdministrator = false, isDisabled = false, canDelete = true) }
        access.sessionChanged(ben)
        access.check(ben)
        assertFalse(access.allowsDelete(ben))
        answer = { s -> AdminMe(s.userId, isAdministrator = true, isDisabled = true, canDelete = true) }
        access.check(ben)
        assertFalse(access.allowsDelete(ben))
    }

    @Test fun aProfileChangeForgetsIt() = runTest {
        access.sessionChanged(ben)
        access.check(ben)
        access.sessionChanged(other)
        assertFalse(access.allowsDelete(ben))
        access.sessionChanged(ben)
        assertFalse("not until asked again", access.allowsDelete(ben))
    }

    @Test fun knownAsksOnceAndThenUsesTheHeldAnswer() = runTest {
        access.sessionChanged(ben)
        assertEquals(AdminStatus.NOT_ADMIN, access.known(ben))
        assertEquals(AdminStatus.NOT_ADMIN, access.known(ben))
        assertEquals(1, calls)
        access.sessionChanged(other)
        access.sessionChanged(ben)
        access.known(ben)
        assertEquals("asked again after a change", 2, calls)
    }
}
