package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Milestone 10: an item this profile deleted from the server leaves its lists at once; the record stays only for a kept download. */
class LibraryRepositoryForgetDeletedTest : LibraryRepositoryTestBase() {
    private val x get() = id("x")

    private fun bothWereShownX() = runBlocking {
        library.writePage(s1u1, ListKey.RESUME, 0, listOf(row(item("x", name = "Film X")), row(item("y", name = "Film Y"))), 2, t0, requestedAtSeq = 0)
        library.writePage(s1u2, ListKey.RESUME, 0, listOf(row(item("x", name = "Film X")), row(item("y", name = "Film Y"))), 2, t0, requestedAtSeq = 0)
    }

    @Test fun deletedItLeavesTheListsAndTheRecordForThatProfileOnly() = runBlocking {
        bothWereShownX()
        repo.forgetDeleted(s1u1, x, keepRecord = false)
        assertEquals(listOf(id("y")), db.listDao().entryIds(S1, U1, ListKey.RESUME))
        assertEquals(1, db.listDao().getState(S1, U1, ListKey.RESUME)!!.loadedCount)
        assertNull(db.itemDao().getItem(S1, U1, x))
        assertEquals("another profile is untouched", listOf(x, id("y")), db.listDao().entryIds(S1, U2, ListKey.RESUME))
        assertNotNull(db.itemDao().getItem(S1, U2, x))
    }

    @Test fun withTheDownloadKeptTheRecordStays() = runBlocking {
        bothWereShownX()
        repo.forgetDeleted(s1u1, x, keepRecord = true)
        assertEquals(listOf(id("y")), db.listDao().entryIds(S1, U1, ListKey.RESUME))
        assertNotNull("Downloads still shows it", db.itemDao().getItem(S1, U1, x))
    }

    @Test fun aMalformedIdDoesNothing() = runBlocking {
        bothWereShownX()
        repo.forgetDeleted(s1u1, "../x", keepRecord = false)
        assertEquals(listOf(x, id("y")), db.listDao().entryIds(S1, U1, ListKey.RESUME))
    }
}
