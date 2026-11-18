package app.shoalwatch.data.downloads

import app.shoalwatch.TestMonotonic
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
    private val c get() = Session(a.serverId, "cccccccccccccccccccccccccccccccc")
    private val clock = TestMonotonic()
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
            clock = clock,
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

    @Test fun aProofIsForThatItemOnlyAndExpires() = runBlocking {
        shared(SharedDelete.EVERYONE)
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e2.id, "4826".toCharArray()))
        assertEquals("a proof for another item marks nothing", ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, locked.release(a, e1.id, together = true))
        assertFalse(playedHere(b, e1))
        assertEquals(PinCheck.Correct, locked.proveSharer(a, b.userId, e1.id, "4826".toCharArray()))
        clock.ms += 61_000
        assertEquals("an old proof marks nothing", ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, locked.release(a, e1.id, together = true))
        assertFalse(playedHere(b, e1))
    }

    @Test fun anUnprotectedProfileAskedNoPinGivesNone() = runBlocking {
        shared(SharedDelete.ANYONE)
        assertEquals(PinCheck.NotProtected, locked.proveSharer(a, c.userId, e1.id, "4826".toCharArray()))
        assertTrue("never checked", checks.isEmpty())
    }
}
