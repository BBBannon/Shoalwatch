package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.db.library.EvictionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Downloading pins the item, its season and its series; the last download going unpins them; eviction keeps what is pinned. */
class DownloadPinningTest : DownloadTestBase() {
    private val e1 get() = ep("e1", 1, 1)
    private val e2 get() = ep("e2", 1, 2)

    private fun all(vararg labels: String) = labels.map { pinned(it) }

    @Test fun addingAnEpisodePinsItItsSeasonAndItsSeries() = runBlocking {
        seen(s1u1, series, season1, e1)
        store.add(s1u1, listOf(newDownload(e1)))
        assertEquals(listOf(true, true, true), all("e1", "season1", "show"))
    }

    @Test fun anotherEpisodeOfTheSeasonKeepsItPinnedWhenTheFirstGoes() = runBlocking {
        seen(s1u1, series, season1, e1, e2)
        store.add(s1u1, listOf(newDownload(e1)))
        store.add(s1u1, listOf(newDownload(e2)))
        store.delete(key(e1))
        assertEquals(listOf(false, true, true, true), all("e1", "e2", "season1", "show"))
    }

    @Test fun deletingTheLastOneUnpinsAllThree() = runBlocking {
        seen(s1u1, series, season1, e1, e2)
        store.add(s1u1, listOf(newDownload(e1), newDownload(e2)))
        store.delete(key(e1))
        store.removeOwner(s1u1, e2.id)
        assertEquals(listOf(false, false, false, false), all("e1", "e2", "season1", "show"))
    }

    @Test fun aFilmPinsOnlyItself() = runBlocking {
        val film = item("film")
        seen(s1u1, film, series, season1)
        store.add(s1u1, listOf(newDownload(film)))
        assertEquals(listOf(true, false, false), all("film", "show", "season1"))
        assertEquals(1, count("items", "pinned = 1"))
    }

    @Test fun anotherServersRowWithTheSameIdIsNotPinned() = runBlocking {
        seen(s1u1, e1)
        seen(s2u1, e1)
        store.add(s1u1, listOf(newDownload(e1)))
        assertFalse(pinned("e1", app.shoalwatch.S2))
    }

    @Test fun evictionKeepsPinnedRowsTheirVisibilityAndAPinnedSeriesSeasonsList() = runBlocking {
        val other = item("other", app.shoalwatch.data.library.ItemKind.SERIES, "Other")
        library.writePage(s1u1, "seasons:${series.id}", 0, listOf(row(season1), row(season2)), 2, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "seasons:${other.id}", 0, listOf(row(item("otherseason", app.shoalwatch.data.library.ItemKind.SEASON, seriesId = "other"))), 1, t0, requestedAtSeq = 0)
        seen(s1u1, series, other, e1)
        store.add(s1u1, listOf(newDownload(e1)))
        library.evict(t0.plus(Duration.ofDays(1)), EvictionPolicy(Duration.ZERO, Duration.ZERO, 0))
        assertTrue(listOf("e1", "season1", "show").all { count("items", "itemId = '${id(it)}'") == 1 })
        assertEquals(3, count("visible_items", "userId = '$U1' AND itemId IN ('${id("e1")}', '${id("season1")}', '${id("show")}')"))
        assertEquals("the pinned series keeps its seasons list", 2, count("list_entries", "listKey = 'seasons:${series.id}'"))
        assertEquals(1, count("list_state", "listKey = 'seasons:${series.id}'"))
        assertEquals("an unpinned series loses its list", 0, count("list_entries", "listKey = 'seasons:${other.id}'"))
        assertEquals(0, count("list_state", "listKey = 'seasons:${other.id}'"))
        assertEquals(0, count("items", "itemId = '${id("other")}'"))
    }

    @Test fun upsertingThePinnedItemFromAServerPageLeavesItPinned() = runBlocking {
        seen(s1u1, e1)
        store.add(s1u1, listOf(newDownload(e1)))
        library.writePage(s1u1, "resume", 0, listOf(row(e1.copy(name = "Renamed"))), 1, t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(e1)), t0, authoritative = true, requestedAtSeq = 0)
        assertTrue(pinned("e1"))
        assertEquals("the authoritative write landed", "Episode e1", db.itemDao().getItemEntity(S1, e1.id)!!.name)
    }
}
