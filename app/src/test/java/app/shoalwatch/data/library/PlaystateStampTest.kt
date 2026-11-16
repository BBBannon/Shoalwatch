package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the "asked for at" stamp is read, and what a stale answer does around removals and rollbacks. */
class PlaystateStampTest : PlaystateTestBase() {
    private val show = id("show")

    /** Makes a local change to "a" in the middle of the named request (after it has been issued), so an answer that carries the old value must be dropped. */
    private fun changeDuring(prefix: String) {
        gateway.onCall = { call -> if (call.startsWith(prefix)) repo.store.setFavouritePending(s1u1, id("a"), true, now) }
    }

    private fun stale() = film("a", ud(favourite = false))

    private fun assertDropped(case: String) {
        assertTrue("$case: the answer was applied over a change made after the request was issued", userData("a")!!.favourite)
    }

    @Test fun everyRecipeReadsItsStampBeforeItsGatewayCall() = runBlocking {
        // nextUp
        save(stale()); gateway.nextUpFor = { listOf(stale()) }; changeDuring("nextUp")
        repo.refresh(s1u1, RefreshTarget.Home); assertDropped("nextUp")

        // latest:<view>
        sql("DELETE FROM user_data"); save(stale()); gateway.nextUpFor = { emptyList() }
        gateway.viewsFor = { listOf(view("lib")) }; gateway.latestFor = { _, _ -> listOf(stale()) }; changeDuring("latest")
        repo.refresh(s1u1, RefreshTarget.Home); assertDropped("latest")
        gateway.viewsFor = { emptyList() }

        // seasons: and episodes:
        sql("DELETE FROM user_data"); save(stale())
        gateway.itemFor = { row(item("show", ItemKind.SERIES, "Show"), ud()) }
        gateway.seasonsFor = { listOf(row(item("s1", ItemKind.SEASON, "S1", seriesId = "show", indexNumber = 1), ud()), stale()) }
        changeDuring("seasons")
        repo.refresh(s1u1, RefreshTarget.Series(show, id("s1"))); assertDropped("seasons")
        sql("DELETE FROM user_data"); save(stale())
        gateway.episodesFor = { _, _ -> listOf(stale()) }; changeDuring("episodes")
        repo.refresh(s1u1, RefreshTarget.Series(show, id("s1"))); assertDropped("episodes")

        // children, page 0
        sql("DELETE FROM user_data"); save(stale())
        gateway.childPage = { _, start, _ -> Page(listOf(stale()), start, 1, start + 1, true) }; changeDuring("children:0")
        repo.refresh(s1u1, RefreshTarget.Children(id("lib"), ListShape.DIRECT, nameAsc, noFilter)); assertDropped("children page 0")

        // loadNextPage
        sql("DELETE FROM user_data")
        gateway.childPage = { _, start, _ -> if (start == 0) Page(listOf(film("x")), 0, 2, 1, false) else Page(listOf(stale()), start, 2, 2, true) }
        gateway.onCall = {}
        repo.refresh(s1u1, RefreshTarget.Children(id("lib"), ListShape.DIRECT, nameAsc, noFilter))
        save(stale())
        changeDuring("children:1")
        repo.loadMore(s1u1, id("lib"), ListShape.DIRECT, nameAsc, noFilter); assertDropped("loadNextPage")
    }

    @Test fun anItemMarkedWatchedThatWasNotInTheSavedContinueWatchingStaysOutOfAStaleOne() = runBlocking {
        save(film("a"))
        saveResume(film("b"))
        gateway.answerAtRequest = true
        gateway.resumeFor = { listOf(film("a"), film("b")) } // asked for before the action; the server still had it
        var requests = 0
        val first = latch()
        val followUp = latch() // keeps the refresh that follows the action from healing the list before it is checked
        gateway.onCall = { call -> if (call.startsWith("resume")) { if (requests++ == 0) first.await() else followUp.await() } }
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) }
        until { gateway.count("resume") == 1 }
        gateway.resumeFor = { listOf(film("b")) }
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("a"), true) }
        first.release()
        withTimeout(5_000) { refreshing.await() }
        assertEquals("the stale page cannot put it into Continue Watching", ids("b"), resumeIds())
        followUp.release()
        settle()
        assertEquals(ids("b"), resumeIds())
    }

    /** The old `PlaystateRestoreTest` intent: a change the server refuses does not stay on screen; the refresh after it decides. */
    @Test fun aRefusedChangeIsReplacedByTheServersCopyByTheRefreshAfterTheOverruling() = runBlocking {
        saveResume(film("a"), film("b"))
        val held = latch()
        val l = latch()
        gateway.onCall = { call -> if (call.startsWith("clearResume")) held.await() else if (call.startsWith("resume")) l.await() }
        gateway.failOps["clearResume"] = app.shoalwatch.core.AppError.Kind.FORBIDDEN
        playstate.removeFromContinueWatching(s1u1, id("a"))
        until { gateway.count("clearResume") == 1 }
        gateway.answerAtRequest = true
        gateway.resumeFor = { listOf(film("a", ud(positionMs = 99_000)), film("b")) }
        gateway.itemFor = { film("a", ud(positionMs = 99_000)) }
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) } // asked for while the change was waiting
        until { gateway.count("resume") == 1 }
        assertEquals("the waiting change stands against it", 0, userData("a")!!.positionMs)
        held.release() // refused: overruled
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertEquals(0, queued())
        assertEquals("the server's copy, from the refresh asked for after the overruling", 99_000, userData("a")!!.positionMs)
        assertEquals(ids("a", "b"), resumeIds())
        assertFalse(userData("a")!!.pendingSyncAt != null)
    }
}
