package app.shoalwatch.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.PageResult
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The library cache's SQL on the SQLite that ships with the device or emulator running the test (Android 10 has 3.22; the
 * unit tests run on a newer one, so they cannot prove this). Exercises every statement shape the cache uses.
 */
@RunWith(AndroidJUnit4::class)
class LibraryCacheOnDeviceTest {
    private lateinit var db: AppDatabase
    private lateinit var store: LibraryStore
    private val server = "11111111111111111111111111111111"
    private val user = "22222222222222222222222222222222"
    private val session = Session(server, user)
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    @Before fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addMigrations(Migration1To2).build()
        store = LibraryStore(db)
        db.serverDao().upsert(ServerEntity(server, "Den", "10.10.3", null, null, false, null, null, ByteArray(1)))
        db.accountDao().upsert(AccountEntity(server, user, "ann", ByteArray(1)))
    }

    @After fun close() = db.close()

    private fun item(label: String, name: String = "Film $label", overview: String? = null, kind: ItemKind = ItemKind.MOVIE) = Item(
        id = label.padEnd(32, '0'), kind = kind, name = name, sortName = null, parentId = null, seriesId = null, seriesName = null,
        seasonId = null, indexNumber = null, parentIndexNumber = null, overview = overview, runtimeMs = 1_000, year = 2020,
        premiereDate = null, communityRating = null, officialRating = null, childCount = null, images = ItemImages(null, null, null, null),
    )

    private fun id(label: String) = label.padEnd(32, '0')

    @Test fun userDataSettersWorkOnAMissingAndAnExistingRow() = runBlocking {
        val items = db.itemDao()
        val now = t0.toEpochMilli()
        items.setPlayed(server, user, id("a"), true, 0, now, pending = true) // missing row
        items.setPlayed(server, user, id("a"), false, 5_000, now + 1) // existing row
        items.setFavourite(server, user, id("b"), true, now) // missing
        items.setFavourite(server, user, id("b"), false, now + 1) // existing
        items.setPosition(server, user, id("c"), 9_000, now) // missing
        items.setPosition(server, user, id("c"), 0, now + 1) // existing
        val a = items.getUserData(server, user, id("a"))!!
        assertFalse(a.played)
        assertEquals(5_000L, a.positionMs)
        assertEquals("pending is kept by a later setter without it", now, a.pendingSyncAt)
        assertFalse(items.getUserData(server, user, id("b"))!!.favourite)
        assertEquals(0L, items.getUserData(server, user, id("c"))!!.positionMs)
    }

    @Test fun aPageWritesAndReadsBackThroughTheJoin() = runBlocking {
        val rows = listOf("a", "b", "c").map { ItemWithUserData(item(it), UserData(it == "a", 0, null, 0, false, null, null)) }
        assertEquals(PageResult.Written, store.writePage(session, "resume", 0, rows, 3, t0))
        val read = db.listDao().getList(server, user, "resume", 10)
        assertEquals(listOf("Film a", "Film b", "Film c"), read.map { it.item.name })
        assertTrue(read.first().userData!!.played)
        assertEquals(PageResult.Stale, store.writePage(session, "resume", 9, rows, 3, t0))
    }

    @Test fun upsertTwiceKeepsPinnedAndOverviewAndAuthoritativeClearsIt() = runBlocking {
        store.writeItems(session, listOf(ItemWithUserData(item("a", overview = "Plot"), null)), t0)
        db.openHelper.writableDatabase.execSQL("UPDATE items SET pinned = 1")
        store.writeItems(session, listOf(ItemWithUserData(item("a", name = "Again", overview = null), null)), t0.plusSeconds(1))
        val kept = db.itemDao().getItemEntity(server, id("a"))!!
        assertEquals("Plot", kept.overview)
        assertTrue(kept.pinned)
        assertEquals("Again", kept.name)
        store.writeItems(session, listOf(ItemWithUserData(item("a", overview = null), null)), t0.plusSeconds(2), authoritative = true)
        assertNull(db.itemDao().getItemEntity(server, id("a"))!!.overview)
    }

    @Test fun aServerRefreshDoesNotOverwriteAPendingRow() = runBlocking {
        db.itemDao().setPlayed(server, user, id("a"), true, 0, t0.toEpochMilli(), pending = true)
        store.writeItems(session, listOf(ItemWithUserData(item("a"), UserData(false, 800, null, 0, false, null, null))), t0.plusSeconds(5))
        assertTrue(db.itemDao().getUserData(server, user, id("a"))!!.played)
        store.clearPending(session, id("a"))
        store.writeItems(session, listOf(ItemWithUserData(item("a"), UserData(false, 800, null, 0, false, null, null))), t0.plusSeconds(6))
        assertFalse(db.itemDao().getUserData(server, user, id("a"))!!.played)
    }

    @Test fun searchFindsAccentedNamesAndEscapesWildcards() = runBlocking {
        store.writeItems(session, listOf(ItemWithUserData(item("a", name = "Élite 100%"), null), ItemWithUserData(item("b", name = "Elite 100x"), null)), t0)
        assertEquals(listOf("Élite 100%"), db.itemDao().searchByName(server, user, "ELITE 100%", emptySet(), 10).map { it.item.name })
    }

    @Test fun removeAndReinsertAnEntryThenEvict() = runBlocking {
        val rows = listOf("a", "b", "c").map { ItemWithUserData(item(it), null) }
        store.writePage(session, "resume", 0, rows, 3, t0)
        assertTrue(store.removeEntry(session, "resume", id("b")))
        assertEquals(listOf(id("a"), id("c")), db.listDao().entryIds(server, user, "resume"))
        assertTrue(store.insertEntryAt(session, "resume", 1, id("b")))
        assertEquals(listOf(id("a"), id("b"), id("c")), db.listDao().entryIds(server, user, "resume"))
        db.itemDao().setFavourite(server, user, id("gone"), false, t0.toEpochMilli())
        val counts = store.evict(t0.plus(Duration.ofDays(90)))
        assertEquals("the stale list, its items and the boring orphan user data", 3, counts.listEntries)
        assertEquals(3, counts.items)
        assertEquals(1, counts.userData)
        assertNotNull(db.serverDao().get(server))
    }

    @Test fun anItemOnlyOneUserWasShownIsAbsentForTheOther() = runBlocking {
        val other = "33333333333333333333333333333333"
        db.accountDao().upsert(AccountEntity(server, other, "bob", ByteArray(1)))
        store.writePage(session, "resume", 0, listOf(ItemWithUserData(item("a"), null)), 1, t0)
        assertNotNull(db.itemDao().getItem(server, user, id("a")))
        assertNull(db.itemDao().getItem(server, other, id("a")))
        assertTrue(db.itemDao().searchByName(server, other, "film", emptySet(), 10).isEmpty())
        store.writeItems(Session(server, other), listOf(ItemWithUserData(item("a"), null)), t0)
        assertNotNull(db.itemDao().getItem(server, other, id("a")))
    }

    @Test fun revokingAnItemHidesItForThatUserOnlyAndOldUnconfirmedRecordsExpire() = runBlocking {
        val other = "33333333333333333333333333333333"
        db.accountDao().upsert(AccountEntity(server, other, "bob", ByteArray(1)))
        val bob = Session(server, other)
        store.writePage(session, "resume", 0, listOf("a", "b").map { ItemWithUserData(item(it), null) }, 2, t0)
        store.writePage(bob, "resume", 0, listOf("a", "b").map { ItemWithUserData(item(it), null) }, 2, t0)
        // the server refused "a" for Bob: gone from his reads and his list, untouched for the first user
        store.revokeVisibility(bob, id("a"))
        assertNull(db.itemDao().getItem(server, other, id("a")))
        assertEquals(listOf(id("b")), db.listDao().entryIds(server, other, "resume"))
        assertNotNull(db.itemDao().getItem(server, user, id("a")))
        assertEquals(listOf(id("a"), id("b")), db.listDao().entryIds(server, user, "resume"))
        // a record the server has not re-confirmed for 31 days goes, unless the item is in that user's current lists
        db.openHelper.writableDatabase.execSQL("UPDATE visible_items SET seenAt = ?", arrayOf<Any>(t0.minus(Duration.ofDays(31)).toEpochMilli()))
        store.writeItems(bob, listOf(ItemWithUserData(item("a"), null)), t0) // keeps the shared row recent; makes Bob's record fresh again
        store.evict(t0)
        assertNotNull("in the user's current list", db.itemDao().getItem(server, user, id("a")))
        assertNotNull("re-confirmed", db.itemDao().getItem(server, other, id("a")))
    }
}
