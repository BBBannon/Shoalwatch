package app.shoalwatch.data.sync

import androidx.room.withTransaction
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** `OutboxStore` on in-memory Room: rows and marks commit with the caller, coalesce per (profile, item, kind), and are read per profile. */
class OutboxStoreTest : LibraryTestBase() {
    private var clock = 5_000L
    private lateinit var outbox: OutboxStore

    @Before fun openOutbox() { outbox = OutboxStore(db) { clock } }

    private fun played(label: String, flag: Boolean, at: Long = 100) = Change(id(label), ItemKind.EPISODE, ChangeKind.PLAYED, flag = flag, occurredAt = at)
    private fun favourite(label: String, flag: Boolean) = Change(id(label), ItemKind.MOVIE, ChangeKind.FAVOURITE, flag = flag, occurredAt = 100)
    private fun position(label: String, ms: Long) = Change(id(label), ItemKind.MOVIE, ChangeKind.POSITION, positionMs = ms, occurredAt = 100)
    private fun play(label: String, at: Long = 100) = Change(id(label), ItemKind.MOVIE, ChangeKind.PLAY, occurredAt = at)
    private fun marks(vararg labels: String, left: List<Pair<String, String>> = emptyList()) =
        OutboxStore.Marks(labels.map(::id), left.map { (label, key) -> id(label) to key })

    private fun enqueue(s: Session, change: Change, marks: OutboxStore.Marks = marks(), keys: List<String> = listOf("resume")) =
        runBlocking { outbox.enqueue(s, change, marks, keys) }

