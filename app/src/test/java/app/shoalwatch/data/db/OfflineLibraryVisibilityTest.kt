package app.shoalwatch.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.OfflineLibraryDao
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.FILMS_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.MINE_OR_SHARED
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.ONE_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.PLAYABLE_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_EPISODES_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.VISIBLE_JOIN
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SAFEGUARDING RULE for the offline library: a profile sees a download only if the server showed that profile the item
 * (and its series, on the series screens) AND the profile owns it or sharing is on and it is COMPLETE. A = U1 (the owner),
 * B = U2, both on S1.
 *
 * Mutation checks are in the tests themselves: each case runs the same SQL with the guarding clause taken out and shows it
 * WOULD then return the row, so the clause is what keeps it out (removing the clause from the DAO fails the case):
 *  (a) MINE_OR_SHARED -> 1=1 lets B see A's unshared film;  (b) `d.state = 'COMPLETE' AND` inside it lets B see a RUNNING one;
 *  (c) the visible_items join -> plain items lets B see what it was never shown;  (d) SERIES_VISIBLE -> 1=1 lists the series;
 *  (e) the join again, for A after revocation;  (f) `d.serverId = :serverId` -> 1=1 shows S2's row;
 *  (g) `u.userId = :userId` in the user_data join -> any user gives A's watched state to B.
 */
