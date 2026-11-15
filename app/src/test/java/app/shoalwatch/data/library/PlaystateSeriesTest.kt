package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Episodes adjust the badges above them; a season or a series marks every episode and season already saved under it. */
class PlaystateSeriesTest : PlaystateTestBase() {
    private fun played(label: String, value: Boolean, s: app.shoalwatch.data.servers.Session = s1u1) = runBlocking { playstate.setPlayed(s, id(label), value) }

    private fun unplayed(label: String, s: app.shoalwatch.data.servers.Session = s1u1) = userData(label, s)!!.unplayedItemCount

    // ---- one episode moves the badges

    @Test fun markingAnEpisodeWatchedLowersTheSeasonAndSeriesBadgesByOne() {
        save(series(), season("s1", 1), ep("e1", "s1", 1))
        played("e1", true)
        assertEquals(2, unplayed("s1"))
        assertEquals(9, unplayed("show"))
        assertTrue(userData("e1")!!.played)
        assertNoPending()
    }

    @Test fun markingItUnwatchedRaisesThemAgain() {
        save(series(), season("s1", 1), ep("e1", "s1", 1, ud(played = true)))
        played("e1", false)
        assertEquals(4, unplayed("s1"))
        assertEquals(11, unplayed("show"))
    }

    @Test fun aBadgeNeverGoesBelowZero() {
        save(series(data = ud(unplayed = 0)), season("s1", 1, ud(unplayed = 0)), ep("e1", "s1", 1))
        played("e1", true)
        assertEquals(0, unplayed("s1"))
        assertEquals(0, unplayed("show"))
    }

    @Test fun anEpisodeAlreadyInTheStateAskedForChangesNoBadge() {
        save(series(), season("s1", 1), ep("e1", "s1", 1, ud(played = true)))
        played("e1", true)
        assertEquals(3, unplayed("s1"))
        assertEquals(10, unplayed("show"))
    }

    @Test fun aSeasonOrSeriesWithNoCountOrNoRowIsLeftAlone() {
        save(series(data = null), season("s1", 1, ud(unplayed = null)), ep("e1", "s1", 1))
        played("e1", true)
        assertNull(userData("show"))
        assertNull(unplayed("s1"))
    }

    @Test fun badgesAreVisibleAtOnceAndRolledBackOnFailure() = runBlocking {
        save(series(), season("s1", 1), ep("e1", "s1", 1))
        val seasonBefore = userData("s1")
        val seriesBefore = userData("show")
        val held = holdServer()
        gateway.failAll = AppError.Kind.TIMEOUT
        val call = background { runCatching { playstate.setPlayed(s1u1, id("e1"), true) } }
        until { gateway.count("setPlayed") == 1 }
        assertEquals(2, unplayed("s1"))
        assertTrue(userData("s1")!!.pendingSyncAt != null)
        release(held)
        assertEquals(AppError.Kind.TIMEOUT, (call.await().exceptionOrNull() as AppError).kind)
        // the badge moves back by what it was moved by; `updatedAt` is the time of that last write, so it is not compared
        assertEquals(seasonBefore!!.unplayedItemCount, userData("s1")!!.unplayedItemCount)
        assertEquals(seriesBefore!!.unplayedItemCount, userData("show")!!.unplayedItemCount)
        assertNoPending()
    }

    @Test fun anotherUsersBadgesAreUntouched() {
        save(series(), season("s1", 1), ep("e1", "s1", 1))
        save(series(), season("s1", 1), ep("e1", "s1", 1), s = s1u2)
        played("e1", true)
        assertEquals(3, unplayed("s1", s1u2))
        assertEquals(10, unplayed("show", s1u2))
        assertFalse(userData("e1", s1u2)!!.played)
    }

    // ---- a season

    private fun savedShow() {
        save(series(), season("s1", 1), season("s2", 2), ep("a", "s1", 1), ep("b", "s1", 2, ud(positionMs = 9_000)), ep("c", "s1", 3), ep("d", "s2", 1))
        saveList(ListKey.seasons(id("show")), season("s1", 1), season("s2", 2))
        saveResume(row(item("film"), ud(positionMs = 5)), ep("b", "s1", 2, ud(positionMs = 9_000)), ep("d", "s2", 1, ud(positionMs = 1_000)))
    }

    @Test fun markingASeasonWatchedMarksItsSavedEpisodesAndNoOthers() {
        savedShow()
        played("s1", true)
        assertTrue(userData("s1")!!.played)
        assertEquals(0, unplayed("s1"))
        for (e in listOf("a", "b", "c")) assertTrue("episode $e", userData(e)!!.played)
        assertEquals(0, userData("b")!!.positionMs)
        assertFalse("another season's episode", userData("d")!!.played)
        assertFalse(userData("s2")!!.played)
        assertEquals(3, unplayed("s2"))
        assertEquals(10, unplayed("show"))
        assertEquals(listOf("setPlayed:${id("s1")}:true"), gateway.calls.filter { it.startsWith("setPlayed") })
        assertNoPending()
    }