    private fun rows(s: Session): List<Long> =
        db.openHelper.readableDatabase.query("SELECT seq FROM sync_outbox WHERE serverId = ? AND userId = ? ORDER BY seq", arrayOf(s.serverId, s.userId))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun marksOf(seq: Long): Set<Pair<String, String>> =
        db.openHelper.readableDatabase.query("SELECT itemId, leftList FROM sync_marks WHERE seq = ?", arrayOf(seq))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    @Test fun anEnqueueInARolledBackTransactionLeavesNoRowAndNoMark() = runBlocking {
        try {
            db.withTransaction {
                outbox.enqueue(s1u1, played("e1", true), marks("e1", left = listOf("e1" to "nextUp")), listOf("resume"))
                error("the caller's local change failed")
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, count("sync_outbox"))
        assertEquals(0, count("sync_marks"))
    }

    @Test fun aSecondPlayedReplacesTheFirstWithANewSeqAndItsOwnMarks() {
        val first = enqueue(s1u1, played("e1", true), marks("e1", left = listOf("e1" to "nextUp")))
        val second = enqueue(s1u1, played("e1", false), marks("e1", "season1"))
        assertEquals(listOf(second), rows(s1u1))
        assertTrue(second > first)
        assertEquals(setOf(id("e1") to "", id("season1") to ""), marksOf(second))
        assertEquals(0, count("sync_marks", "seq = $first"))
        assertEquals(0, count("sync_outbox", "flag = 1"))
    }

    @Test fun favouriteAndPositionCoalesceTooButTwoPlaysStayTwoRows() {
        enqueue(s1u1, favourite("f", true))
        val fav = enqueue(s1u1, favourite("f", false))
        enqueue(s1u1, position("f", 10_000))
        val pos = enqueue(s1u1, position("f", 20_000))
        val play1 = enqueue(s1u1, play("f", 100))
        val play2 = enqueue(s1u1, play("f", 200))
        assertEquals(listOf(fav, pos, play1, play2), rows(s1u1))
    }

    @Test fun coalescingNeverCrossesProfilesItemsOrKinds() {
        val a = enqueue(s1u1, played("e1", true))
        val b = enqueue(s1u2, played("e1", true))
        val c = enqueue(s2u1, played("e1", true))
        val d = enqueue(s1u1, played("e2", true))
        val e = enqueue(s1u1, favourite("e1", true))
        val f = enqueue(s1u1, position("e1", 0))
        assertEquals(listOf(a, d, e, f), rows(s1u1))
        assertEquals(listOf(b), rows(s1u2))
        assertEquals(listOf(c), rows(s2u1))
    }

    @Test fun takeReturnsOnlyTheMarksNoOtherRowStillCovers() = runBlocking {
        val season = enqueue(s1u1, played("season1", true), marks("season1", "e1", "e2"), listOf("resume", "nextUp"))
        enqueue(s1u1, played("e1", true), marks("e1"))
        enqueue(s1u2, played("e2", true), marks("e2")) // another profile's mark covers nothing of s1u1's
        val landed = outbox.take(season)!!
        assertEquals(setOf(id("season1"), id("e2")), landed.unmarked.toSet())
        assertEquals(listOf("resume", "nextUp"), landed.refreshKeys)
        assertEquals(id("season1"), landed.itemId)
        assertEquals(ChangeKind.PLAYED, landed.kind)
        assertTrue(outbox.hasMark(s1u1, id("e1")))
        assertFalse(outbox.hasMark(s1u1, id("e2")))
        assertNull("a second take finds nothing", outbox.take(season))
    }

    @Test fun leftListIsOnlyThisProfilesUnsentDeparturesFromThatList() = runBlocking {
        val a = enqueue(s1u1, position("f1", 0), marks("f1", left = listOf("f1" to "resume")))
        enqueue(s1u1, position("f2", 0), marks("f2", left = listOf("f2" to "resume", "f2" to "nextUp")))
        enqueue(s1u2, position("f3", 0), marks("f3", left = listOf("f3" to "resume")))
        assertEquals(setOf(id("f1"), id("f2")), outbox.leftList(s1u1, "resume"))
        assertEquals(setOf(id("f2")), outbox.leftList(s1u1, "nextUp"))
        assertEquals(setOf(id("f3")), outbox.leftList(s1u2, "resume"))
        outbox.take(a)
        assertEquals(setOf(id("f2")), outbox.leftList(s1u1, "resume"))
        assertEquals(emptySet<String>(), outbox.leftList(s2u1, "resume"))
    }

    @Test fun statusCountsPerProfileAndNeedsSignInFollowsTheFlow() = runBlocking {
        val signedIn = MutableStateFlow(true)
        assertEquals(SyncStatus.NONE, outbox.status(s1u1, signedIn).first())
        val a = enqueue(s1u1, played("e1", true))
        enqueue(s1u1, played("e2", true))
        enqueue(s1u2, played("e3", true))
        repeat(OutboxStore.STUCK_AFTER) { outbox.deferAfterServerError(a) }
        assertEquals(SyncStatus(2, 1, false, null), outbox.status(s1u1, signedIn).first())
        assertEquals(SyncStatus(1, 0, false, null), outbox.status(s1u2, signedIn).first())
        signedIn.value = false
        assertEquals(SyncStatus(2, 1, true, null), outbox.status(s1u1, signedIn).first())
        assertEquals("nothing waiting needs no sign-in", SyncStatus.NONE, outbox.status(s2u1, signedIn).first())
        clock = 9_000
        outbox.touchSynced(s1u1)
        assertEquals(9_000L, outbox.status(s1u1, signedIn).first().lastSyncedAt)
        assertEquals(2, outbox.waiting(s1u1))
        assertEquals(listOf(s1u1, s1u2), outbox.accountsWithWork())
        assertEquals(setOf(s1u1.serverId), outbox.serversWithWork().first())
    }

    @Test fun touchSyncedForAnAccountThatHasGoneWritesNothing() = runBlocking {
        servers.removeAccount(s1u2.serverId, s1u2.userId)
        outbox.touchSynced(s1u2)
        assertEquals(0, count("sync_state"))
    }

    @Test fun aFailingReadEmitsNoneAndNeverThrows() = runBlocking {
        enqueue(s1u1, played("e1", true))
        db.close()
        assertEquals(SyncStatus.NONE, outbox.status(s1u1, flowOf(false)).first())
        assertEquals(emptySet<String>(), outbox.serversWithWork().first())
    }

    @Test fun enqueueRefusesIdsThatAreNotNormalisedAndTooManyRefreshKeys() {
        val upper = id("e1").uppercase()
        val refusals = listOf<() -> Unit>(
            { enqueue(s1u1, Change(upper, ItemKind.EPISODE, ChangeKind.PLAYED, flag = true, occurredAt = 1)) },
            { enqueue(s1u1, played("e1", true), OutboxStore.Marks(listOf("not-an-id"), emptyList())) },
            { enqueue(s1u1, played("e1", true), OutboxStore.Marks(emptyList(), listOf(upper to "resume"))) },
            { enqueue(Session(s1u1.serverId, "u1"), played("e1", true)) },
            { enqueue(s1u1, played("e1", true), keys = List(300) { "nextUp$it" }) },
        )
        for (refusal in refusals) {
            try {
                refusal()
                fail("accepted")
            } catch (e: IllegalArgumentException) {
                assertFalse("the message echoes nothing", e.message.orEmpty().contains("e1", ignoreCase = true) || e.message.orEmpty().contains(upper))
            }
        }
        assertEquals(0, count("sync_outbox"))
        enqueue(s1u1, played("e1", true), keys = List(10) { "x".repeat(199) }) // 1,999 characters joined
        assertEquals(1, count("sync_outbox"))
    }

    @Test fun aRowOfAKindThisVersionCannotReadIsDroppedOnRead() = runBlocking {
        val seq = enqueue(s1u1, played("e1", true), marks("e1"))
        sql("UPDATE sync_outbox SET kind = 'WATCHED_TOGETHER' WHERE seq = ?", seq)
        assertNull(outbox.head(s1u1, clock))
        assertEquals(0, count("sync_outbox"))
        assertEquals(0, count("sync_marks"))
    }

    @Test fun markSendingKeepsTheFirstPlayCountAndDeferBacksOffToSixHours() = runBlocking {
        val seq = enqueue(s1u1, play("f"))
        outbox.markSending(seq, 3)
        outbox.markSending(seq, 4)
        var row = outbox.head(s1u1, clock)!!
        assertTrue(row.sentOnce)
        assertEquals(3, row.sentPlayCount)
        assertEquals(2, row.attempts)
        outbox.deferAfterServerError(seq)
        assertEquals(clock + 60_000, raw(seq).notBefore)
        outbox.deferAfterServerError(seq)
        assertEquals(clock + 120_000, raw(seq).notBefore)
        repeat(40) { outbox.deferAfterServerError(seq) }
        row = raw(seq)
        assertEquals(42, row.serverErrors)
        assertEquals(clock + 6 * 3_600_000L, row.notBefore)
    }

    private suspend fun raw(seq: Long): OutboxEntity = outbox.head(s1u1, Long.MAX_VALUE).also { assertEquals(seq, it!!.seq) }!!

    @Test fun nothingHerePrintsAnIdAPositionOrADate() {
        val change = Change(id("e1"), ItemKind.EPISODE, ChangeKind.POSITION, positionMs = 123_456, occurredAt = 987_654_321)
        val printed = listOf(
            change,
            SyncStatus(1, 0, false, 987_654_321),
            ServerUserData(true, 3, 123_456, true, 987_654_321),
            UserDataUpdate(true, 3, 123_456, 987_654_321),
            OutboxStore.Landed(id("e1"), ItemKind.EPISODE, ChangeKind.PLAY, 987_654_321, false, listOf(id("e1")), listOf("resume")),
            OutboxStore.Marks(listOf(id("e1")), listOf(id("e1") to "resume")),
        ).joinToString { it.toString() }
        for (secret in listOf(id("e1"), "123456", "987654321", "resume")) assertFalse("printed $secret: $printed", secret in printed)
        assertNotEquals("", printed)
    }
}
