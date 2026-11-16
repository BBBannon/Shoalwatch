package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Task 6 review's findings (I-1 to I-3, M-1 to M-3, M-9), each as the behaviour that must hold. */
internal class PlaybackControllerReviewTest : PlaybackControllerTestBase() {
    private val outro = MediaSegment(SegmentKind.OUTRO, 1_100_000, 1_200_000)

    // ---- I-2: an end-of-item sleep always wins over Up next

    @Test fun sleepAtTheEndOfAnItemWithAnOutroNeverShowsOrPlaysUpNext() = runTest {
        gateway.segmentAnswer = listOf(outro)
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.END_OF_ITEM)
        at(1_150_000)
        assertNull("not at the credits either", c.state.value.upNext)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertNull(c.state.value.upNext)
        assertEquals(ep1, c.state.value.itemId)
        assertEquals(1, engine.loads.size)
        assertFalse(engine.isPlaying)
    }

    @Test fun sleepAtTheEndOfAnItemWithoutAnOutroNeverPlaysUpNext() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.END_OF_ITEM)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertNull(c.state.value.upNext)
        assertEquals(ep1, c.state.value.itemId)
    }

    // ---- I-3: pause while buffering; a due sleep always pauses

    @Test fun pauseWhileBufferingPausesAndStaysPausedWhenReady() = runTest {
        val c = controller()
        c.open(ep1, StartFrom.BEGINNING); runCurrent()
        assertEquals(Phase.BUFFERING, c.state.value.phase)
        c.playPause(); runCurrent()
        assertFalse(engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
        assertFalse(engine.isPlaying)
        c.playPause(); runCurrent()
        assertTrue(engine.isPlaying)
    }

    @Test fun aSleepTimerDueWhileBufferingPauses() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.MIN_15)
        engine.emit(EngineEvent.Buffering); runCurrent()
        clock.ms += 15 * 60_000L + 1; advanceTimeBy(250); runCurrent()
        assertFalse("nothing plays when the rebuffer ends", engine.playWhenReady)
        assertEquals(SleepState.Off, c.state.value.sleep)
    }

    @Test fun aSleepTimerDueDuringAnUpNextCountdownPlaysNothing() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.MIN_15)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertTrue(c.state.value.upNext?.secondsLeft != null)
        clock.ms += 15 * 60_000L + 1; advanceTimeBy(250); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(ep1, c.state.value.itemId)
        assertEquals(1, engine.loads.size)
        assertNull("the countdown was cancelled", c.state.value.upNext?.secondsLeft)
    }

    // ---- I-1: the old player is released before the new one loads

    @Test fun aSecondPlayerLoadsOnlyAfterTheFirstIsReleased() = runTest {
        val log = mutableListOf<String>()
        engine.log = log; engine.name = "first"
        val first = controller(); openAndPlay(first)
        val second = controller(FakePlayerEngine().also { it.log = log; it.name = "second" })
        assertEquals("making a player builds no session yet", listOf("first load"), log)
        second.open(ep2, StartFrom.BEGINNING); runCurrent()
        assertEquals(listOf("first load", "first release", "second load"), log)
        assertNull("the first has ended", first.state.value.session)
    }

    // ---- M-1: a closed controller is told apart from one never opened

    @Test fun aRefusedOpenIsClosedNotEmpty() = runTest {
        activePlayback.sessionChanged(app.shoalwatch.ui.browse.AMY)
        val c = controller()
        assertEquals(PlaybackState.EMPTY, c.state.value)
        c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(PlaybackState.CLOSED, c.state.value)
        assertTrue(c.state.value.closed)
        assertNull(c.state.value.session)
    }

    // ---- M-2: the old episode is paused while the next one resolves

    @Test fun nextPausesTheOldEpisodeAndAFailedResolveStaysPaused() = runTest {
        val c = controller(); openAndPlay(c)
        gateway.infos.clear(); gateway.failWith = AppError.of(AppError.Kind.OFFLINE)
        val gate = CompletableDeferred<Unit>().also { gateway.infoGate = it }
        c.next(); runCurrent()
        assertFalse("not audible while the next one resolves", engine.isPlaying)
        gate.complete(Unit); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase)
        assertFalse(engine.isPlaying)
    }

    // ---- M-3: a controller that ends itself releases its record

    @Test fun aSignedOutRouteEndReleasesTheRecord() = runTest {
        val c = controller(); openAndPlay(c)
        assertTrue(activePlayback.registered)
        engine.emit(EngineEvent.Failed(EngineFailure.NOT_SIGNED_IN)); runCurrent()
        assertFalse(activePlayback.registered)
    }

    // ---- M-9: the reroute cap

    @Test fun aRouteThatKeepsGoingStaleIsReroutedThreeTimesThenFailsAsOffline() = runTest {
        val c = controller(); openAndPlay(c)
        repeat(3) { engine.emit(EngineEvent.Failed(EngineFailure.ROUTE_STALE)); runCurrent() }
        assertEquals(Phase.BUFFERING, c.state.value.phase)
        assertEquals("one route for the start and one per reroute", 4, routesMade.size)
        engine.emit(EngineEvent.Failed(EngineFailure.ROUTE_STALE)); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase)
        assertEquals(AppError.Kind.OFFLINE, c.state.value.error)
        assertEquals(4, routesMade.size)
        assertEquals("no server call for a reroute", 1, playbackInfos)
    }

    @Test fun aReadyBetweenRerouteResetsTheCount() = runTest {
        val c = controller(); openAndPlay(c)
        repeat(3) { engine.emit(EngineEvent.Failed(EngineFailure.ROUTE_STALE)); runCurrent() }
        engine.emit(EngineEvent.Ready); runCurrent()
        engine.emit(EngineEvent.Failed(EngineFailure.ROUTE_STALE)); runCurrent()
        assertEquals(Phase.BUFFERING, c.state.value.phase)
        assertEquals(5, routesMade.size)
    }
}