    @Test fun theSeasonsEpisodesLeaveContinueWatchingInOneStep() {
        savedShow()
        gateway.failOps["resume"] = AppError.Kind.OFFLINE // keep the follow-up refresh from rewriting the list
        played("s1", true)
        drain()
        assertEquals(ids("film", "d"), resumeIds())
    }

    @Test fun anEpisodeThatIsNotSavedGetsNoRowCreated() {
        save(series(), season("s1", 1))
        val rowsBefore = count("user_data")
        played("s1", true)
        assertEquals(rowsBefore, count("user_data"))
    }

    @Test fun afterASeasonIsMarkedTheSeasonAndItsEpisodesAreRefreshed() {
        savedShow()
        played("s1", true)
        drain()
        assertEquals(1, gateway.count("seasons"))
        assertEquals(1, gateway.calls.count { it == "episodes:${id("s1")}" })
        assertEquals(0, gateway.calls.count { it == "episodes:${id("s2")}" })
        assertEquals("the series badge", 1, gateway.calls.count { it == "item:${id("show")}" })
        assertEquals(1, gateway.count("resume"))
        assertEquals(1, gateway.count("nextUp"))
    }

    @Test fun unmarkingASeasonLeavesItsBadgeUnknownRatherThanGuessed() {
        savedShow()
        played("s1", true)
        played("s1", false)
        assertFalse(userData("s1")!!.played)
        assertNull(unplayed("s1"))
        for (e in listOf("a", "b", "c")) assertFalse("episode $e", userData(e)!!.played)
        assertEquals(0, userData("a")!!.playCount)
    }

    @Test fun aFailedSeasonMarkRestoresEveryRowAndListEntry() {
        savedShow()
        val names = listOf("s1", "a", "b", "c", "d", "s2", "show", "film")
        val before = names.associateWith { userData(it) }
        val listBefore = resumeIds()
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(AppError.Kind.OFFLINE, failure { playstate.setPlayed(s1u1, id("s1"), true) }.kind)
        names.forEach { assertEquals("row $it", before[it], userData(it)) }
        assertEquals(ids("film", "b", "d"), resumeIds()) // b (season 1) sat before d (season 2): their order is part of the undo
        assertEquals(listBefore, resumeIds())
        assertEquals(3, unplayed("s1"))
        assertEquals(10, unplayed("show"))
        assertNoPending()
    }

    @Test fun anotherUsersEpisodesAreUntouchedBySeasonMark() {
        savedShow()
        save(series(), season("s1", 1), ep("a", "s1", 1), s = s1u2)
        played("s1", true)
        assertFalse(userData("a", s1u2)!!.played)
        assertFalse(userData("s1", s1u2)!!.played)
    }

    @Test fun anEpisodeTheUserWasNeverShownIsNotTouched() {
        save(series(), season("s1", 1), ep("a", "s1", 1))
        save(ep("hidden", "s1", 2), s = s1u2) // shown to the other user only
        played("s1", true)
        assertNull(userData("hidden", s1u1))
        assertFalse(userData("hidden", s1u2)!!.played)
    }

    // ---- a series

    @Test fun markingASeriesWatchedMarksItsSavedSeasonsAndEpisodes() {
        savedShow()
        played("show", true)
        assertTrue(userData("show")!!.played)
        assertEquals(0, unplayed("show"))
        for (x in listOf("s1", "s2")) { assertTrue(userData(x)!!.played); assertEquals(0, unplayed(x)) }
        for (x in listOf("a", "b", "c", "d")) assertTrue("episode $x", userData(x)!!.played)
        assertEquals(1, gateway.calls.count { it.startsWith("setPlayed") })
        assertNoPending()
    }

    @Test fun afterASeriesIsMarkedItsSeasonsAndEpisodesAreRefreshed() {
        savedShow()
        played("show", true)
        drain()
        assertEquals(1, gateway.count("item"))
        assertEquals(1, gateway.count("seasons"))
        assertEquals(1, gateway.calls.count { it == "episodes:${id("s1")}" })
        assertEquals(1, gateway.calls.count { it == "episodes:${id("s2")}" })
        assertEquals(1, gateway.count("resume"))
        assertEquals(1, gateway.count("nextUp"))
    }

    @Test fun unmarkingASeriesLeavesTheBadgesUnknown() {
        savedShow()
        played("show", false)
        assertNull(unplayed("show"))
        assertNull(unplayed("s1"))
        assertNull(unplayed("s2"))
    }

    @Test fun aFailedSeriesMarkRestoresEverything() {
        savedShow()
        val names = listOf("show", "s1", "s2", "a", "b", "c", "d", "film")
        val before = names.associateWith { userData(it) }
        val listBefore = resumeIds()
        gateway.failAll = AppError.Kind.UNAUTHORISED
        assertEquals(AppError.Kind.UNAUTHORISED, failure { playstate.setPlayed(s1u1, id("show"), true) }.kind)
        names.forEach { assertEquals("row $it", before[it], userData(it)) }
        assertEquals(ids("film", "b", "d"), resumeIds())
        assertEquals(listBefore, resumeIds())
        assertEquals(3, unplayed("s1"))
        assertEquals(3, unplayed("s2"))
        assertEquals(10, unplayed("show"))
        assertNoPending()
    }
}
