package app.shoalwatch.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.shoalwatch.data.db.downloads.LibraryWriteLock
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.RELEASED_SEASON_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.RELEASED_SERIES_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.RELEASED_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.RELEASED_VISIBLE
import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SAFEGUARDING for "Watched and deleted": `observeReleased` lists only the session profile's own tombstones whose episode,
 * series and season it was shown, each clause checked by running the SQL without it (as `OfflineLibraryClausesTest`).
 */
class TombstoneTest : DownloadTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val e1 get() = ep("e1", 1, 1)

    @Before fun releasedByA() = runBlocking {
        seen(a, series, season1, e1); seen(b, series, season1, e1)
        store.add(a, listOf(newDownload(e1)))
        setState(e1, DownloadState.COMPLETE)
        store.markFetcher(key(e1), a.userId)
        ReleaseStore(db, { clock }, LibraryWriteLock.None).release(a, e1.id)
        Unit
    }

    private fun listed(s: Session) = runBlocking { db.offlineLibraryDao().observeReleased(s.serverId, s.userId, series.id).first().map { it.item.itemId } }

    private fun without(clause: String, with: String, s: Session): Int {
        assertTrue("the clause is in the query", RELEASED_SQL.contains(clause))
        var sql = RELEASED_SQL.replace(clause, with)
        for ((k, v) in mapOf("serverId" to s.serverId, "userId" to s.userId, "seriesId" to series.id)) sql = sql.replace(":$k", "'$v'")
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use { it.count }
    }

    private fun revoke(s: Session, label: String) = sql("DELETE FROM visible_items WHERE serverId = ? AND userId = ? AND itemId = ?", s.serverId, s.userId, id(label))

    @Test fun theProfileSeesItsOwnTombstoneAndNoOtherProfileDoes() {
        assertEquals(listOf(e1.id), listed(a))
        assertEquals(emptyList<String>(), listed(b))
        assertEquals(1, without("r.userId = :userId AND ", "", b))
    }

    @Test fun anEpisodeNoLongerShownIsNotListed() {
        revoke(a, "e1")
        assertEquals(emptyList<String>(), listed(a))
        assertEquals(1, without(RELEASED_VISIBLE, "", a))
    }

    @Test fun aSeriesNoLongerShownIsNotListed() {
        revoke(a, "show")
        assertEquals(emptyList<String>(), listed(a))
        assertEquals(1, without(RELEASED_SERIES_VISIBLE, "1", a))
    }

    @Test fun aSeasonNoLongerShownIsNotListed() {
        revoke(a, "season1")
        assertEquals(emptyList<String>(), listed(a))
        assertEquals(1, without(RELEASED_SEASON_VISIBLE, "1", a))
    }
}
