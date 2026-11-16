package app.shoalwatch.data.sync

import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** `OutboxStore.head`: seq order per profile; a deferred row holds the profile back; a stuck row holds back only its own item. */
class OutboxOrderTest : LibraryTestBase() {
    private var clock = 1_000_000L
    private lateinit var outbox: OutboxStore

    @Before fun openOutbox() { outbox = OutboxStore(db) { clock } }

    private fun enqueue(s: Session, label: String, kind: ChangeKind = ChangeKind.PLAY) = runBlocking {
        val change = when (kind) {
            ChangeKind.PLAY -> Change(id(label), ItemKind.MOVIE, kind, occurredAt = 1)
            ChangeKind.POSITION -> Change(id(label), ItemKind.MOVIE, kind, positionMs = 10, occurredAt = 1)
            else -> Change(id(label), ItemKind.MOVIE, kind, flag = true, occurredAt = 1)
        }
        outbox.enqueue(s, change, OutboxStore.Marks(listOf(id(label)), emptyList()), emptyList())
    }

    private fun head(s: Session) = runBlocking { outbox.head(s, clock)?.seq }
    private fun take(seq: Long) = runBlocking { outbox.take(seq) }
    private fun defer(seq: Long, times: Int = 1) = runBlocking { repeat(times) { outbox.deferAfterServerError(seq) } }

    @Test fun headsComeInSeqOrder() {
        val seqs = listOf("a", "b", "c", "d", "e").map { enqueue(s1u1, it) }
        val heads = buildList { while (true) add(head(s1u1)?.also { take(it) } ?: break) }
        assertEquals(seqs, heads)
    }

    @Test fun headsPageThroughMoreThanFiftyRows() {
        val stuck = enqueue(s1u1, "x")
        defer(stuck, OutboxStore.STUCK_AFTER)
        repeat(60) { enqueue(s1u1, "x") } // all held behind their stuck item
        val other = enqueue(s1u1, "y")
        assertEquals(other, head(s1u1))
    }

    @Test fun aDeferredRowHoldsBackTheWholeProfileUntilItIsDue() {
        val a = enqueue(s1u1, "a")
        val b = enqueue(s1u1, "b")
        defer(a)
        assertNull(head(s1u1))
        clock += 59_999
        assertNull(head(s1u1))
        clock += 1
        assertEquals(a, head(s1u1))
        defer(a)
        clock += 119_999
        assertNull("the second wait is twice as long", head(s1u1))
        clock += 1
        assertEquals(a, head(s1u1))
        take(a)
        assertEquals(b, head(s1u1))
    }

    @Test fun aStuckRowIsPassedOverByAnotherItemButNotByItsOwnItemsLaterRow() {
        val stuck = enqueue(s1u1, "a", ChangeKind.PLAYED)
        val sameItem = enqueue(s1u1, "a", ChangeKind.FAVOURITE)
        val other = enqueue(s1u1, "b")
        defer(stuck, OutboxStore.STUCK_AFTER)
        assertEquals(other, head(s1u1))
        take(other)
        assertNull("its own item's later row never overtakes it", head(s1u1))
        clock += 6 * 3_600_000L
        assertEquals("a stuck row is still sent when it is due", stuck, head(s1u1))
        take(stuck)
        assertEquals(sameItem, head(s1u1))
    }

    @Test fun anotherProfilesRowsAreUnaffectedByEither() {
        val deferred = enqueue(s1u1, "a")
        val b1 = enqueue(s1u2, "a")
        val c1 = enqueue(s2u1, "a")
        defer(deferred)
        assertNull(head(s1u1))
        assertEquals(b1, head(s1u2))
        defer(b1, OutboxStore.STUCK_AFTER)
        assertNull(head(s1u2))
        assertEquals(c1, head(s2u1))
    }
}
