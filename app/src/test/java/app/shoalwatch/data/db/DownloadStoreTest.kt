package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore.Added
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.db.downloads.toEntry
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue rows and their owners (in-memory Room, accounts through the real ServerStore). */
class DownloadStoreTest : DownloadTestBase() {
    private val a get() = item("a")
    private val b get() = item("b")
    private val c get() = item("c")

    @Test fun addQueuesAtTheEndInCallOrder() = runBlocking {
        seen(s1u1, a, b, c)
        assertEquals(mapOf(b.id to Added.Queued, a.id to Added.Queued), store.add(s1u1, listOf(newDownload(b), newDownload(a))))
        store.add(s1u1, listOf(newDownload(c)))
        val order = mutableListOf<String>()
        while (true) {
            val next = store.next() ?: break
            order += next.itemId
            store.update(DownloadKey(S1, next.itemId)) { it.copy(state = DownloadState.COMPLETE) }
        }
        assertEquals(listOf(b.id, a.id, c.id), order)
        val row = db.downloadDao().get(S1, b.id)!!
        assertEquals(listOf(DownloadState.COMPLETE, 0, 0, 1_000L), listOf(row.state, row.attempts, row.doneBytes.toInt(), row.requestedAt))
        assertEquals(listOf(U1), store.owners(DownloadKey(S1, b.id)))
    }

