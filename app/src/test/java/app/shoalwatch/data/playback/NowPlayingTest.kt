package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.0.1: whether any of our players is playing, for the app lock's "Lock after" (see "App lock" in docs/ARCHITECTURE.md). */
class NowPlayingTest {
    private val said = mutableListOf<Boolean>()
    private val now = NowPlaying { said += it }

    @Test fun itSaysEachChangeOnce() {
        val engine = now.reporter()
        engine(true); engine(true); engine(false); engine(false)
        assertEquals(listOf(true, false), said)
    }

    @Test fun anOldPlayerStoppingDoesNotHideANewOneThatIsPlaying() {
        val old = now.reporter()
        val new = now.reporter()
        old(true); new(true); old(false)
        assertEquals(listOf(true), said)
        new(false)
        assertEquals(listOf(true, false), said)
    }
}
