package app.shoalwatch.data.playback

import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The play queue and its registry: order, shuffle, bounds, the ends, tokens. Pure. */
class PlayQueueTest {
    private fun films(range: IntRange) = range.map { item(it, ItemKind.MOVIE) }

    @Test fun keepsTheOrderGiven() {
        val q = PlayQueue.of(films(1..5), shuffle = false)!!
        assertEquals((1..5).map { id(it) }, q.ids)
    }

    @Test fun shuffleKeepsEveryItemExactlyOnce() {
        repeat(20) { seed ->
            val q = PlayQueue.of(films(1..50), shuffle = true, random = Random(seed))!!
            assertEquals(50, q.ids.size)
            assertEquals((1..50).map { id(it) }.toSet(), q.ids.toSet())
        }
    }

    @Test fun shuffleReallyReorders() {
        val q = PlayQueue.of(films(1..50), shuffle = true, random = Random(1))!!
        assertNotEquals((1..50).map { id(it) }, q.ids)
    }

    @Test fun onlyPlayableKindsAreKept() {
        val items = listOf(
            item(1, ItemKind.MOVIE), item(2, ItemKind.EPISODE), item(3, ItemKind.VIDEO), item(4, ItemKind.PHOTO), item(5, ItemKind.SERIES),
            item(6, ItemKind.SEASON), item(7, ItemKind.BOX_SET), item(8, ItemKind.FOLDER), item(9, ItemKind.OTHER), item(10, ItemKind.PLAYLIST),
        )
        assertEquals(listOf(id(1), id(2), id(3)), PlayQueue.of(items, false)!!.ids)
    }

    @Test fun nothingPlayableIsNoQueue() {
        assertNull(PlayQueue.of(emptyList(), false))
        assertNull(PlayQueue.of(listOf(item(4, ItemKind.PHOTO)), true))
    }

    @Test fun aRepeatedItemIsKeptOnceAtItsFirstPlace() {
        val q = PlayQueue.of(listOf(item(1), item(2), item(1), item(3)), false)!!
        assertEquals(listOf(id(1), id(2), id(3)), q.ids)
    }

    @Test fun aBadIdIsDropped() {
        val bad = item(1).copy(id = "../etc")
        assertEquals(listOf(id(2)), PlayQueue.of(listOf(bad, item(2)), false)!!.ids)
    }

    @Test fun isBoundedAtTwoThousand() {
        val q = PlayQueue.of(films(1..2_500), false)!!
        assertEquals(2_000, q.ids.size)
        assertEquals(id(2_000), q.ids.last())
    }

    @Test fun nextAndPreviousStopAtTheEnds() {
        val q = PlayQueue.of(films(1..3), false)!!
        assertEquals(id(2), q.next(id(1)))
        assertEquals(id(3), q.next(id(2)))
        assertNull(q.next(id(3)))
        assertNull(q.previous(id(1)))
        assertEquals(id(1), q.previous(id(2)))
    }

    @Test fun anItemOutsideTheQueueHasNoNeighbours() {
        val q = PlayQueue.of(films(1..3), false)!!
        assertNull(q.next(id(9))); assertNull(q.previous(id(9)))
        assertTrue(!q.contains(id(9)) && q.contains(id(2)))
    }

    @Test fun tokensAreRandomAndUnknownOnesPlayAlone() {
        val queues = PlayQueues()
        val q = PlayQueue.of(films(1..3), false)!!
        val a = queues.put(q); val b = queues.put(q)
        assertNotEquals(a, b)
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(a)) // 128 bits, URL-safe
        assertEquals(q, queues.get(a))
        assertNull(queues.get("AAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(queues.get(null)); assertNull(queues.get("")); assertNull(queues.get("../../x"))
        assertNull(PlayQueues().get(a)) // a new process: unknown
    }

    @Test fun theRegistryKeepsOnlyTheLatestFew() {
        val queues = PlayQueues(maxQueues = 2)
        val q = PlayQueue.of(films(1..3), false)!!
        val first = queues.put(q); val second = queues.put(q); val third = queues.put(q)
        assertNull(queues.get(first)); assertNotNull(queues.get(second)); assertNotNull(queues.get(third))
    }

    @Test fun neverPrintsItsIds() {
        assertEquals("PlayQueue(3 items)", PlayQueue.of(films(1..3), false).toString())
    }
}
