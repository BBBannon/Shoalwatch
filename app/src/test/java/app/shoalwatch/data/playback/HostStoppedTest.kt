package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final review N-4: no background playback this milestone. While the host is stopped (screen off, Home pressed), a headset or
 * Bluetooth "play" through the media session does not start the film; once the player is back in front it does.
 */
internal class HostStoppedTest : PlaybackControllerTestBase() {
    @Test fun whileTheHostIsStoppedAControllerCannotStartPlayback() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.onHostStopped(); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
        assertTrue(engine.stoppedHost)
        engine.controllerPlay(); runCurrent()
        assertEquals("a headset's play is refused", Phase.PAUSED, c.state.value.phase)
        assertFalse(engine.playWhenReady)
    }

    @Test fun onceTheHostStartsAgainAControllerCanPlay() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.onHostStopped(); runCurrent()
        c.onHostStarted(); runCurrent()
        assertFalse(engine.stoppedHost)
        assertEquals("starting again does not play by itself", Phase.PAUSED, c.state.value.phase)
        engine.controllerPlay(); runCurrent()
        assertEquals(Phase.PLAYING, c.state.value.phase)
    }

    // ---- final-fixes review I-3: a load still in flight when the host stops arrives paused

    private fun hold() = CompletableDeferred<Unit>().also { gateway.infoGate = it }

    private fun TestScope.release(gate: CompletableDeferred<Unit>) { gateway.infoGate = null; gate.complete(Unit); runCurrent() }

    @Test fun anOpenWhoseAnswerArrivesAfterTheHostStoppedStartsPaused() = runTest {
        val c = controller()
        val gate = hold()
        c.open(ep1, StartFrom.RESUME); runCurrent()
        c.onHostStopped(); runCurrent()
        release(gate)
        assertEquals("it loaded", 1, engine.loads.size)
        assertFalse("loaded with playWhenReady = false", engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
        c.onHostStarted(); runCurrent()
        assertEquals("starting again does not play by itself", Phase.PAUSED, c.state.value.phase)
        c.playPause(); runCurrent()
        assertEquals("the user's Play plays", Phase.PLAYING, c.state.value.phase)
    }

    @Test fun aQualityChangeWhoseAnswerArrivesAfterTheHostStoppedStaysPaused() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        val gate = hold()
        c.setQuality(Quality.MBPS_8); runCurrent()
        c.onHostStopped(); runCurrent()
        release(gate)
        assertEquals("it reloaded", 2, engine.loads.size)
        assertFalse("reloaded with playWhenReady = false", engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
    }

    // ---- subtitle-parsers review I-3: a load that STARTS after the host stopped loads paused too

    private fun fail(f: EngineFailure) { engine.isPlaying = false; engine.emit(EngineEvent.Failed(f)) }

    @Test fun aFallbackAfterTheHostStoppedLoadsPausedAndPlaysOnceTheUserSaysSo() = runTest {
        answerOnly("direct-play.json", "transcode.json")
        val c = controller(); openAndPlay(c); at(30_000)
        c.onHostStopped(); runCurrent()
        fail(EngineFailure.DECODER); runCurrent()
        assertEquals("fell back", 2, engine.loads.size)
        assertFalse("the transcode loads paused", engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
        c.onHostStarted(); runCurrent()
        assertEquals("starting again does not play by itself", Phase.PAUSED, c.state.value.phase)
        c.playPause(); runCurrent()
        assertEquals("the user's Play plays", Phase.PLAYING, c.state.value.phase)
    }

    @Test fun aReResolveAfterTheHostStoppedLoadsPaused() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.onHostStopped(); runCurrent()
        fail(EngineFailure.NETWORK); runCurrent()
        assertEquals("re-resolved", 2, engine.loads.size)
        assertFalse(engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
    }

    @Test fun aLoadAfterTheHostStartedAgainPlaysAsUsual() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.onHostStopped(); runCurrent()
        c.onHostStarted(); runCurrent()
        c.playPause(); runCurrent()
        assertEquals(Phase.PLAYING, c.state.value.phase)
        fail(EngineFailure.NETWORK); runCurrent()
        assertEquals("re-resolved", 2, engine.loads.size)
        assertTrue("in front again, a reload plays as before", engine.playWhenReady)
    }

    @Test fun theHostStoppingBeforeAnythingIsOpenStillRefusesControllers() = runTest {
        val c = controller()
        c.onHostStopped()
        assertTrue(engine.stoppedHost)
        c.onHostStarted()
        assertFalse(engine.stoppedHost)
    }
}
