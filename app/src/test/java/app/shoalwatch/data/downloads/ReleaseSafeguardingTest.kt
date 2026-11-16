package app.shoalwatch.data.downloads

import androidx.sqlite.db.SimpleSQLiteQuery
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U3
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.HousekeepingDao.Companion.SHARERS_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.MINE_OR_SHARED
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.PLAYABLE_SQL
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.VISIBLE_JOIN
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAFEGUARDING: a profile can release (or count, or mark "together") only a download it may see. Each case also runs the
 * guarding query with its clause taken out, as `OfflineLibraryClausesTest` does, to show that clause is what keeps it safe.
 */
class ReleaseSafeguardingTest : ReleaseTestBase() {
    private fun bind(sql: String, s: Session, itemId: String): String {
        var bound = sql
        for ((k, v) in mapOf("serverId" to s.serverId, "userId" to s.userId, "itemId" to itemId)) bound = bound.replace(":$k", "'$v'")
        return bound
    }

    /** The first column of every row [sql] returns for [s] once [clause] is replaced with [with] (the clause must be there). */
    private fun without(sql: String, clause: String, with: String, s: Session, itemId: String): List<String> {
        assertTrue("the clause is in the query", sql.contains(clause))
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(bind(sql.replace(clause, with), s, itemId))).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun nothingChanged(s: Session, folder: java.io.File) {
        settled()
        assertTrue(hasRow(film)); assertTrue(folder.exists())
        assertEquals(0, count("released_episodes"))
        assertFalse("its watched state is untouched", playedHere(s, film))
        assertEquals(0, count("sync_outbox"))
    }

    @Test fun aProfileWithNoVisibilityRecordGetsNotYoursAndTheSameChoiceAsNoDownload() = runBlocking {
        sharing(true)
        seen(a, film)
        val folder = downloaded(a, film)
        confirm(b, film) // even with a permission on record, the child was never shown the title
        assertEquals(ReleaseOutcome.NOT_YOURS, housekeeping.release(b, film.id, together = true))
        nothingChanged(b, folder)
        val never = housekeeping.releaseChoice(b, item("never").id)
        assertEquals(never, housekeeping.releaseChoice(b, film.id))
        assertEquals(ReleaseChoice(false, false, SharedDelete.ANYONE, 0, false), never)
        // Mutation: without the visibility join the child would get the row.
        assertEquals(listOf(a.serverId), without(PLAYABLE_SQL, VISIBLE_JOIN, "", b, film.id))
    }

    @Test fun sharingOffAndNotAnOwnerIsNotYours() = runBlocking {
        seen(a, film); seen(b, film)
        val folder = downloaded(a, film)
        confirm(b, film)
        assertEquals(ReleaseOutcome.NOT_YOURS, housekeeping.release(b, film.id, together = false))
        nothingChanged(b, folder)
        assertFalse(housekeeping.releaseChoice(b, film.id).canRelease)
        assertEquals(1, without(PLAYABLE_SQL, MINE_OR_SHARED, "1", b, film.id).size)
    }

    @Test fun sharingOnButNotCompleteIsNotYours() = runBlocking {
        sharing(true)
        seen(a, film); seen(b, film)
        val folder = downloaded(a, film)
        store.update(DownloadKey(a.serverId, film.id)) { it.copy(state = DownloadState.RUNNING) }
        confirm(b, film)
        assertEquals(ReleaseOutcome.NOT_YOURS, housekeeping.release(b, film.id, together = false))
        nothingChanged(b, folder)
        assertEquals(1, without(PLAYABLE_SQL, "d.state = 'COMPLETE'", "1 = 1", b, film.id).size)
    }

