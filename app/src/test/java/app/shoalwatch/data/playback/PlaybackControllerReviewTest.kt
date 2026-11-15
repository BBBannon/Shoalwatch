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
}
