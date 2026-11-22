package app.shoalwatch.data.playback

import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.browse.userData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A play queue drives Up next, Next and Previous instead of the series; without one nothing changes (the other controller tests);
 * and the version chosen for one play is sent for that play only.
 */
internal class PlayQueueControllerTest : PlaybackControllerTestBase() {
    private val film = id(7)
    private val video = id(8)
    private val chosen = "abcdef0123456789abcdef0123456789"

    private fun TestScope.queued(vararg ids: String): PlayQueue {
        val filmEntry = ItemWithUserData(item(7, ItemKind.MOVIE, "Film 7"), userData())
        val videoEntry = ItemWithUserData(item(8, ItemKind.VIDEO, "Video 8"), userData())
        saved(filmEntry); saved(videoEntry)
        val all = mapOf(ep1 to episode(101), ep2 to episode(102), film to filmEntry, video to videoEntry)
        return PlayQueue.of(ids.map { all.getValue(it).item }, shuffle = false)!!
    }

    @Test fun upNextFollowsTheQueueNotTheSeries() = runTest {
        val q = queued(ep1, film, ep2)
        val c = controller(); c.open(ep1, StartFrom.RESUME, q); runCurrent()
        engine.durationMs = runtime; engine.startPlaying(); runCurrent()
        assertEquals(film, c.state.value.next); assertNull(c.state.value.previous)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertEquals(film, c.state.value.upNext?.itemId); assertEquals("Film 7", c.state.value.upNext?.title)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(film, c.state.value.itemId)
        assertEquals(ep1, c.state.value.previous); assertEquals(ep2, c.state.value.next)
    }

    @Test fun nextAndPreviousWalkTheQueue() = runTest {
        val q = queued(ep2, video, ep1)
        val c = controller(); c.open(ep2, StartFrom.RESUME, q); runCurrent()
        at(60_000); c.next(); runCurrent()
        assertEquals(video, c.state.value.itemId)
        at(3_000); c.next(); runCurrent()
        assertEquals(ep1, c.state.value.itemId)
        at(3_000); c.previous(); runCurrent()
        assertEquals(video, c.state.value.itemId)
    }

    @Test fun atTheLastItemThereIsNoNextAndNoUpNext() = runTest {
        val q = queued(film, ep1)
        val c = controller(); c.open(ep1, StartFrom.RESUME, q); runCurrent()
        engine.durationMs = runtime; engine.startPlaying(); runCurrent()
        assertNull(c.state.value.next) // the series next episode (ep2) is not offered
        c.next(); runCurrent()
        assertEquals(ep1, c.state.value.itemId)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertNull(c.state.value.upNext)
    }

    @Test fun atTheFirstItemPreviousRestartsIt() = runTest {
        val q = queued(ep2, ep1)
        val c = controller(); c.open(ep2, StartFrom.RESUME, q); runCurrent()
        engine.durationMs = runtime; engine.startPlaying(); runCurrent()
        assertNull(c.state.value.previous) // not ep1 by the series order
        at(2_000); c.previous(); runCurrent()
        assertEquals(ep2, c.state.value.itemId); assertEquals(listOf(0L), engine.seeks)
    }

    @Test fun anItemThatIsNotInTheQueueFollowsTheSeries() = runTest {
        val q = queued(film, video)
        val c = controller(); c.open(ep1, StartFrom.RESUME, q); runCurrent()
        assertEquals(ep2, c.state.value.next)
    }

    @Test fun noQueueIsTheSeriesAsBefore() = runTest {
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(ep2, c.state.value.next)
    }

    @Test fun aRetryKeepsTheQueue() = runTest {
        val q = queued(ep1, film)
        val c = controller(); c.open(ep1, StartFrom.RESUME, q); runCurrent()
        c.open(ep1, StartFrom.RESUME); runCurrent() // as the Retry button does
        assertEquals(film, c.state.value.next)
    }

    // ---- versions

    @Test fun theChosenVersionIsSentForThatPlayOnly() = runTest {
        val q = queued(ep1, ep2)
        val c = controller(); c.open(ep1, StartFrom.RESUME, q, chosen); runCurrent()
        assertEquals(chosen, gateway.bodies.last().mediaSourceId)
        engine.durationMs = runtime; engine.startPlaying(); runCurrent()
        at(60_000); c.next(); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
        assertNull(gateway.bodies.last().mediaSourceId)
    }

    @Test fun noVersionSendsNone() = runTest {
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertNull(gateway.bodies.last().mediaSourceId)
    }

    @Test fun anInvalidVersionIsNeverSent() = runTest {
        val c = controller()
        for (bad in listOf("../x", "abc", "ABCDEF0123456789ABCDEF012345678Z", chosen + "0", "")) {
            gateway.bodies.clear(); gateway.answer(fixtureInfo("direct-play.json"))
            c.open(ep1, StartFrom.RESUME, null, bad); runCurrent()
            assertTrue(bad, gateway.bodies.isNotEmpty())
            assertNull(bad, gateway.bodies.last().mediaSourceId)
        }
    }
}