    @Test fun aSecondProfileAddingTheSameItemOnlyBecomesAnOwner() = runBlocking {
        seen(s1u1, a)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a)))
        assertEquals(mapOf(a.id to Added.OwnerAdded), store.add(s1u2, listOf(newDownload(a))))
        assertEquals(1, count("downloads"))
        assertEquals(listOf(U1, U2).sorted(), store.owners(key(a)).sorted())
    }

    @Test fun anItemTheSessionWasNeverShownIsSkippedWithNothingWritten() = runBlocking {
        seen(s1u1, a)
        assertEquals(mapOf(a.id to Added.Skipped), store.add(s1u2, listOf(newDownload(a))))
        assertEquals(mapOf(id("missing") to Added.Skipped), store.add(s1u1, listOf(newDownload(item("missing")))))
        assertEquals(listOf(0, 0, 0), listOf(count("downloads"), count("download_owners"), count("items", "pinned = 1")))
    }

    @Test fun anItemOnAnotherServerIsSkipped() = runBlocking {
        seen(s2u1, a)
        assertEquals(mapOf(a.id to Added.Skipped), store.add(s1u1, listOf(newDownload(a))))
        assertEquals(0, count("downloads"))
    }

    @Test fun aSeriesOrSeasonIsNeverADownload() = runBlocking {
        seen(s1u1, series)
        assertEquals(mapOf(series.id to Added.Skipped), store.add(s1u1, listOf(newDownload(series))))
        assertEquals(0, count("downloads"))
    }

    @Test fun removeOwnerKeepsTheRowForOthersAndDeletesItWithTheLastOwner() = runBlocking {
        seen(s1u1, a)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a)))
        store.add(s1u2, listOf(newDownload(a)))
        assertEquals(RemoveOutcome.KeptForOthers, store.removeOwner(s1u1, a.id))
        assertEquals(RemoveOutcome.NotYours, store.removeOwner(s1u1, a.id))
        assertEquals(1, count("downloads"))
        assertEquals(RemoveOutcome.Deleted(key(a)), store.removeOwner(s1u2, a.id))
        assertEquals(0, count("downloads"))
    }

    @Test fun aNonOwnerCannotRemoveIt() = runBlocking {
        seen(s1u1, a)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a)))
        assertEquals(RemoveOutcome.NotYours, store.removeOwner(s1u2, a.id))
        assertEquals(listOf(U1), store.owners(key(a)))
    }

    @Test fun requeueInterruptedTurnsRunningAndVerifyingBackIntoQueued() = runBlocking {
        seen(s1u1, a, b, c)
        store.add(s1u1, listOf(newDownload(a), newDownload(b), newDownload(c)))
        setState(a, DownloadState.RUNNING)
        setState(b, DownloadState.VERIFYING)
        setState(c, DownloadState.COMPLETE)
        assertEquals(2, store.requeueInterrupted())
        assertEquals(listOf(DownloadState.QUEUED, DownloadState.QUEUED, DownloadState.COMPLETE), listOf(a, b, c).map { db.downloadDao().get(S1, it.id)!!.state })
    }

    @Test fun pauseAndRetryAreForOwnersOnly() = runBlocking {
        seen(s1u1, a)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a)))
        assertFalse(store.setPaused(s1u2, a.id, true))
        assertTrue(store.setPaused(s1u1, a.id, true))
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(S1, a.id)!!.state)
        assertFalse(store.setPaused(s1u2, a.id, false))
        assertTrue(store.setPaused(s1u1, a.id, false))
        assertEquals(DownloadState.QUEUED, db.downloadDao().get(S1, a.id)!!.state)
        store.update(key(a)) { it.copy(state = DownloadState.FAILED, failure = DownloadFailure.SERVER_ERROR, attempts = 3) }
        assertFalse(store.retry(s1u2, a.id))
        assertEquals(DownloadState.FAILED, db.downloadDao().get(S1, a.id)!!.state)
        assertTrue(store.retry(s1u1, a.id))
        val row = db.downloadDao().get(S1, a.id)!!
        assertEquals(listOf<Any?>(DownloadState.QUEUED, 0, null), listOf(row.state, row.attempts, row.failure))
    }

    @Test fun pauseOnlyMovesQueuedOrRunningAndRetryOnlyFailed() = runBlocking {
        seen(s1u1, a)
        store.add(s1u1, listOf(newDownload(a)))
        setState(a, DownloadState.COMPLETE)
        assertFalse(store.setPaused(s1u1, a.id, true))
        assertFalse(store.retry(s1u1, a.id))
        setState(a, DownloadState.RUNNING)
        assertTrue(store.setPaused(s1u1, a.id, true))
        assertFalse("already paused", store.setPaused(s1u1, a.id, true))
    }

    @Test fun updateIsOneTransactionSoAThrowLeavesTheRowAsItWas() = runBlocking {
        seen(s1u1, a)
        store.add(s1u1, listOf(newDownload(a)))
        val before = db.downloadDao().get(S1, a.id)
        val thrown = runCatching { store.update(key(a)) { it.copy(doneBytes = 99); error("boom") } }
        assertTrue(thrown.isFailure)
        assertEquals(before, db.downloadDao().get(S1, a.id))
        store.update(key(a)) { it.copy(doneBytes = 99) }
        assertEquals(99L, db.downloadDao().get(S1, a.id)!!.doneBytes)
    }

    @Test fun updateCannotMoveARowToAnotherKey() = runBlocking {
        seen(s1u1, a)
        store.add(s1u1, listOf(newDownload(a)))
        assertTrue(runCatching { store.update(key(a)) { it.copy(itemId = b.id) } }.isFailure)
        assertEquals(1, count("downloads", "itemId = '${a.id}'"))
    }

    @Test fun updateCannotMoveARowToAnotherSeasonSeriesOrKindSoThePinsStayRight() = runBlocking {
        val e1 = ep("e1", 1, 1)
        seen(s1u1, series, season1, season2, e1)
        store.add(s1u1, listOf(newDownload(e1)))
        for (change in listOf<(app.shoalwatch.data.db.downloads.DownloadEntity) -> app.shoalwatch.data.db.downloads.DownloadEntity>(
            { it.copy(seasonId = season2.id) }, { it.copy(seriesId = null) }, { it.copy(kind = app.shoalwatch.data.library.ItemKind.MOVIE) },
        )) {
            val thrown = runCatching { store.update(key(e1), change) }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException)
            assertEquals("A download's item cannot change.", thrown!!.message)
        }
        assertEquals(season1.id, db.downloadDao().get(S1, e1.id)!!.seasonId)
        assertTrue(pinned("season1"))
        assertFalse(pinned("season2"))
    }

    @Test fun deleteRemovesTheRowAndItsOwners() = runBlocking {
        seen(s1u1, a)
        store.add(s1u1, listOf(newDownload(a)))
        store.delete(key(a))
        assertNull(db.downloadDao().get(S1, a.id))
        assertEquals(0, count("download_owners"))
    }

    @Test fun sharingIsOffUntilSwitchedOnAndIsObserved() = runBlocking {
        assertFalse(store.sharing(S1))
        assertFalse(store.observeSharing(S1).first())
        store.setSharing(S1, true)
        assertTrue(store.sharing(S1))
        assertTrue(store.observeSharing(S1).first())
        assertFalse("per server", store.sharing(app.shoalwatch.S2))
        store.setSharing(S1, false)
        assertFalse(store.observeSharing(S1).first())
    }

    @Test fun afterAccountGoneHandsSharedDownloadsToProfilesThatCanSeeThemAndDeletesTheRest() = runBlocking {
        seen(s1u1, a, b)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a), newDownload(b)))
        store.setSharing(S1, true)
        servers.removeAccount(S1, U1)
        assertEquals(listOf(key(b)), store.afterAccountGone(S1))
        assertEquals(listOf(U2), store.owners(key(a)))
        assertNull(db.downloadDao().get(S1, b.id))
        assertFalse(pinned("b"))
        assertTrue(pinned("a"))
    }

    @Test fun afterAccountGoneWithSharingOffDeletesEveryOwnerlessDownload() = runBlocking {
        seen(s1u1, a)
        seen(s1u2, a)
        store.add(s1u1, listOf(newDownload(a)))
        servers.removeAccount(S1, U1)
        assertEquals(listOf(key(a)), store.afterAccountGone(S1))
        assertEquals(0, count("downloads"))
    }

    @Test fun progressIsBytesOrSegmentsAndCompleteIsWhole() = runBlocking {
        seen(s1u1, a)
        store.add(s1u1, listOf(newDownload(a)))
        val entry = { db.offlineLibraryDao().observeOne(S1, U1, a.id).let { kotlinx.coroutines.runBlocking { it.first()!! } }.toEntry(available = true).download }
        assertNull(entry().progress)
        store.update(key(a)) { it.copy(totalBytes = 200, doneBytes = 50) }
        assertEquals(0.25f, entry().progress)
        store.update(key(a)) { it.copy(segmentsTotal = 10, segmentsDone = 9) }
        assertEquals("a conversion counts segments", 0.9f, entry().progress)
        store.update(key(a)) { it.copy(state = DownloadState.COMPLETE) }
        assertEquals(1f, entry().progress)
        assertTrue(entry().ownedByMe)
    }

    @Test fun theKeyNeverPrintsItsIds() {
        val text = DownloadKey(S1, id("a")).toString()
        assertFalse(text.contains(S1) || text.contains(id("a")))
    }
}
