package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Storage on real parts (in-memory Room, the real release rule and removal). A = U1 is the adult, B = U2 the child; both on S1.
 * SAFEGUARDING: B's view never names, sizes or counts a download it may not see; those bytes are only in `otherProfilesBytes`.
 */
class StorageUsageTest : ReleaseTestBase() {
    private val e3 get() = episode("e3", 1, 3)

    private fun sized(item: Item, total: Long?, done: Long = 0, state: DownloadState = DownloadState.COMPLETE) = runBlocking {
        store.update(DownloadKey(a.serverId, item.id)) { it.copy(totalBytes = total, doneBytes = done, state = state) }
    }

    private fun view(s: Session) = runBlocking { housekeeping.storage(s).first() }

    private fun watchedBy(s: Session, vararg items: Item) = runBlocking { for (it in items) playstate.setPlayed(s, it.id, true) }

    @Test fun aChildNeverSeesTheAdultsHiddenFilmByTitleSizeOrCountOnlyAsOtherProfilesBytes() {
        seen(a, film)
        downloaded(a, film)
        sized(film, 4_000)
        seen(a, show, season1, e1); seen(b, show, season1, e1)
        downloaded(b, e1)
        sized(e1, 1_000)
        val child = view(b)
        assertEquals(emptyList<FilmUse>(), child.films)
        assertEquals(listOf(show.id), child.series.map { it.series.item.id })
        assertEquals(1_000L, child.series.single().bytes)
        assertEquals("the film is one number", 4_000L, child.otherProfilesBytes)
        assertFalse("no title anywhere", child.series.any { it.series.item.id == film.id || it.series.item.name == film.name })
        val adult = view(a)
        assertEquals(listOf(film.id), adult.films.map { it.film.item.id })
        assertEquals(emptyList<SeriesUse>(), adult.series)
        assertEquals("sharing off: B's episode is someone else's", 1_000L, adult.otherProfilesBytes)
    }

    @Test fun ownersAndSharedViewersSharingOn() {
        sharing(true)
        seen(a, show, season1, e1, e2); seen(b, show, season1, e1, e2)
        downloaded(a, e1); sized(e1, 1_000)
        downloaded(a, e2); sized(e2, 2_000)
        confirm(b, e1); confirm(b, e2)
        alsoOwns(b, e2)
        val owner = view(a).series.single()
        assertEquals(2, owner.episodes); assertEquals(2, owner.ownedEpisodes)
        assertEquals(3_000L, owner.bytes)
        assertEquals("e2 is also B's: deleting A's own frees only e1", 1_000L, owner.deletableBytes)
        val viewer = view(b).series.single()
        assertEquals(3_000L, viewer.bytes)
        assertEquals(1, viewer.ownedEpisodes)
        assertEquals(0L, viewer.deletableBytes)
        assertEquals(0L, view(b).otherProfilesBytes)
    }

    @Test fun sizesByStateAndLargestFirst() {
        seen(a, show, season1, e1, e2, e3, film)
        downloaded(a, film); sized(film, 500)
        downloaded(a, e1); sized(e1, 1_000)
        downloaded(a, e2); sized(e2, 9_000, done = 300, state = DownloadState.RUNNING)
        downloaded(a, e3); sized(e3, null, done = 700)
        val v = view(a)
        assertEquals("COMPLETE: the total (or what is done when none); otherwise what is done", 1_000L + 300 + 700, v.series.single().bytes)
        assertEquals(3, v.series.single().episodes)
        assertEquals(500L, v.films.single().bytes)
        watchedBy(a, e1)
        assertEquals(1, view(a).series.single().watched)
    }

    @Test fun sdCardVolumesCountWhatIsOnThemAndACardThatIsOutStillShowsItsDownloads() {
        fake.card("CARD-1", temp.newFolder()).free["CARD-1"] = 9_999
        fake.free[null] = 5_555
        seen(a, film, show, season1, e1)
        downloaded(a, film); sized(film, 4_000)
        downloaded(a, e1); sized(e1, 1_000)
        runBlocking { store.update(DownloadKey(a.serverId, film.id)) { it.copy(storage = StorageKind.REMOVABLE, volumeId = "CARD-1") } }
        assertEquals(
            listOf(VolumeUse(StorageKind.INTERNAL, null, 5_555, 1_000, true), VolumeUse(StorageKind.REMOVABLE, "CARD-1", 9_999, 4_000, true)),
            view(a).volumes,
        )
        fake.unmount("CARD-1")
        runBlocking { store.update(DownloadKey(a.serverId, e1.id)) { it.copy(totalBytes = 1_001) } } // a fresh emission
        assertEquals(
            listOf(VolumeUse(StorageKind.INTERNAL, null, 5_555, 1_001, true), VolumeUse(StorageKind.REMOVABLE, "CARD-1", 0, 4_000, false)),
            view(a).volumes,
        )
    }

