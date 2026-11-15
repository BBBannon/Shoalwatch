package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.PlaybackState.Phase
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

    @Test fun theHostStoppingBeforeAnythingIsOpenStillRefusesControllers() = runTest {
        val c = controller()
        c.onHostStopped()
        assertTrue(engine.stoppedHost)
        c.onHostStarted()
        assertFalse(engine.stoppedHost)
    }
}
