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

    /**
     * Final review N-1: sharing on, A queues the film as a conversion, B joins it as "already on this phone", A's sign-in can no
     * longer send (so the queue runs it under B's), three days pass and the server answers [bAnswer] about B's own CanDownload
     * (null: it does not say). With [aLeaves], A is no longer an owner, so B is the only one.
     */
    private fun bRunsTheConversion(bAnswer: Boolean?, aLeaves: Boolean = false) = runBlocking {
        store.setSharing(S1, true)
        queued(a, film, quality = DownloadQuality.MEDIUM)
        seen(b, film)
        assertEquals(mapOf(film.id to app.shoalwatch.data.db.downloads.DownloadStore.Added.OwnerAdded), store.add(b, listOf(shareDownload(film))))
        if (aLeaves) store.removeOwner(a, film.id)
        tokens -= a
        if (bAnswer == null) facts.known.remove(film.id) else facts.allow(film.id, canDownload = bAnswer)
        clock += 3 * DAY
        convertible()
        drain()
    }

    private fun bFetched() = runBlocking { store.isFetcher(key(film), b.userId) }

    @Test fun aConversionUnderAnOwnersSignInThatIsNotYetItsFetcherAsksThatSignInFirstAndANoDropsIt() {
        bRunsTheConversion(bAnswer = false)
        assertEquals("one question, about B's own permission", listOf(listOf(film.id)), facts.calls)
        assertTrue("nothing was converted", hls.opens.isEmpty())
        assertFalse(bFetched())
        assertEquals(listOf(a.userId), owners())
        assertFalse(runBlocking { store.isConfirmed(key(film), b.userId) })
        assertFalse(download(film)!!.state == DownloadState.COMPLETE)
        assertNull(plan(b))
    }

    @Test fun aNoForTheOnlyOwnerFailsTheConversionAsNotAllowed() {
        bRunsTheConversion(bAnswer = false, aLeaves = true)
        assertTrue(hls.opens.isEmpty())
        assertEquals(DownloadState.FAILED to DownloadFailure.NOT_ALLOWED, download(film)!!.let { it.state to it.failure })
        assertFalse(bFetched())
        assertNull(plan(b))
    }

    @Test fun anUnknownAnswerNeverConvertsAndDoesNotMakeAFetcher() {
        bRunsTheConversion(bAnswer = null)
        assertTrue(hls.opens.isEmpty())
        assertEquals(DownloadState.FAILED to DownloadFailure.NOT_ALLOWED, download(film)!!.let { it.state to it.failure })
        assertFalse(bFetched())
        assertNull(plan(b))
    }

    @Test fun aYesConfirmsThatSignInNowAndThenMakesItTheFetcher() {
        bRunsTheConversion(bAnswer = true)
        assertEquals(listOf(listOf(film.id)), facts.calls)
        assertEquals(DownloadState.COMPLETE, download(film)!!.state)
        assertTrue(bFetched())
        assertEquals("B's own confirmation, dated now", 1, count("download_permissions", "userId = '${b.userId}' AND confirmedAt = $clock"))
    }

    /** Nit-3: an original is checked by `/Download` itself; a refusal for the only owner, after the mark, leaves nothing playable. */
    @Test fun anOriginalRefusedByDownloadForTheOnlyOwnerAfterTheMarkEndsFailedWithNothingPlayable() = runBlocking {
        store.setSharing(S1, true)
        queued(a, film)
        seen(b, film)
        store.add(b, listOf(shareDownload(film)))
        store.removeOwner(a, film.id)
        tokens -= a
        playable()
        files.steps += FakeFiles.answer(FetchResult.Failed(DownloadFailure.NOT_ALLOWED))
        drain()
        assertTrue("no separate question for an original", facts.calls.isEmpty())
        assertEquals(DownloadState.FAILED to DownloadFailure.NOT_ALLOWED, download(film)!!.let { it.state to it.failure })
        assertNull(plan(b))
        assertNull(db.offlineLibraryDao().getPlayable(S1, b.userId, film.id))
    }

    /**
     * Final review L-a (the controller's decision): an owner that did not fetch the copy and whose answer was "unknown" keeps its
     * ownership row, but with no confirmed permission it cannot play or inherit the copy, online or offline; a later yes restores it.
     */
    @Test fun anOwnerThatDidNotFetchItAndHasNoConfirmedPermissionCannotPlayItEvenOfflineUntilAYes() = runBlocking {
        aDownloadsIt()
        seen(b, film)
        store.add(b, listOf(shareDownload(film)))
        clock += 8 * DAY
        serverReachable = true
        housekeeping.reconfirm(b) // the server does not say (no CanDownload): B's permission goes, its ownership stays
        assertEquals(setOf(a.userId, b.userId), owners().toSet())
        assertFalse(store.isConfirmed(key(film), b.userId))
        serverReachable = false
        assertNull("offline, no permission: not played", plan(b))
        assertNull("nor its extras", copies.media(b, film.id))
        hooked.removeAccount(S1, a.userId)
        settle()
        assertNull("and A leaving hands it nothing playable", plan(b))
        facts.allow(film.id, canDownload = true)
        serverReachable = true
        housekeeping.reconfirm(b)
        assertNotNull("a later yes restores it", plan(b))
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1_000 }
}
