package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One forced-transcode fallback, one re-resolve after a network or sign-in failure, and a new route when one goes stale. */
internal class PlaybackControllerFallbackTest : PlaybackControllerTestBase() {
    private fun fail(f: EngineFailure) { engine.isPlaying = false; engine.emit(EngineEvent.Failed(f)) }

    @Test fun aDecoderErrorInDirectPlayFallsBackOnceAtTheSamePosition() = runTest {
        answerOnly("direct-play.json", "transcode.json")
        val c = controller(); openAndPlay(c); at(600_000)
        fail(EngineFailure.DECODER); runCurrent()
        assertEquals(2, playbackInfos)
        assertFalse(gateway.bodies[1].enableDirectPlay)
        val failedStop = reports.stops.single()
        assertTrue(failedStop.failed); assertEquals(600_000L, failedStop.positionMs); assertEquals(PlayMethod.DIRECT_PLAY, failedStop.method)
        assertEquals(2, engine.loads.size); assertEquals(600_000L, engine.loadedAt)
        assertTrue(c.state.value.fellBackToTranscode); assertEquals(PlayMethod.TRANSCODE, c.state.value.method)
    }

    @Test fun aSecondDecoderErrorFailsWithoutAThirdAsk() = runTest {
        answerOnly("direct-play.json", "transcode.json")
        val c = controller(); openAndPlay(c)
        fail(EngineFailure.DECODER); runCurrent(); engine.startPlaying(); runCurrent()
        fail(EngineFailure.FORMAT_UNSUPPORTED); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase); assertEquals(AppError.Kind.PLAYBACK_FAILED, c.state.value.error)
        assertEquals(2, playbackInfos)
    }

    @Test fun aDecoderErrorDuringATranscodeFailsAtOnce() = runTest {
        answerOnly("transcode.json")
        val c = controller(); openAndPlay(c)
        fail(EngineFailure.DECODER); runCurrent()
        assertEquals(AppError.Kind.PLAYBACK_FAILED, c.state.value.error)
        assertEquals(1, playbackInfos)
        assertTrue("the play session is stopped as failed", reports.stops.single().failed)
    }

    @Test fun aNetworkErrorReResolvesExactlyOnce() = runTest {
        val c = controller(); openAndPlay(c); at(90_000)
        fail(EngineFailure.NETWORK); runCurrent()
        assertEquals(2, playbackInfos); assertEquals(90_000L, engine.loadedAt)
        engine.startPlaying(); runCurrent()
        fail(EngineFailure.NETWORK); runCurrent()
        assertEquals(2, playbackInfos)
        assertEquals(Phase.FAILED, c.state.value.phase); assertEquals(AppError.Kind.NETWORK, c.state.value.error)
    }

    @Test fun unauthorisedThenAResolverUnauthorisedFails() = runTest {
        val c = controller(); openAndPlay(c)
        gateway.failWith = AppError.of(AppError.Kind.UNAUTHORISED)
        fail(EngineFailure.HTTP_UNAUTHORISED); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase); assertEquals(AppError.Kind.UNAUTHORISED, c.state.value.error)
        assertEquals(1, engine.loads.size)
    }

    @Test fun aStaleRouteGetsANewRouteWithoutAskingTheServer() = runTest {
        val c = controller(); openAndPlay(c); at(90_000)
        fail(EngineFailure.ROUTE_STALE); runCurrent()
        assertEquals(1, playbackInfos); assertEquals(2, routesMade.size)
        assertEquals(2, engine.loads.size); assertEquals(90_000L, engine.loadedAt)
        assertTrue("the same play session goes on", stopCalls.isEmpty())
    }

    @Test fun aRouteRefusedAsSignedOutEndsPlaybackSendingNothing() = runTest {
        val c = controller(); openAndPlay(c)
        fail(EngineFailure.NOT_SIGNED_IN); runCurrent()
        assertTrue(engine.released); assertEquals(PlaybackState.CLOSED, c.state.value)
        assertTrue(stopCalls.isEmpty())
    }

    @Test fun aFailureBeforePlayingFallsBackAtTheResumePointNotZero() = runTest {
        answerOnly("direct-play.json", "transcode.json")
        saved(episode(101, positionMs = 300_000))
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        engine.positionMs = 0
        fail(EngineFailure.DECODER); runCurrent()
        assertEquals(300_000L, engine.loadedAt)
        assertEquals(300_000L, reports.stops.single().positionMs)
    }
}