    @Test fun watchedSummaryMatchesWhatDeleteWatchedDeletesAfterAnyone() {
        sharing(true, SharedDelete.ANYONE)
        seen(a, show, season1, e1, e2, e3); seen(b, show, season1, e1, e2, e3)
        downloaded(a, e1); sized(e1, 1_000)
        downloaded(a, e2); sized(e2, 2_000)
        downloaded(a, e3); sized(e3, 4_000)
        confirm(b, e1) // B sees e1 shared
        watchedBy(a, e1, e2)
        val summary = runBlocking { housekeeping.watchedSummary(a) }
        assertEquals(WatchedSummary(count = 2, bytes = 3_000, sharedForEveryone = 1), summary)
        assertEquals(summary.count, runBlocking { housekeeping.deleteWatched(a) })
        settled()
        assertFalse(hasRow(e1)); assertFalse(hasRow(e2)); assertTrue("not watched: kept", hasRow(e3))
        assertEquals(WatchedSummary(0, 0, 0), runBlocking { housekeeping.watchedSummary(a) })
    }

    @Test fun watchedSummaryMatchesWhatDeleteWatchedDeletesAfterEveryone() {
        sharing(true, SharedDelete.EVERYONE)
        seen(a, show, season1, e1, e2); seen(b, show, season1, e1, e2)
        downloaded(a, e1); sized(e1, 1_000)
        downloaded(a, e2); sized(e2, 2_000)
        confirm(b, e1); confirm(b, e2)
        watchedBy(a, e1, e2)
        watchedBy(b, e2)
        val summary = runBlocking { housekeeping.watchedSummary(a) }
        assertEquals("e1 waits for B", WatchedSummary(count = 1, bytes = 2_000, sharedForEveryone = 1), summary)
        assertEquals(1, runBlocking { housekeeping.deleteWatched(a) })
        assertTrue(hasRow(e1)); assertFalse(hasRow(e2))
    }

    @Test fun watchedSummarySharingOffCountsAKeptCopyButFreesNothingForIt() {
        seen(a, show, season1, e1, e2); seen(b, show, season1, e1, e2)
        downloaded(a, e1); sized(e1, 1_000)
        downloaded(a, e2); sized(e2, 2_000)
        alsoOwns(b, e1)
        watchedBy(a, e1, e2)
        val summary = runBlocking { housekeeping.watchedSummary(a) }
        assertEquals(WatchedSummary(count = 2, bytes = 2_000, sharedForEveryone = 0), summary)
        assertEquals(2, runBlocking { housekeeping.deleteWatched(a) })
        assertTrue("B's still", hasRow(e1)); assertFalse(hasRow(e2))
    }

    @Test fun aChildsDeleteWatchedNeverTouchesTheAdultsDownloads() {
        seen(a, film); downloaded(a, film)
        watchedBy(a, film)
        seen(b, show, season1, e1)
        assertEquals(WatchedSummary(0, 0, 0), runBlocking { housekeeping.watchedSummary(b) })
        assertEquals(0, runBlocking { housekeeping.deleteWatched(b) })
        assertTrue(hasRow(film))
    }

    @Test fun deleteSeriesRemovesOnlyThisProfilesOwnAndKeepsAnotherOwnersCopy() {
        seen(a, show, season1, e1, e2); seen(b, show, season1, e1, e2)
        val folder = downloaded(a, e1)
        downloaded(a, e2)
        alsoOwns(b, e2)
        assertEquals("e1 deleted; e2 stays for B", 1, runBlocking { housekeeping.deleteSeries(a, show.id) })
        assertFalse(hasRow(e1)); assertFalse(folder.exists())
        assertTrue(hasRow(e2))
        assertEquals(listOf(b.userId), runBlocking { db.downloadDao().owners(a.serverId, e2.id) })
        assertEquals("B owns nothing of A's: nothing for A to delete", 0, runBlocking { housekeeping.deleteSeries(a, show.id) })
        assertEquals(0, runBlocking { housekeeping.deleteSeries(a, "not an id") })
    }
}
