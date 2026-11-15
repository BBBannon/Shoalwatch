package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.toModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/** Visibility is revocable: when the server refuses an item for one user, it disappears for that user and nobody else. */
class LibraryRepositoryRevocationTest : LibraryRepositoryTestBase() {
    private val x get() = id("x")
    private val stale = Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)

    /** Ann and Bob were both shown film X (in their Continue Watching lists), and a little time has passed. */
    private fun bothWereShownX() {
        runBlocking {
            library.writePage(s1u1, ListKey.RESUME, 0, listOf(row(item("x", name = "Film X")), row(item("y", name = "Film Y"))), 2, t0, fetchStartedAtMs = 0)
            library.writePage(s1u2, ListKey.RESUME, 0, listOf(row(item("x", name = "Film X")), row(item("y", name = "Film Y"))), 2, t0, fetchStartedAtMs = 0)
        }
        advance(Duration.ofMinutes(10))
    }

    private fun serverRefusesBobOnly(kind: AppError.Kind) {
        gateway.itemBySession = { s, _ -> if (s.userId == U2) throw AppError.of(kind) else row(item("x", name = "Film X")) }
    }

    private fun checkRefusal(kind: AppError.Kind) {
        bothWereShownX()
        serverRefusesBobOnly(kind)
        // Room reports the row's removal separately from the failure, so wait for both
        val bob = repo.item(s1u2, x).await { it.freshness is Freshness.Stale && it.item == null }
        assertNull("the item is gone for Bob", bob.item)
        assertEquals(stale, bob.freshness) // a refusal reads as "not found", whichever the kind
        gateway.failOps["search"] = AppError.Kind.OFFLINE
        runBlocking {
            assertEquals(emptyList<String>(), names(repo.search(s1u2, "film x").items))
            assertEquals(listOf("Film Y"), names(db.listDao().getList(S1, U2, ListKey.RESUME, 10).map { it.toModel() })) // gone from Bob's list, no gap
            assertEquals(listOf(id("y")), db.listDao().entryIds(S1, U2, ListKey.RESUME))
            assertEquals(1, db.listDao().getState(S1, U2, ListKey.RESUME)!!.loadedCount)
            // Ann is untouched, everywhere
            assertEquals(listOf("Film X"), names(repo.search(s1u1, "film x").items))
            assertEquals(listOf(id("x"), id("y")), db.listDao().entryIds(S1, U1, ListKey.RESUME))
            assertNotNull(db.itemDao().getItem(S1, U1, x))
            assertNotNull(db.itemDao().getItemEntity(S1, x)) // the shared row stays
        }
        assertEquals("Film X", repo.item(s1u1, x).await { it.item != null }.item!!.item.name)
    }

    @Test fun aForbiddenItemDisappearsForThatUserOnly() = checkRefusal(AppError.Kind.FORBIDDEN)

    @Test fun aMissingItemDisappearsForThatUserOnly() = checkRefusal(AppError.Kind.SERVER_NOT_FOUND)

    @Test fun otherFailuresDoNotRevokeAnything() {
        bothWereShownX()
        gateway.itemBySession = { _, _ -> throw AppError.of(AppError.Kind.SERVER_ERROR) }
        val bob = repo.item(s1u2, x).await { it.freshness is Freshness.Stale }
        assertEquals("Film X", bob.item!!.item.name)
        assertEquals(Freshness.Stale(AppError.Kind.SERVER_ERROR), bob.freshness)
    }

    @Test fun aLaterServerReturnMakesTheItemVisibleAgain() = runBlocking {
        bothWereShownX()
        serverRefusesBobOnly(AppError.Kind.FORBIDDEN)
        repo.item(s1u2, x).first5s { it.freshness is Freshness.Stale && it.item == null }
        assertNull(db.itemDao().getItem(S1, U2, x))
        gateway.itemBySession = { _, _ -> row(item("x", name = "Film X")) } // the restriction was lifted
        advance(Duration.ofMinutes(1))
        repo.refresh(s1u2, RefreshTarget.Item(x))
        assertEquals("Film X", repo.item(s1u2, x).first5s { it.item != null }.item!!.item.name)
    }

    @Test fun aRefusalForASeriesHidesItsHeaderForThatUser() {
        bothWereShownX()
        gateway.itemBySession = { s, _ -> if (s.userId == U2) throw AppError.of(AppError.Kind.FORBIDDEN) else row(item("x")) }
        val bob = repo.series(s1u2, x).await { it.freshness is Freshness.Stale && it.series == null }
        assertNull(bob.series)
    }
}
