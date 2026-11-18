package app.shoalwatch.data.downloads

import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security review M8 F12 (SEC-03): "Watched together" writes a watched mark into each sharer's own server account. A protected
 * profile's account is marked only after its own PIN in this flow, once, for this item; without it that profile is left out, the
 * others are still marked, and "After everyone has watched" is not satisfied by a mark that never happened.
 */
class WatchedTogetherProtectedTest : ReleaseTestBase() {
    private val c get() = Session(a.serverId, app.shoalwatch.testId("u3"))
    private val checks = mutableListOf<Pair<Session, String>>()

    private val locked by lazy {
        HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { _, _ -> }, { true }, appScope,
            volumes = fake.volumes, remove = { _, _ -> error("not used") },
            sharerLocks = SharerLocks(
                isProtected = { it == b },
                check = { s, pin -> checks += s to String(pin); if (String(pin) == "4826") PinCheck.Correct else PinCheck.Wrong(4) },
                name = { s -> if (s == b) "ben" else null },
            ),
        )
    }

    private fun episodeSeen(vararg who: Session) = who.forEach { seen(it, show, season1, e1, e2) }

    private fun shared(rule: SharedDelete) {
        episodeSeen(a, b)
        sharing(true, rule)
        downloaded(a, e1)
        confirm(b, e1)
    }

    @Test fun aProtectedSharerIsNotMarkedWithoutItsPinAndEveryoneKeepsTheCopy() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(listOf(AccountSummary(b.serverId, b.userId, "ben")), locked.protectedSharers(a, e1.id))
        assertEquals(ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, locked.release(a, e1.id, together = true))
        assertFalse("no mark in the protected account", playedHere(b, e1))
        assertEquals(0, playedRows(b, e1))
        assertTrue(playedHere(a, e1))
        assertTrue(hasRow(e1))
    }

    @Test fun withItsOwnPinTheProtectedSharerIsMarkedOnceAndEveryoneDeletes() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Wrong(4), locked.proveSharer(a, b.userId, e1.id, "1111".toCharArray()))
        val pin = "4826".toCharArray()
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, pin))
        assertTrue("the PIN array is zeroed", pin.all { it == '\u0000' })
        assertEquals(ReleaseOutcome.DELETED, locked.release(a, e1.id, together = true))
        assertTrue(playedHere(b, e1))
        assertEquals(1, playedRows(b, e1))
    }

    @Test fun aProofIsForThatItemOnly() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e2.id, "4826".toCharArray()))
        assertEquals("a proof for another item marks nothing", ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, locked.release(a, e1.id, together = true))
        assertFalse(playedHere(b, e1))
    }

    /** Review M-1/M-2: a proof belongs to one press. A new press drops it, a release uses it up, and it never expires mid-flow. */
    @Test fun aProofIsNotReusedByASecondPress() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, "4826".toCharArray()))
        locked.dropProofs(a, e1.id) // the next "Watched together" press starts
        assertEquals(TogetherRelease(ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, leftOut = true), locked.releaseTogether(a, e1.id))
        assertFalse(playedHere(b, e1))
    }

    @Test fun aReleaseDiscardsEveryProofOfItsPress() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, "4826".toCharArray()))
        assertEquals("the delete-only press ends it", ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, locked.release(a, e1.id, together = false))
        assertEquals(TogetherRelease(ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, leftOut = true), locked.releaseTogether(a, e1.id))
        assertFalse(playedHere(b, e1))
    }

    @Test fun aProofMadeForOneReleaserMarksNothingForAnother() = runBlocking {
        servers.upsertAccount(app.shoalwatch.data.servers.Account(c.serverId, c.userId, "cat", "tok-4"), makeActive = false)
        shared(SharedDelete.EVERYONE)
        seen(c, show, season1, e1, e2)
        confirm(c, e1)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, "4826".toCharArray()))
        assertTrue(locked.releaseTogether(c, e1.id).leftOut)
        assertFalse(playedHere(b, e1))
    }

    @Test fun releaseTogetherSaysWhenAProtectedSharerWasLeftOut() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, "4826".toCharArray()))
        assertEquals(TogetherRelease(ReleaseOutcome.DELETED, leftOut = false), locked.releaseTogether(a, e1.id))
    }

    /** Review M-3: a name that cannot be read is empty (the screen then uses neutral words), never a failed press. */
    @Test fun aNameThatCannotBeReadIsEmpty() = runBlocking {
        shared(SharedDelete.EVERYONE)
        val noNames = HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { _, _ -> }, { true }, appScope,
            volumes = fake.volumes, remove = { _, _ -> error("not used") },
            sharerLocks = SharerLocks({ it == b }, { _, _ -> PinCheck.Correct }, { error("db") }),
        )
        assertEquals(listOf(AccountSummary(b.serverId, b.userId, "")), noNames.protectedSharers(a, e1.id))
    }

    @Test fun anUnprotectedProfileAskedNoPinGivesNone() = runBlocking {
        shared(SharedDelete.ANYONE)
        assertEquals(PinCheck.NotProtected, locked.proveSharer(a, c.userId, e1.id, "4826".toCharArray()))
        assertTrue("never checked", checks.isEmpty())
    }
}
