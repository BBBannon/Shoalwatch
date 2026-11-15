package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.library.ItemKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/**
 * SAFEGUARDING: item rows are shared per server, but a user is only ever shown an item the SERVER showed that user. An item
 * the user was not shown is treated exactly as absent, by every read that could reach a shared row.
 */
class LibraryVisibilityTest : LibraryTestBase() {
    private val items get() = db.itemDao()
    private val lists get() = db.listDao()

    private fun annCaches(vararg labels: String) = runBlocking { library.writePage(s1u1, "resume", 0, labels.map { row(item(it, name = "Secret $it")) }, labels.size, t0) }

    @Test fun anItemOnlyAnnWasShownIsAbsentForBobEverywhere() = runBlocking {
        annCaches("x")
        assertNotNull(items.getItem(S1, U1, id("x")))
        assertNull(items.getItem(S1, U2, id("x")))
        assertNull(items.observeItem(S1, U2, id("x")).first())
        assertEquals(emptyList<String>(), items.searchByName(S1, U2, "secret", emptySet(), 10).map { it.item.name })
        assertEquals(listOf("Secret x"), items.searchByName(S1, U1, "secret", emptySet(), 10).map { it.item.name })
    }

    @Test fun aListEntryCanNeverRevealAnItemTheUserWasNotShown() = runBlocking {
        annCaches("x")
        sql("INSERT INTO list_entries (serverId, userId, listKey, position, itemId) VALUES (?, ?, 'resume', 0, ?)", S1, U2, id("x"))
        assertEquals(emptyList<String>(), lists.getList(S1, U2, "resume", 10).map { it.item.name })
        assertEquals(emptyList<String>(), lists.observeList(S1, U2, "resume", 10).first().map { it.item.name })
        assertEquals(listOf("Secret x"), lists.getList(S1, U1, "resume", 10).map { it.item.name })
    }

    @Test fun episodesAreFilteredToo() = runBlocking {
        library.writeItems(s1u1, listOf(row(episode("e1", 1, 1)), row(episode("e2", 1, 2))), t0)
        library.writeItems(s1u2, listOf(row(episode("e2", 1, 2))), t0)
        assertEquals(listOf(id("e1"), id("e2")), items.getEpisodes(S1, U1, id("show"), null).map { it.item.itemId })
        assertEquals(listOf(id("e2")), items.getEpisodes(S1, U2, id("show"), null).map { it.item.itemId })
        assertEquals(listOf(id("e2")), items.observeEpisodes(S1, U2, id("show"), null).first().map { it.item.itemId })
    }

    @Test fun onceTheServerReturnsTheItemToBobHeSeesIt() = runBlocking {
        annCaches("x", "y", "z")
        library.writePage(s1u2, "kids", 0, listOf(row(item("x", name = "Secret x"))), 1, t0) // a list page
        library.writeItems(s1u2, listOf(row(item("y", name = "Secret y"))), t0) // a search result or detail fetch
        assertEquals("Secret x", items.getItem(S1, U2, id("x"))!!.item.name)
        assertEquals("Secret y", items.getItem(S1, U2, id("y"))!!.item.name)
        assertNull(items.getItem(S1, U2, id("z")))
    }

    @Test fun aWriteThatIsNotAppliedRecordsNothing() = runBlocking {
        annCaches("x")
        library.writePage(s1u1, "paged", 0, listOf(row(item("p"))), 5, t0, nextStartIndex = 1, endReached = false)
        val stale = library.writePage(s1u2, "paged", 3, listOf(row(item("q"))), 5, t0) // beyond the end: not written
        assertEquals(app.shoalwatch.data.db.library.PageResult.Stale, stale)
        assertEquals(0, count("visible_items", "itemId = '${id("q")}'"))
    }

    @Test fun bobsVisibilityDoesNotChangeAnnsAndRemovingBobKeepsAnns() = runBlocking {
        annCaches("x")
        library.writeItems(s1u2, listOf(row(item("x", name = "Secret x"))), t0)
        servers.removeAccount(S1, U2)
        assertEquals(0, count("visible_items", "userId = '$U2'"))
        assertNotNull(items.getItem(S1, U1, id("x")))
    }

