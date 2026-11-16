package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Final review M-1: only the owner whose own sign-in fetched the copy is outside the strict sharing rule. A profile that
 * inherits a copy, or joins it as "already on this phone", is re-asked after 7 days, refused while stale and the server can be
 * reached, and removed as an owner on an explicit "no"; and it inherits only with a fresh confirmation while the server can be
 * reached. A = U1 (the parent, who downloads), B = U2 (the child), both on S1.
 */
class StrictSharingOwnersTest : DownloadHousekeepingTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val film get() = item("film")
    private lateinit var copies: SavedCopies

    @Before fun setUpCopies() {
        copies = SavedCopies(db.offlineLibraryDao(), db.downloadDao().savedRows(), paths, Dispatchers.Unconfined, now = { clock }, reachable = { serverReachable })
    }

    /** A downloads the film through the real queue, so A's own sign-in fetched it. */
    private fun aDownloadsIt() {
        queued(a, film)
        playable()
        assertEquals(QueueRun.Drained, drain())
        assertEquals(DownloadState.COMPLETE, download(film)!!.state)
    }

    private fun plan(s: Session) = runBlocking { copies.plan(s, film.id) }

    private fun owners() = runBlocking { store.owners(key(film)) }

    /** Sharing on, B shown the film and confirmed [daysAgo] days ago (as `reconfirm` would have recorded it). */
    private fun bConfirmed(daysAgo: Int) = runBlocking {
        store.setSharing(S1, true)
        seen(b, film)
        confirm(b, film)
        clock += daysAgo * DAY
    }

    @Test fun aParentSigningOutOnlineHandsNothingToAChildWhoseConfirmationIsOverAWeekOld() {
        aDownloadsIt()
        bConfirmed(daysAgo = 8)
        serverReachable = true
        facts.allow(film.id, canDownload = false) // the admin has since turned the child's download permission off
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertEquals(emptyList<String>(), owners())
        assertNull("the row is gone", download(film))
        assertFalse("and its folder", folder(film).exists())
        assertNull("never LOCAL for the child", plan(b))
    }

    @Test fun offlineTheChildInheritsOnItsOldConfirmation() {
        aDownloadsIt()
        bConfirmed(daysAgo = 8)
        serverReachable = false
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertEquals(listOf(b.userId), owners())
        assertNotNull(plan(b))
    }

    @Test fun onlineAFreshConfirmationInherits() {
        aDownloadsIt()
        bConfirmed(daysAgo = 2)
        serverReachable = true
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertEquals(listOf(b.userId), owners())
        assertNotNull(plan(b))
    }

    /** Final review L-2: a confirmation dated ahead of the clock is not fresh enough to inherit while the server can be reached. */
    @Test fun onlineAConfirmationDatedAheadOfTheClockDoesNotInherit() {
        aDownloadsIt()
        bConfirmed(daysAgo = 0)
        clock -= 30 * DAY
        serverReachable = true
        runBlocking { hooked.removeAccount(S1, a.userId) }
        settle()
        assertNull(download(film))
    }

    @Test fun anInheritedCopyIsReAskedAfterAWeekAndAnExplicitNoRemovesItAndItsFiles() = runBlocking {
        aDownloadsIt()
        bConfirmed(daysAgo = 0)
        hooked.removeAccount(S1, a.userId)
        settle()
        assertEquals(listOf(b.userId), owners())
        clock += 8 * DAY
        serverReachable = true
        assertNull("a stale inherited copy is not played while the server can be reached", plan(b))
        facts.allow(film.id, canDownload = false)
        housekeeping.reconfirm(b)
        assertEquals("asked about it", 1, facts.calls.size)
        assertNull("the last owner's explicit no deletes the copy", download(film))
        assertFalse(folder(film).exists())
    }

    @Test fun aCoOwnerThroughAlreadyOnThisPhoneStaysUnderTheRuleEvenWithSharingOff() = runBlocking {
        aDownloadsIt()
        seen(b, film)
        assertEquals(mapOf(film.id to app.shoalwatch.data.db.downloads.DownloadStore.Added.OwnerAdded), store.add(b, listOf(shareDownload(film))))
        clock += 8 * DAY
        serverReachable = true
        assertNull("stale and the server is there: B streams", plan(b))
        assertNotNull("A fetched it: no confirmation needed, however old", plan(a))
        facts.allow(film.id, canDownload = false)
        housekeeping.reconfirm(b)
        assertEquals("B is no longer an owner", listOf(a.userId), owners())
        assertFalse(store.isConfirmed(key(film), b.userId))
        assertTrue("A keeps its copy", folder(film).exists())
        assertNull(plan(b))
    }

    @Test fun theOwnerWhoseSignInFetchedItIsNeverReAsked() = runBlocking {
        aDownloadsIt()
        clock += 30 * DAY
        serverReachable = true
        assertNotNull(plan(a))
        housekeeping.reconfirm(a)
        assertTrue(facts.calls.isEmpty())
        assertTrue(refreshedIds.isEmpty())
    }

    @Test fun aFreshAlreadyOnThisPhoneConfirmationRefreshesAnOldPermission() = runBlocking {
        aDownloadsIt()
        bConfirmed(daysAgo = 8)
        store.add(b, listOf(shareDownload(film)))
        assertEquals("the planner's confirmation is today's", clock, db.offlineLibraryDao().sharedConfirmedAt(S1, b.userId, film.id))
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1_000 }
}
