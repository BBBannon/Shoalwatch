package app.shoalwatch.data.library

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A malformed id (a navigation argument, saved state) must never make the seam throw: it reads as "not on the server". */
class LibraryRepositoryInvalidIdTest : LibraryRepositoryTestBase() {
    private val bad = listOf("not-an-id", "", "a:b:c", "x".repeat(300), "../../etc")
    private val notFound = Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)

    private fun assertNothingHappened() {
        assertEquals(emptyList<String>(), gateway.calls.toList())
        assertEquals(0, count("list_state"))
        assertEquals(0, count("list_entries"))
        assertEquals(0, count("items"))
    }

    @Test fun aListingWithAMalformedParentIsEmptyAndStaleWithNoRequest() = runBlocking {
        for (id in bad) {
            repo.children(s1u1, id, ListShape.DIRECT, nameAsc, noFilter).test {
                assertEquals(Listing(emptyList(), null, notFound, false), awaitItem())
                awaitComplete()
            }
        }
        assertNothingHappened()
    }

    @Test fun aSeriesWithAMalformedSeriesOrSeasonIdIsEmptyAndStaleWithNoRequest() = runBlocking {
        for (id in bad) {
            repo.series(s1u1, id).test {
                assertEquals(SeriesDetail(null, emptyList(), emptyList(), null, notFound), awaitItem())
                awaitComplete()
            }
            repo.series(s1u1, this@LibraryRepositoryInvalidIdTest.id("show"), id).test {
                assertEquals(SeriesDetail(null, emptyList(), emptyList(), null, notFound), awaitItem())
                awaitComplete()
            }
        }
        assertNothingHappened()
    }

    @Test fun anItemWithAMalformedIdIsEmptyAndStaleWithNoRequest() = runBlocking {
        for (id in bad) {
            repo.item(s1u1, id).test {
                assertEquals(ItemDetail(null, notFound), awaitItem())
                awaitComplete()
            }
        }
        assertNothingHappened()
    }

    @Test fun loadMoreAndRefreshWithMalformedIdsDoNothing() = runBlocking {
        for (id in bad) {
            repo.loadMore(s1u1, id, ListShape.DIRECT, nameAsc, noFilter)
            repo.refresh(s1u1, RefreshTarget.Children(id, ListShape.DIRECT, nameAsc, noFilter))
            repo.refresh(s1u1, RefreshTarget.Series(id))
            repo.refresh(s1u1, RefreshTarget.Series(this@LibraryRepositoryInvalidIdTest.id("show"), id))
            repo.refresh(s1u1, RefreshTarget.Item(id))
        }
        assertNothingHappened()
    }

    @Test fun aGoodIdStillWorksAfterBadOnes() {
        gateway.itemFor = { row(item("a")) }
        assertNull(repo.item(s1u1, "nope").await().item)
        assertEquals("Film a", repo.item(s1u1, id("a")).await { it.item != null }.item!!.item.name)
    }
}