    @Test fun signingInAgainKeepsTheRecord() = runBlocking {
        annCaches("x")
        servers.invalidateToken(S1, U1)
        assertEquals(1, count("visible_items", "userId = '$U1'"))
    }

    @Test fun evictionDeletesTheRecordOfEvictedItemsButKeepsPinnedOnes() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("gone")), row(item("pinned"))), t0)
        sql("UPDATE items SET pinned = 1 WHERE itemId = ?", id("pinned"))
        library.evict(t0.plus(Duration.ofDays(100)))
        assertEquals(0, count("items", "itemId = '${id("gone")}'"))
        assertEquals(0, count("visible_items", "itemId = '${id("gone")}'"))
        assertEquals(1, count("visible_items", "itemId = '${id("pinned")}'"))
    }

    @Test fun theRecordIsPerServer() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("x"))), t0)
        library.writeItems(s2u1, listOf(row(item("y"))), t0)
        assertNull(items.getItem(app.shoalwatch.S2, U1, id("x")))
        assertNotNull(items.getItem(S1, U1, id("x")))
    }

    @Test fun anEpisodeShownToBobDoesNotShowItsSeries() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("show", ItemKind.SERIES)), row(episode("e1", 1, 1))), t0)
        library.writeItems(s1u2, listOf(row(episode("e1", 1, 1))), t0) // the server returned only the episode
        assertNull(items.getItem(S1, U2, id("show")))
        assertNotNull(items.getItem(S1, U2, id("e1")))
    }

    // ---- unconfirmed visibility expires (30 days)

    private val day = Duration.ofDays(1)

    /** Item X is shown to Ann and Bob; only Ann's record is made [age] old (Bob's item write keeps the row itself fresh). */
    private fun annSeenAgo(age: Duration, label: String = "x") = runBlocking {
        library.writeItems(s1u1, listOf(row(item(label))), t0)
        library.writeItems(s1u2, listOf(row(item(label))), t0)
        sql("UPDATE visible_items SET seenAt = ? WHERE userId = ? AND itemId = ?", t0.minus(age).toEpochMilli(), U1, id(label))
    }

    @Test fun aVisibilityRecordOlderThanThirtyDaysThatIsNeitherPinnedNorListedIsDropped() = runBlocking {
        annSeenAgo(day.multipliedBy(31))
        library.evict(t0)
        assertNull(items.getItem(S1, U1, id("x")))
        assertNotNull(items.getItem(S1, U2, id("x"))) // Bob's is fresh; the shared row stays for him
        assertEquals(1, count("items", "itemId = '${id("x")}'"))
    }

    @Test fun anOldRecordForAnItemInTheUsersCurrentListIsKept() = runBlocking {
        library.writePage(s1u1, "resume", 0, listOf(row(item("x"))), 1, t0)
        sql("UPDATE visible_items SET seenAt = ? WHERE userId = ?", t0.minus(day.multipliedBy(31)).toEpochMilli(), U1)
        library.evict(t0)
        assertNotNull(items.getItem(S1, U1, id("x")))
    }

    @Test fun anOldRecordForAListedItemOfAnotherUserDoesNotProtectIt() = runBlocking {
        library.writePage(s1u2, "resume", 0, listOf(row(item("x"))), 1, t0) // Bob's list names X
        library.writeItems(s1u1, listOf(row(item("x"))), t0)
        sql("UPDATE visible_items SET seenAt = ? WHERE userId = ?", t0.minus(day.multipliedBy(31)).toEpochMilli(), U1)
        library.evict(t0)
        assertNull(items.getItem(S1, U1, id("x")))
        assertNotNull(items.getItem(S1, U2, id("x")))
    }

    @Test fun anOldRecordForAPinnedItemIsKept() = runBlocking {
        annSeenAgo(day.multipliedBy(60))
        sql("UPDATE items SET pinned = 1 WHERE itemId = ?", id("x"))
        library.evict(t0)
        assertNotNull(items.getItem(S1, U1, id("x")))
    }

    @Test fun aTwentyNineDayOldRecordIsKept() = runBlocking {
        annSeenAgo(day.multipliedBy(29))
        library.evict(t0)
        assertNotNull(items.getItem(S1, U1, id("x")))
    }
}