    @Test fun togetherNeverMarksAProfileThatWasNotShownTheItem() = runBlocking {
        val c = Session(S1, U3)
        servers.upsertAccount(Account(S1, U3, "kid", "tok-4"), makeActive = false)
        sharing(true)
        seen(a, film)
        downloaded(a, film)
        confirm(c, film)
        assertEquals(0, housekeeping.releaseChoice(a, film.id).othersWhoShare)
        assertEquals(listOf(U3), without(SHARERS_SQL, "JOIN visible_items v ON v.serverId = a.serverId AND v.userId = a.userId AND v.itemId = d.itemId ", "", a, film.id))
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, film.id, together = true))
        assertEquals(0, playedRows(c, film))
    }

    @Test fun togetherNeverMarksASignedOutProfile() = runBlocking {
        sharing(true)
        seen(a, film); seen(b, film)
        downloaded(a, film)
        confirm(b, film)
        signedOut += b
        assertEquals(0, housekeeping.releaseChoice(a, film.id).othersWhoShare)
        assertEquals(listOf(b.userId), releases.sharers(a, film.id) { true }) // mutation: the signed-in check is what leaves it out
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, film.id, together = true))
        assertEquals(0, playedRows(b, film))
    }

    @Test fun togetherNeverMarksAProfileOnAnotherServer() = runBlocking {
        val other = Session(S2, U3)
        servers.upsertAccount(Account(S2, U3, "kid", "tok-5"), makeActive = false)
        sharing(true)
        runBlocking { store.setSharing(S2, true) }
        seen(a, film); seen(other, film)
        downloaded(a, film)
        downloaded(other, film) // the same id on the other server, its own copy
        assertEquals(0, housekeeping.releaseChoice(a, film.id).othersWhoShare)
        assertEquals(ReleaseOutcome.DELETED, housekeeping.release(a, film.id, together = true))
        assertEquals(0, playedRows(other, film))
        assertTrue("the other server's copy is untouched", hasRow(film, S2))
        assertEquals(listOf(U3), without(SHARERS_SQL, "a.serverId = :serverId AND ", "", a, film.id))
    }

    /** Tasks 11-12 review M4: a profile shown the item, but neither a fetching owner nor cleared by the server for it, is never marked. */
    @Test fun togetherNeverMarksAProfileTheServerNeverClearedForTheCopy() = runBlocking {
        sharing(true)
        seen(a, film); seen(b, film)
        downloaded(a, film) // B has no permission on record and did not fetch it
        assertEquals(emptyList<String>(), releases.sharers(a, film.id) { true })
        val clause = "AND (EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = a.userId AND o.fetched = 1) " +
            "OR EXISTS (SELECT 1 FROM download_permissions p WHERE p.serverId = d.serverId AND p.itemId = d.itemId AND p.userId = a.userId)) "
        assertEquals("mutation: the permission-or-owner clause is what leaves B out", listOf(b.userId), without(SHARERS_SQL, clause, "", a, film.id))
    }

    /** Tasks 11-12 review M4: a copy that is not COMPLETE is shared with no one. */
    @Test fun togetherNeverMarksForACopyThatIsNotComplete() = runBlocking {
        sharing(true)
        seen(a, film); seen(b, film)
        downloaded(a, film)
        confirm(b, film)
        store.update(DownloadKey(a.serverId, film.id)) { it.copy(state = DownloadState.RUNNING) }
        assertEquals(emptyList<String>(), db.housekeepingDao().sharers(a.serverId, a.userId, film.id))
        assertEquals("mutation: the COMPLETE clause is what leaves B out", listOf(b.userId), without(SHARERS_SQL, "AND d.state = 'COMPLETE' ", "", a, film.id))
    }

    @Test fun anotherServersDownloadWithTheSameIdIsUntouched() = runBlocking {
        seen(a, film); seen(s2u1, film)
        val there = downloaded(s2u1, film)
        assertEquals(ReleaseOutcome.NOT_YOURS, housekeeping.release(a, film.id, together = false))
        settled()
        assertTrue(hasRow(film, S2)); assertTrue(there.exists())
        assertFalse(playedHere(a, film))
        assertEquals(listOf(S2), without(PLAYABLE_SQL, "d.serverId = :serverId AND ", "", a, film.id))
    }
}
