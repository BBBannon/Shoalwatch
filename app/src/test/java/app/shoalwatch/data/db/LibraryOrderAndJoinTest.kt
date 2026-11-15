package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.api.inEpisodeOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The stored episode order is the gateway's order (so the two cannot drift), and the user-data join never hides a row. */
class LibraryOrderAndJoinTest : LibraryTestBase() {
    private val items get() = db.itemDao()

    private fun episodes() = buildList {
        // numbered seasons, season 0 (Specials) and no season number; numbered, zero and missing episode numbers; sort names that differ from names
        for (season in listOf(null, 0, 3, 1, 2)) for (number in listOf(null, 2, 0, 1)) {
            add(item("e-${season ?: "x"}-${number ?: "x"}", ItemKind.EPISODE, name = "Name ${season ?: "x"}${number ?: "x"}", sortName = if (number == 2) "A sort" else null,
                seriesId = "show", seasonId = "s${season ?: "x"}", indexNumber = number, parentIndexNumber = season))
        }
        add(item("tie-b", ItemKind.EPISODE, name = "Same", seriesId = "show", seasonId = "s1", indexNumber = 5, parentIndexNumber = 1))
        add(item("tie-a", ItemKind.EPISODE, name = "Same", seriesId = "show", seasonId = "s1", indexNumber = 5, parentIndexNumber = 1))
    }

    @Test fun storedEpisodesComeInExactlyTheOrderTheGatewayUses() = runBlocking {
        for (seed in 1..5) {
            val rows = episodes().map { row(it) }.shuffled(Random(seed))
            db.openHelper.writableDatabase.execSQL("DELETE FROM items")
            library.writeItems(s1u1, rows, t0, fetchStartedAtMs = 0)
            val expected = rows.inEpisodeOrder().map { it.item.id }
            assertEquals("seed $seed", expected, items.getEpisodes(S1, U1, id("show"), null).map { it.item.itemId })
        }
        // spelled out: numbered seasons, then no season number, then Specials (season 0) genuinely last
        val order = items.getEpisodes(S1, U1, id("show"), null).map { it.item.parentIndexNumber }.distinct()
        assertEquals(listOf(1, 2, 3, null, 0), order)
    }

    @Test fun oneSeasonKeepsTheSameOrder() = runBlocking {
        library.writeItems(s1u1, episodes().map { row(it) }.shuffled(Random(9)), t0, fetchStartedAtMs = 0)
        val expected = episodes().map { row(it) }.filter { it.item.seasonId == id("s1") }.inEpisodeOrder().map { it.item.id }
        assertEquals(expected, items.getEpisodes(S1, U1, id("show"), id("s1")).map { it.item.itemId })
    }

    // ---- the join: another user's data must never decide whether a row is returned

    @Test fun aListRowWithNoUserDataForThisUserIsStillReturned() = runBlocking {
        library.writePage(s1u2, "resume", 0, listOf(row(item("a"), watched(played = true))), 1, t0, fetchStartedAtMs = 0) // only bob has data
        library.writePage(s1u1, "resume", 0, listOf(row(item("a"))), 1, t0, fetchStartedAtMs = 0)
        val ann = db.listDao().getList(S1, U1, "resume", 10).single()
        assertNull(ann.userData)
        assertTrue(db.listDao().getList(S1, U2, "resume", 10).single().userData!!.played)
    }

    @Test fun anEpisodeWithNoUserDataForThisUserIsStillReturned() = runBlocking {
        library.writeItems(s1u2, listOf(row(episode("e1", 1, 1), watched(played = true))), t0, fetchStartedAtMs = 0)
        library.writeItems(s1u1, listOf(row(episode("e1", 1, 1)), row(episode("e2", 1, 2))), t0, fetchStartedAtMs = 0)
        val ann = items.getEpisodes(S1, U1, id("show"), null)
        assertEquals(2, ann.size)
        assertTrue(ann.all { it.userData == null })
        assertTrue(items.getEpisodes(S1, U2, id("show"), null).first { it.item.itemId == id("e1") }.userData!!.played)
    }

    @Test fun aSearchHitWithNoUserDataForThisUserIsStillReturned() = runBlocking {
        library.writeItems(s1u2, listOf(row(item("a", name = "Match"), watched(played = true))), t0, fetchStartedAtMs = 0)
        library.writeItems(s1u1, listOf(row(item("a", name = "Match"))), t0, fetchStartedAtMs = 0)
        assertNull(items.searchByName(S1, U1, "match", emptySet(), 5).single().userData)
        assertNull(items.getItem(S1, U1, id("a"))!!.userData)
    }
}
