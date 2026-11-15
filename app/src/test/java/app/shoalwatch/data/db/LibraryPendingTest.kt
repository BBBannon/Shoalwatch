package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unsynced local changes (`pendingSyncAt`) are never overwritten by a server refresh and never evicted (SPEC 3.2). */
class LibraryPendingTest : LibraryTestBase() {
    private val items get() = db.itemDao()
    private val now = t0.plusSeconds(10).toEpochMilli()

    private suspend fun data(label: String, userId: String = U1) = items.getUserData(S1, userId, id(label))

    @Test fun aLocalChangeMadeWithPendingCarriesTheMarkerAndOneWithoutDoesNot() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        items.setFavourite(S1, U1, id("b"), true, now, pending = true)
        items.setPosition(S1, U1, id("c"), 5_000, now, pending = true)
        items.setFavourite(S1, U1, id("d"), true, now)
        assertEquals(now, data("a")!!.pendingSyncAt)
        assertEquals(now, data("b")!!.pendingSyncAt)
        assertEquals(now, data("c")!!.pendingSyncAt)
        assertNull(data("d")!!.pendingSyncAt)
    }

    @Test fun aLaterLocalChangeWithoutPendingDoesNotClearTheMarker() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        items.setFavourite(S1, U1, id("a"), true, now + 1)
        assertEquals(now, data("a")!!.pendingSyncAt)
    }

    @Test fun aRefreshDoesNotOverwriteAPendingRowButUpdatesTheOthersInTheSamePage() = runBlocking {
        library.writePage(s1u1, "resume", 0, listOf(row(item("a"), watched()), row(item("b"), watched()), row(item("c"), watched())), 3, t0)
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        items.setPosition(S1, U1, id("c"), 4_000, now, pending = true)
        library.writePage(
            s1u1, "resume", 0,
            listOf(row(item("a"), watched(positionMs = 500)), row(item("b"), watched(positionMs = 9_000, favourite = true)), row(item("c"), watched(positionMs = 0))),
            3, t0.plusSeconds(60),
        )
        val a = data("a")!!
        assertTrue("played stays", a.played)
        assertEquals("position stays", 0L, a.positionMs)
        assertEquals(now, a.pendingSyncAt)
        assertEquals(4_000L, data("c")!!.positionMs)
        val b = data("b")!!
        assertEquals("a row that is not pending takes the server's value", 9_000L, b.positionMs)
        assertTrue(b.favourite)
        assertNull(b.pendingSyncAt)
    }

    @Test fun writeItemsAlsoRespectsPending() = runBlocking {
        items.setFavourite(S1, U1, id("a"), true, now, pending = true)
        library.writeItems(s1u1, listOf(row(item("a"), watched(favourite = false))), t0.plusSeconds(60))
        assertTrue(data("a")!!.favourite)
    }

    @Test fun afterClearPendingTheNextRefreshApplies() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        library.clearPending(s1u1, id("a"))
        assertNull(data("a")!!.pendingSyncAt)
        assertTrue("clearing the marker keeps the local value", data("a")!!.played)
        library.writeItems(s1u1, listOf(row(item("a"), watched(played = false, positionMs = 700))), t0.plusSeconds(60))
        assertFalse(data("a")!!.played)
        assertEquals(700L, data("a")!!.positionMs)
    }

    @Test fun markPendingMarksAnExistingRowOnlyAndReportsIt() = runBlocking {
        assertFalse("no row, nothing to mark", library.markPending(s1u1, id("a"), t0))
        assertNull(data("a"))
        items.setFavourite(S1, U1, id("a"), true, now)
        assertTrue(library.markPending(s1u1, id("a"), t0.plusSeconds(99)))
        assertEquals(t0.plusSeconds(99).toEpochMilli(), data("a")!!.pendingSyncAt)
        library.writeItems(s1u1, listOf(row(item("a"), watched(favourite = false))), t0.plusSeconds(200))
        assertTrue(data("a")!!.favourite)
    }

    @Test fun pendingIsPerUser() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        library.writeItems(s1u2, listOf(row(item("a"), watched(positionMs = 123))), t0)
        library.writeItems(s1u1, listOf(row(item("a"), watched(positionMs = 456))), t0)
        assertEquals("the other user has nothing pending, so the server's value applies", 123L, data("a", U2)!!.positionMs)
        assertEquals(0L, data("a", U1)!!.positionMs)
        library.clearPending(s1u2, id("a")) // clearing for the other user changes nothing for this one
        assertNotNull(data("a", U1)!!.pendingSyncAt)
    }

    @Test fun evictionKeepsPendingRowsWhoseItemIsGone() = runBlocking {
        // Boring rows (not played, not favourite, no position) for items that do not exist: only the unpending one goes.
        items.setFavourite(S1, U1, id("gone-pending"), false, now, pending = true)
        items.setFavourite(S1, U1, id("gone-plain"), false, now)
        val counts = library.evict(t0.plusSeconds(60))
        assertEquals(1, counts.userData)
        assertNotNull(data("gone-pending"))
        assertNull(data("gone-plain"))
    }

    // ---- deleteUserData (rolling back an optimistic action on a row that did not exist)

    @Test fun deleteUserDataRemovesOneUsersRowEvenIfPending() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, now, pending = true)
        items.setPlayed(S1, U2, id("a"), true, 0, now)
        library.deleteUserData(s1u1, id("a"))
        assertNull(data("a", U1))
        assertNotNull(data("a", U2))
        library.deleteUserData(s1u1, id("a")) // absent: harmless
    }
}
