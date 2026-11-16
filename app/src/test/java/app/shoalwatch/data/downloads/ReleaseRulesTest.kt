package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import app.shoalwatch.data.servers.Session
import org.junit.Test

/** Watch-and-delete's one rule, on real parts (see [ReleaseTestBase]). */
class ReleaseRulesTest : ReleaseTestBase() {
    private fun episodeSeen(vararg who: app.shoalwatch.data.servers.Session) = who.forEach { seen(it, show, season1, e1, e2) }

    private fun releasedIds(s: app.shoalwatch.data.servers.Session) = runBlocking {
        db.offlineLibraryDao().observeReleased(s.serverId, s.userId, show.id).first().map { it.item.itemId }
    }

    @Test fun sharingOffTheOnlyOwnerDeletesFilesWritesATombstoneAndKeepsThePins() = runBlocking {
        episodeSeen(a)
        val folder = downloaded(a, e1)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, e1.id, together = false))
        settled()
        assertFalse(hasRow(e1))
        assertFalse("the folder is gone", folder.exists())
        assertEquals(setOf(a.userId), tombstones(e1))
        assertTrue(pinned(e1)); assertTrue(pinned(season1)); assertTrue(pinned(show))
        assertTrue("watched on this phone", playedHere(a, e1))
        assertEquals(1, playedRows(a, e1))
        assertEquals(listOf(a to e1.id), watchedCalls)
    }

    @Test fun sharingOffTwoOwnersKeepsItForTheOtherAndThisLibraryShowsItReleased() = runBlocking {
        episodeSeen(a, b)
        val folder = downloaded(a, e1)
        alsoOwns(b, e1)
        assertEquals(ReleaseOutcome.KEPT_FOR_OTHERS, housekeeping.release(a, e1.id, together = false))
        settled()
        assertTrue(folder.exists())
        assertNotNull("the other owner still plays it", db.offlineLibraryDao().getPlayable(b.serverId, b.userId, e1.id))
        assertEquals(null, db.offlineLibraryDao().getPlayable(a.serverId, a.userId, e1.id))
        assertEquals(listOf(e1.id), releasedIds(a))
        assertEquals(emptyList<String>(), releasedIds(b))
    }

    @Test fun sharingOnAfterAnyoneANonOwnerReleaseDeletesForEveryoneWithTombstonesForEachOwnerAndTheReleaser() = runBlocking {
        episodeSeen(a, b)
        sharing(true)
        val folder = downloaded(a, e1)
        confirm(b, e1)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(b, e1.id, together = false))
        settled()
        assertFalse(hasRow(e1))
        assertFalse(folder.exists())
        assertEquals(setOf(a.userId, b.userId), tombstones(e1))
        assertEquals(listOf(e1.id), releasedIds(a))
        assertEquals(listOf(e1.id), releasedIds(b))
    }

    @Test fun sharingOnAfterEveryoneWaitsForTheSecondThenDeletes() = runBlocking {
        episodeSeen(a, b)
        sharing(true, SharedDelete.EVERYONE)
        val folder = downloaded(a, e1)
        confirm(b, e1)
        assertEquals(ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED, housekeeping.release(a, e1.id, together = false))
        settled()
        assertTrue(hasRow(e1)); assertTrue(folder.exists()); assertEquals(emptySet<String>(), tombstones(e1))
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(b, e1.id, together = false))
        settled()
        assertFalse(hasRow(e1)); assertFalse(folder.exists())
    }

    @Test fun watchedTogetherWithEveryoneDeletesInOnePressAndTheOtherProfilesRowIsItsOwn() = runBlocking {
        episodeSeen(a, b)
        sharing(true, SharedDelete.EVERYONE)
        downloaded(a, e1)
        confirm(b, e1)
        assertEquals(1, housekeeping.releaseChoice(a, e1.id).othersWhoShare)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, e1.id, together = true))
        assertFalse(hasRow(e1))
        assertEquals(1, playedRows(a, e1))
        assertEquals("B's own outbox holds its own PLAYED row", 1, playedRows(b, e1))
        assertEquals(2, count("sync_outbox", "kind = 'PLAYED'"))
        assertTrue(playedHere(b, e1))
    }

    @Test fun togetherWithSharingOffIsTheSameAsWithout() = runBlocking {
        episodeSeen(a, b)
        downloaded(a, e1)
        alsoOwns(b, e1)
        assertEquals(0, housekeeping.releaseChoice(a, e1.id).othersWhoShare)
        assertEquals(ReleaseOutcome.KEPT_FOR_OTHERS, housekeeping.release(a, e1.id, together = true))
        assertEquals(0, playedRows(b, e1))
    }

    @Test fun aFilmIsDeletedWithNoTombstone() = runBlocking {
        seen(a, film)
        val folder = downloaded(a, film)
        val choice = housekeeping.releaseChoice(a, film.id)
        assertTrue(choice.canRelease && choice.film)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, film.id, together = false))
        settled()
        assertFalse(folder.exists())
        assertEquals(0, count("released_episodes"))
        assertFalse(pinned(film))
    }

    @Test fun reDownloadingAReleasedEpisodeRemovesItsTombstone() = runBlocking {
        episodeSeen(a)
        downloaded(a, e1)
        housekeeping.release(a, e1.id, together = false)
        assertEquals(setOf(a.userId), tombstones(e1))
        store.add(a, listOf(newDownload(e1)))
        assertEquals(emptySet<String>(), tombstones(e1))
        assertTrue(pinned(e1))
    }

    @Test fun deletingTheSeriesLastDownloadRemovesItsTombstonesAndUnpins() = runBlocking {
        episodeSeen(a)
        downloaded(a, e1)
        downloaded(a, e2)
        housekeeping.release(a, e1.id, together = false)
        assertTrue(pinned(e1))
        store.removeOwner(a, e2.id)
        assertEquals(0, count("released_episodes"))
        assertFalse(pinned(e1)); assertFalse(pinned(e2)); assertFalse(pinned(season1)); assertFalse(pinned(show))
    }

    @Test fun anOpenCopyKeepsItsFilesUntilThePlayerLetsGo() = runBlocking {
        episodeSeen(a)
        val folder = downloaded(a, e1)
        val key = DownloadKey(a.serverId, e1.id)
        playing.opened(key)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, e1.id, together = false))
        settled()
        assertTrue("still open: kept", folder.exists())
        playing.closed(key)
        settled()
        assertFalse(folder.exists())
    }

    @Test fun alwaysReleasesAtTheStopWithoutASecondMark() = runBlocking {
        episodeSeen(a)
        val folder = downloaded(a, e1)
        housekeeping.setAfterFinish(a, AfterFinish.ALWAYS)
        playstate.recordLocalStop(a, e1.id, 0, finished = true) // what the LOCAL stop does first
        housekeeping.onLocalFinished(a, e1.id)
        settled()
        assertFalse(hasRow(e1)); assertFalse(folder.exists())
        assertEquals("no PLAYED tick on top of the stop's PLAY", 0, playedRows(a, e1))
        assertEquals(listOf(a to e1.id), watchedCalls)
    }

    @Test fun autoDownloadNextIsRememberedWhileTheDownloadIsStillThereOnBothPaths() = runBlocking {
        episodeSeen(a)
        downloaded(a, e1)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, e1.id, together = false))
        assertEquals("the press: remembered before the row went", listOf(Triple(a, e1.id, true)), rememberedCalls.toList())
        downloaded(a, e2)
        housekeeping.setAfterFinish(a, AfterFinish.ALWAYS)
        housekeeping.onLocalFinished(a, e2.id)
        settled()
        assertEquals("the ALWAYS stop: the same", Triple(a, e2.id, true), rememberedCalls.last())
        assertFalse(hasRow(e2))
    }

    @Test fun aReleaseThatIsNotYoursRemembersNothing() = runBlocking {
        episodeSeen(a, b)
        downloaded(a, e1)
        assertEquals(ReleaseOutcome.NOT_YOURS, housekeeping.release(b, e1.id, together = false))
        assertEquals(emptyList<Triple<Session, String, Boolean>>(), rememberedCalls.toList())
    }

    @Test fun askAndNeverKeepTheDownloadAtTheStop() = runBlocking {
        episodeSeen(a)
        downloaded(a, e1)
        for (setting in listOf(AfterFinish.ASK, AfterFinish.NEVER)) {
            housekeeping.setAfterFinish(a, setting)
            housekeeping.onLocalFinished(a, e1.id)
            settled()
            assertTrue(setting.name, hasRow(e1))
        }
    }
}