class OfflineLibraryVisibilityTest : DownloadTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val film get() = item("film")
    private val e1 get() = ep("e1", 1, 1)
    private val dao: OfflineLibraryDao get() = db.offlineLibraryDao()

    /** A downloads the film and the episode, both COMPLETE (unless [state]). */
    private fun aDownloads(state: DownloadState = DownloadState.COMPLETE, serverId: String = S1) = runBlocking {
        val s = Session(serverId, a.userId)
        seen(s, film, series, season1, e1)
        store.add(s, listOf(newDownload(film), newDownload(e1)))
        for (it in listOf(film, e1)) store.update(DownloadKey(serverId, it.id)) { row -> row.copy(state = state) }
    }

    /** Everything [s] can see of [item] through every query, as a set of query names. */
    private suspend fun seenBy(s: Session, item: Item = film): Set<String> = buildSet {
        if (dao.observeFilms(s.serverId, s.userId).first().any { it.item.itemId == item.id }) add("films")
        if (dao.observeSeries(s.serverId, s.userId).first().any { it.item.itemId == item.seriesId }) add("series")
        if (dao.observeSeriesEpisodes(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.id }) add("episodes")
        if (dao.observeSeasons(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.seasonId }) add("seasons")
        if (dao.observeInProgress(s.serverId, s.userId).first().any { it.item.itemId == item.id }) add("inProgress")
        if (dao.observeOne(s.serverId, s.userId, item.id).first() != null) add("one")
        if (dao.observeForSeries(s.serverId, s.userId, series.id).first().any { it.item.itemId == item.id }) add("forSeries")
        if (dao.getPlayable(s.serverId, s.userId, item.id) != null) add("playable")
    }

    /** Rows [sql] returns with its named parameters bound to these values, after replacing [clause] (which must be in it) with [with]. */
    private fun rowsWithout(sql: String, clause: String, with: String, params: Map<String, String>): Int {
        assertTrue("the clause is in the query", sql.contains(clause))
        var bound = sql.replace(clause, with)
        for ((k, v) in params) bound = bound.replace(":$k", "'$v'")
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(bound)).use { it.count }
    }

    private fun params(s: Session, itemId: String = film.id) = mapOf("serverId" to s.serverId, "userId" to s.userId, "itemId" to itemId, "seriesId" to series.id)

    @Test fun aSharingOffBSeesNothingEvenWithAVisibilityRecord() = runBlocking {
        aDownloads()
        seen(b, film, series, season1, e1)
        assertEquals(emptySet<String>(), seenBy(b, film) + seenBy(b, e1))
        assertEquals(setOf("films", "series", "episodes", "seasons", "one", "forSeries", "playable"), seenBy(a, film) + seenBy(a, e1))
        assertEquals(1, rowsWithout(FILMS_SQL, MINE_OR_SHARED, "1=1", params(b)))
    }

    @Test fun bSharingOnBSeesItOnceCompleteAndNotWhileRunning() = runBlocking {
        aDownloads(DownloadState.RUNNING)
        confirm(b, film, e1) // the strict sharing rule: B's own permission is confirmed
        seen(b, film, series, season1, e1)
        store.setSharing(S1, true)
        assertEquals(emptySet<String>(), seenBy(b, film) + seenBy(b, e1))
        assertEquals(1, rowsWithout(ONE_SQL, "d.state = 'COMPLETE' AND ", "", params(b)))
        for (it in listOf(film, e1)) store.update(key(it)) { row -> row.copy(state = DownloadState.COMPLETE) }
        assertEquals(setOf("films", "series", "episodes", "seasons", "one", "forSeries", "playable"), seenBy(b, film) + seenBy(b, e1))
        assertEquals("a non-owner never sees it as in progress", false, dao.observeOne(S1, b.userId, film.id).first()!!.ownedByMe)
    }

    @Test fun cSharingOnBWithNoRecordNeverSeesIt() = runBlocking {
        aDownloads()
        confirm(b, film, e1) // the strict sharing rule: B's own permission is confirmed
        store.setSharing(S1, true)
        seen(b, series, season1) // the series is shown to B, but not the film or the episode
        assertEquals(emptySet<String>(), seenBy(b, film) + seenBy(b, e1))
        val noJoin = "JOIN items v ON v.serverId = d.serverId AND v.itemId = d.itemId "
        assertEquals(1, rowsWithout(PLAYABLE_SQL, VISIBLE_JOIN, noJoin, params(b)))
        assertEquals(1, rowsWithout(SERIES_EPISODES_SQL, VISIBLE_JOIN, noJoin, params(b)))
    }

    @Test fun dBWithTheEpisodesRecordButNotTheSeriessDoesNotGetTheSeries() = runBlocking {
        aDownloads()
        confirm(b, film, e1) // the strict sharing rule: B's own permission is confirmed
        store.setSharing(S1, true)
        seen(b, e1, season1)
        assertEquals(setOf("one", "playable"), seenBy(b, e1))
        assertEquals(1, rowsWithout(SERIES_SQL, SERIES_VISIBLE, "1=1", params(b)))
    }

    @Test fun eARevokedRecordHidesAsOwnDownload() = runBlocking {
        aDownloads()
        library.revokeVisibility(a, film.id)
        assertEquals(emptySet<String>(), seenBy(a, film))
        val noJoin = "JOIN items v ON v.serverId = d.serverId AND v.itemId = d.itemId "
        assertEquals(1, rowsWithout(FILMS_SQL, VISIBLE_JOIN, noJoin, params(a)))
    }

    @Test fun fAnotherServersDownloadWithTheSameIdNeverAppears() = runBlocking {
        aDownloads(serverId = S2)
        seen(a, film, series, season1, e1) // S1 knows the items too, but has no download of them
        assertEquals(emptySet<String>(), seenBy(a, film) + seenBy(a, e1))
        assertEquals(1, rowsWithout(FILMS_SQL, "d.serverId = :serverId AND", "", params(a)))
    }

    @Test fun gTheWatchedStateShownIsTheSessionUsersOwn() = runBlocking {
        aDownloads()
        confirm(b, film, e1) // the strict sharing rule: B's own permission is confirmed
        store.setSharing(S1, true)
        seen(b, film, series, season1, e1)
        db.itemDao().setPlayed(S1, a.userId, e1.id, true, 0, 5)
        assertEquals(true, dao.observeSeriesEpisodes(S1, a.userId, series.id).first().single().userData?.played)
        assertNull(dao.observeSeriesEpisodes(S1, b.userId, series.id).first().single().userData)
        assertNull(dao.observeOne(S1, b.userId, e1.id).first()!!.userData)
        val anyUser = "LEFT JOIN user_data u ON u.serverId = d.serverId AND u.itemId = d.itemId "
        val sql = SERIES_EPISODES_SQL
        assertTrue(sql.contains("LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId "))
        var bound = sql.replace("LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId ", anyUser)
        for ((k, v) in params(b)) bound = bound.replace(":$k", "'$v'")
        val leaked = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(bound)).use { c ->
            c.moveToFirst(); c.getInt(c.getColumnIndexOrThrow("ud_played"))
        }
        assertEquals("without the user condition B would be shown A's watched state", 1, leaked)
    }

    @Test fun theOfflineEpisodeOrderIsTheLibrarysOwn() {
        assertTrue(ItemDao.EPISODES_SQL.endsWith(OfflineLibraryDao.EPISODE_ORDER))
    }

    @Test fun episodesComeInTheLibrarysOrderWithSpecialsLast() = runBlocking {
        val special = ep("sp", 0, 1)
        val e2 = ep("e2", 1, 2)
        val s2e1 = ep("s2e1", 2, 1)
        val unnumbered = episode("x", 1, null)
        val all = listOf(special, s2e1, unnumbered, e2, e1)
        val specials = item("season0", app.shoalwatch.data.library.ItemKind.SEASON, "Specials", seriesId = "show", parentId = "show", indexNumber = 0)
        seen(a, series, season1, season2, specials, *all.toTypedArray()) // the series screens need each season's own record too
        store.add(a, all.map { newDownload(it) })
        for (it in all) store.update(key(it)) { row -> row.copy(state = DownloadState.COMPLETE) }
        assertEquals(listOf(e1, e2, unnumbered, s2e1, special).map { it.id }, dao.observeSeriesEpisodes(S1, a.userId, series.id).first().map { it.item.itemId })
        assertEquals(5, dao.observeSeries(S1, a.userId).first().single().episodeCount)
    }
}
