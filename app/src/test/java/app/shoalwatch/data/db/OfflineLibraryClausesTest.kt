package app.shoalwatch.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.OfflineLibraryDao
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.FILMS_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.FOR_SERIES_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.IN_PROGRESS_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.MINE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.ONE_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.PLAYABLE_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SEASONS_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SEASON_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_EPISODES_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_SQL
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safeguarding clauses the first suite left unguarded (Tasks 1–4 review I-1, I-2), cases (h) to (m), with the same in-test
 * mutation check as [OfflineLibraryVisibilityTest]: each runs the DAO's SQL with the clause taken out and shows the row would
 * then appear, so the clause is what keeps it out. A = U1 (the owner), B = U2, both on S1.
 */
class OfflineLibraryClausesTest : DownloadTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val film get() = item("film")
    private val e1 get() = ep("e1", 1, 1)
    private val dao: OfflineLibraryDao get() = db.offlineLibraryDao()

    private fun downloads(s: Session, vararg items: Item, state: DownloadState = DownloadState.COMPLETE) = runBlocking {
        store.add(s, items.map { newDownload(it) })
        for (it in items) store.update(DownloadKey(s.serverId, it.id)) { row -> row.copy(state = state) }
    }

    private suspend fun seenBy(s: Session, item: Item): Set<String> = buildSet {
        if (dao.observeFilms(s.serverId, s.userId).first().any { it.item.itemId == item.id }) add("films")
        if (dao.observeSeries(s.serverId, s.userId).first().any { it.item.itemId == item.seriesId }) add("series")
        if (dao.observeSeriesEpisodes(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.id }) add("episodes")
        if (dao.observeSeasons(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.seasonId }) add("seasons")
        if (dao.observeInProgress(s.serverId, s.userId).first().any { it.item.itemId == item.id }) add("inProgress")
        if (dao.observeOne(s.serverId, s.userId, item.id).first() != null) add("one")
        if (dao.observeForSeries(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.id }) add("forSeries")
        if (dao.getPlayable(s.serverId, s.userId, item.id) != null) add("playable")
    }

    private fun bind(sql: String, s: Session, itemId: String = film.id): String {
        var bound = sql
        for ((k, v) in mapOf("serverId" to s.serverId, "userId" to s.userId, "itemId" to itemId, "seriesId" to series.id)) bound = bound.replace(":$k", "'$v'")
        return bound
    }

    /** Rows [sql] returns for [s] once every [clause] in it is replaced with [with] (the clause must be there). */
    private fun rowsWithout(sql: String, clause: String, with: String, s: Session, itemId: String = film.id): Int {
        assertTrue("the clause is in the query", sql.contains(clause))
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(bind(sql.replace(clause, with), s, itemId))).use { it.count }
    }

    /** The first row's [column] as an Int (null if SQL NULL) after the same replacement. */
    private fun firstWithout(sql: String, clause: String, with: String, s: Session, column: String): Int? {
        assertTrue("the clause is in the query", sql.contains(clause))
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(bind(sql.replace(clause, with), s))).use { c ->
            assertTrue(c.moveToFirst())
            val i = c.getColumnIndexOrThrow(column)
            if (c.isNull(i)) null else c.getInt(i)
        }
    }

    @Test fun hBWithTheSeriesAndEpisodeButNotTheSeasonGetsNothingOnTheSeriesScreens() = runBlocking {
        seen(a, series, season1, e1)
        downloads(a, e1)
        confirm(b, e1)
        store.setSharing(S1, true)
        seen(b, series, e1) // the server hid season 1 from B
        assertEquals(setOf("one", "playable"), seenBy(b, e1))
        assertEquals(1, rowsWithout(SERIES_EPISODES_SQL, SEASON_VISIBLE, "1=1", b))
        assertEquals(1, rowsWithout(FOR_SERIES_SQL, SEASON_VISIBLE, "1=1", b))
        assertEquals(1, rowsWithout(SERIES_SQL, SEASON_VISIBLE, "1=1", b))
        val seasonJoin = "JOIN visible_items v ON v.serverId = i.serverId AND v.userId = :userId AND v.itemId = i.itemId "
        assertEquals("without the season row's own record the hidden season is listed", 1, rowsWithout(SEASONS_SQL, seasonJoin, "", b))
    }

    @Test fun hAnEpisodeWithNoSeasonStillShows() = runBlocking {
        val loose = item("loose", ItemKind.EPISODE, "Loose", seriesId = "show", indexNumber = 3)
        seen(a, series, loose)
        downloads(a, loose)
        assertEquals(setOf("series", "episodes", "one", "forSeries", "playable"), seenBy(a, loose))
    }

    @Test fun iSharingOnForAnotherServerDoesNotShareThisOnesDownloads() = runBlocking {
        seen(a, film)
        downloads(a, film)
        confirm(b, film)
        store.setSharing(S2, true)
        seen(b, film)
        assertEquals(emptySet<String>(), seenBy(b, film))
        assertEquals("without x.serverId, S2's setting would open S1's downloads", 1, rowsWithout(FILMS_SQL, "x.serverId = d.serverId AND ", "", b))
    }

    @Test fun jAnOwnerRowOnAnotherServerDoesNotMakeAnOwnerHere() = runBlocking {
        seen(b, film)
        downloads(b, film) // on S1 the film is B's alone
        seen(s2u1, film)
        downloads(s2u1, film) // on S2 the same item id is A's
        seen(a, film)
        assertEquals(emptySet<String>(), seenBy(a, film))
        assertEquals(1, rowsWithout(FILMS_SQL, "o.serverId = d.serverId AND ", "", a))
        assertEquals(true, dao.observeOne(S1, b.userId, film.id).first()!!.ownedByMe)
    }

    @Test fun kTheSeriesAndSeasonWatchedStateShownIsTheSessionUsersOwn() = runBlocking {
        seen(a, series, season1, e1)
        downloads(a, e1)
        confirm(b, e1)
        store.setSharing(S1, true)
        seen(b, series, season1, e1)
        db.itemDao().setPlayed(S1, a.userId, series.id, true, 0, 5)
        db.itemDao().setPlayed(S1, a.userId, season1.id, true, 0, 5)
        assertEquals(true, dao.observeSeries(S1, a.userId).first().single().userData?.played)
        assertNull(dao.observeSeries(S1, b.userId).first().single().userData)
        assertEquals(true, dao.observeSeasons(S1, a.userId, series.id).first().single().userData?.played)
        assertNull(dao.observeSeasons(S1, b.userId, series.id).first().single().userData)
        assertEquals(1, firstWithout(SERIES_SQL, "u.userId = :userId AND u.itemId = d.seriesId", "u.itemId = d.seriesId", b, "ud_played"))
        assertEquals(1, firstWithout(SEASONS_SQL, "u.userId = :userId AND u.itemId = i.itemId", "u.itemId = i.itemId", b, "ud_played"))
    }

    @Test fun lAnOwnersUnfinishedDownloadIsInProgressNotPlayableOrListed() = runBlocking {
        seen(a, film, series, season1, e1)
        downloads(a, film, e1, state = DownloadState.RUNNING)
        assertEquals(setOf("inProgress", "one", "forSeries"), seenBy(a, film) + seenBy(a, e1))
        assertEquals(true, dao.observeOne(S1, a.userId, film.id).first()!!.ownedByMe)
        assertEquals("queue order", listOf(film.id, e1.id), dao.observeInProgress(S1, a.userId).first().map { it.item.itemId })
        assertEquals(1, rowsWithout(FILMS_SQL, "d.kind = 'MOVIE' AND d.state = 'COMPLETE' AND ", "d.kind = 'MOVIE' AND ", a))
        assertEquals(1, rowsWithout(PLAYABLE_SQL, "d.itemId = :itemId AND d.state = 'COMPLETE' AND ", "d.itemId = :itemId AND ", a))
        assertEquals(1, rowsWithout(SERIES_EPISODES_SQL, "+d.state = 'COMPLETE' ", "1=1 ", a, e1.id))
    }

    @Test fun mInProgressIsForOwnersOnlyEvenWithSharingOn() = runBlocking {
        seen(a, film)
        downloads(a, film, state = DownloadState.QUEUED)
        store.setSharing(S1, true)
        seen(b, film)
        assertEquals(listOf(film.id), dao.observeInProgress(S1, a.userId).first().map { it.item.itemId })
        assertEquals(emptyList<String>(), dao.observeInProgress(S1, b.userId).first().map { it.item.itemId })
    }

    /**
     * Tasks 5-8 review Minor 1: `o.serverId = d.serverId` inside MINE (owner controls, and the in-progress queue) is guarded too.
     * B owns the film on S1 (shared); A owns the same item id on S2. On S1, A gets no owner controls and none of B's queue.
     */
    @Test fun nAnOwnerRowOnAnotherServerGivesNoOwnerControlsOrQueueHere() = runBlocking {
        seen(b, film)
        downloads(b, film)
        confirm(a, film)
        store.setSharing(S1, true)
        seen(s2u1, film)
        downloads(s2u1, film, state = DownloadState.RUNNING)
        seen(a, film)
        val weak = MINE.replace("o.serverId = d.serverId AND ", "")
        assertEquals(false, dao.observeOne(S1, a.userId, film.id).first()!!.ownedByMe)
        assertEquals("without the clause A would get the owner controls", 1, firstWithout(ONE_SQL, "$MINE AS ownedByMe", "$weak AS ownedByMe", a, "ownedByMe"))
        store.update(DownloadKey(S1, film.id)) { it.copy(state = DownloadState.RUNNING) }
        assertEquals(emptyList<String>(), dao.observeInProgress(S1, a.userId).first().map { it.item.itemId })
        assertEquals("without the clause B's queue would be A's", 1, rowsWithout(IN_PROGRESS_SQL, MINE, weak, a))
    }
}
