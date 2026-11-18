package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens on this phone when a row is confirmed or overruled (`PlaystateLanding`), and the refresh keys a row carries. */
class PlaystateLandingTest : PlaystateTestBase() {
    @Test fun confirmingClearsOnlyMarkersNoOtherRowCoversAndRefreshesTheRowsLists() = runBlocking {
        saveResume(film("a"))
        gateway.failOps["setPlayed"] = AppError.Kind.OFFLINE
        playstate.setFavourite(s1u1, id("a"), true) // sent and confirmed
        playstate.setPlayed(s1u1, id("a"), true) // waits: it still covers the row
        settle()
        assertEquals(1, queued())
        assertNotNull("the played row still covers it", userData("a")!!.pendingSyncAt)
        assertEquals("a favourite refreshes no list", 0, gateway.count("resume"))
        gateway.failOps.clear()
        gateway.resumeFor = { emptyList() }
        sender.kick(s1u1)
        settle()
        assertNoPending()
        assertEquals(1, gateway.count("resume"))
        assertEquals(1, gateway.count("nextUp"))
        assertEquals("a confirmed change does not ask for the item", 0, gateway.count("item"))
    }

    /**
     * Round 3 (Ben: "marking things as watched doesn't take effect till I reload"): Home, collected all along as its ViewModel does,
     * shows the server's own Continue Watching and Next Up once the change has reached the server, offline or not; not before.
     */
    @Test fun homeCollectedAllAlongShowsTheServersListsOnceAWatchedMarkIsDeliveredEvenAfterBeingOffline() = runBlocking {
        saveResume(film("a"), film("b"))
        saveList(ListKey.NEXT_UP, film("n"))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Home>()
        val watching = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { repo.home(s1u1).collect { seen += it } } // not the app scope: settle() waits for that
        try {
            settle()
            gateway.calls.clear()
            gateway.failOps["setPlayed"] = AppError.Kind.OFFLINE
            playstate.setPlayed(s1u1, id("a"), true)
            settle()
            assertEquals("not asked before the server has the change", 0, gateway.count("resume") + gateway.count("nextUp"))
            gateway.failOps.clear()
            gateway.resumeFor = { films("b") }
            gateway.nextUpFor = { films("m") }
            sender.kick(s1u1) // the connection is back: the later drain
            settle()
            until { seen.last().resume.items.map { it.item.name } == listOf("Film b") && seen.last().nextUp.items.map { it.item.name } == listOf("Film m") }
            assertEquals("once each", listOf(1, 1), listOf(gateway.count("resume"), gateway.count("nextUp")))
        } finally {
            watching.cancelAndJoin() // before the database closes
        }
    }

    @Test fun overrulingAlsoRefreshesTheItemSoTheServersCopyReplacesTheLocalOne() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.failOps["setFavourite"] = AppError.Kind.FORBIDDEN
        gateway.itemFor = { film("a", ud(favourite = false)) }
        playstate.setFavourite(s1u1, id("a"), true)
        settle()
        assertEquals(0, queued())
        assertEquals(1, gateway.count("item"))
        assertFalse("a refused change does not stay on screen", userData("a")!!.favourite)
        assertNoPending()
    }

    @Test fun theServersNewerCopyOverrulesWithoutAnyWrite() = runBlocking {
        save(film("a", ud(positionMs = 0)))
        sync.copies[s1u1 to id("a")] = app.shoalwatch.data.sync.ServerUserData(played = true, playCount = 1, positionMs = 0, favourite = false, lastPlayedAt = now + 3_600_000)
        gateway.itemFor = { film("a", ud(played = true, positionMs = 0)) }
        playstate.recordStop(s1u1, id("a"), 120_000, finished = false, reached = false)
        settle()
        assertEquals("the later change on the server wins", 0, gateway.count("setPosition"))
        assertEquals(0, userData("a")!!.positionMs)
        assertTrue(userData("a")!!.played)
        assertNoPending()
    }

    @Test fun aRefusalForOneItemLeavesOtherItemsRowsAlone() = runBlocking {
        save(film("a", ud()), film("b", ud()))
        gateway.itemFor = { film("a", ud(favourite = false)) }
        gateway.onCall = { call -> if (call == "setFavourite:${id("a")}:true") throw AppError.of(AppError.Kind.FORBIDDEN) }
        playstate.setFavourite(s1u1, id("a"), true)
        playstate.setFavourite(s1u1, id("b"), true)
        settle()
        assertEquals(0, queued())
        assertFalse(userData("a")!!.favourite)
        assertTrue("b was sent and confirmed as itself", userData("b")!!.favourite)
        assertEquals(listOf("setFavourite:${id("a")}:true", "setFavourite:${id("b")}:true"), gateway.calls.filter { it.startsWith("setFavourite") })
        assertNoPending()
    }

    @Test fun aRowReplacedWhileInFlightLandsAsNothingAndTheNewerRowCarriesIt() = runBlocking {
        save(film("a", ud()))
        val held = holdServer()
        playstate.setFavourite(s1u1, id("a"), true)
        until { gateway.count("setFavourite") == 1 }
        playstate.setFavourite(s1u1, id("a"), false) // replaces the row in flight
        release(held)
        settle()
        assertEquals(0, queued())
        assertFalse(userData("a")!!.favourite)
        assertNull(userData("a")!!.pendingSyncAt)
    }

    @Test fun refreshKeysGoThereAndBackAndAnythingElseIsRefused() {
        val series = id("show")
        val season = id("s1")
        val all = listOf(Recompute.Resume, Recompute.NextUp, Recompute.Seasons(series), Recompute.Episodes(series, season), Recompute.Item(season))
        assertEquals(listOf("resume", "nextUp", "seasons:$series", "episodes:$series:$season", "item:$season"), all.map { it.key() })
        assertEquals(all, all.map { parseRecompute(it.key()) })
        for (bad in listOf("", "Resume", "resume:x", "item", "item:../x", "item:${series.uppercase()}", "seasons:$series:$season", "episodes:$series", "views")) {
            assertNull(bad, parseRecompute(bad))
        }
    }
}
