package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.ui.browse.id
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's behaviour: resume, Up next, skip, finished, pause reports, sleep timer, speed, next and previous. */
internal class PlaybackControllerTest : PlaybackControllerTestBase() {
    @Test fun resumesFromTheSavedPositionBySeeking() = runTest {
        saved(episode(101, positionMs = 300_000))
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(300_000L, engine.loadedAt)
        assertEquals(Phase.BUFFERING, c.state.value.phase)
    }

    @Test fun nearlyFinishedStartsFromTheBeginning() = runTest {
        saved(episode(101, positionMs = runtime * 95 / 100))
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(0L, engine.loadedAt)
    }

    @Test fun barelyStartedStartsFromTheBeginning() = runTest {
        saved(episode(101, positionMs = runtime * 4 / 100))
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(0L, engine.loadedAt)
    }

    @Test fun fromTheBeginningIgnoresTheSavedPosition() = runTest {
        saved(episode(101, positionMs = 300_000))
        val c = controller(); c.open(ep1, StartFrom.BEGINNING); runCurrent()
        assertEquals(0L, engine.loadedAt)
    }

    @Test fun theTitleIsPublishedAndGivenToTheMediaSession() = runTest {
        val c = controller(); openAndPlay(c)
        val s = c.state.value
        assertEquals(session, s.session); assertEquals(ep1, s.itemId); assertEquals("Episode 101", s.title); assertEquals("Series 50", s.episodeLine)
        assertEquals(Phase.PLAYING, s.phase); assertEquals(PlayMethod.DIRECT_PLAY, s.method); assertEquals(ep2, s.next)
        assertEquals("Episode 101", engine.metadataTitle); assertEquals("Series 50", engine.metadataSubtitle)
        assertEquals(listOf("started@0"), reports.calls)
    }

    @Test fun anItemThatNeverArrivesFailsAsNotFoundAndAsksTheServerNothing() = runTest {
        val c = controller(); c.open(id(777), StartFrom.RESUME); advanceTimeBy(5_001); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, c.state.value.error)
        assertEquals(0, playbackInfos)
    }

    @Test fun upNextCountsDownAndPlaysTheNextEpisode() = runTest {
        val c = controller(); openAndPlay(c)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertEquals(UpNextState(ep2, "Episode 102", 10, cancelled = false), c.state.value.upNext)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
        assertEquals(2, engine.loads.size)
        assertEquals(1, stopCalls.size); assertTrue(stopCalls.single().endsWith(" finished"))
    }

    @Test fun cancelledUpNextNeverAutoPlays() = runTest {
        val c = controller(); openAndPlay(c)
        engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        advanceTimeBy(3_000); runCurrent(); c.cancelUpNext(); advanceTimeBy(20_000); runCurrent()
        assertEquals(ep1, c.state.value.itemId); assertEquals(Phase.ENDED, c.state.value.phase)
        assertEquals(true, c.state.value.upNext?.cancelled)
        assertEquals(1, engine.loads.size)
    }

    @Test fun withoutAutoplayUpNextWaitsForTheUser() = runTest {
        autoplay = false
        val c = controller(); openAndPlay(c)
        engine.isPlaying = false; engine.emit(EngineEvent.Ended); advanceTimeBy(20_000); runCurrent()
        assertEquals(ep1, c.state.value.itemId); assertNull(c.state.value.upNext?.secondsLeft)
        c.playUpNextNow(); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
    }

    @Test fun upNextAppearsAtTheCreditsAndPausingPausesTheCountdown() = runTest {
        gateway.segmentAnswer = listOf(MediaSegment(SegmentKind.OUTRO, 1_100_000, 1_200_000))
        val c = controller(); openAndPlay(c)
        at(1_099_000); assertNull(c.state.value.upNext)
        at(1_100_000); assertEquals(10, c.state.value.upNext?.secondsLeft)
        c.playPause(); runCurrent(); assertEquals(Phase.PAUSED, c.state.value.phase)
        val left = c.state.value.upNext?.secondsLeft
        advanceTimeBy(20_000); runCurrent()
        assertEquals(ep1, c.state.value.itemId); assertEquals(left, c.state.value.upNext?.secondsLeft)
        c.playPause(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
    }

    @Test fun skipIntroSeeksToTheSegmentEnd() = runTest {
        gateway.segmentAnswer = listOf(MediaSegment(SegmentKind.INTRO, 1_000, 3_000))
        val c = controller(); openAndPlay(c)
        at(500); assertNull(c.state.value.segmentNow)
        at(1_500); assertEquals(SegmentKind.INTRO, c.state.value.segmentNow?.kind)
        c.skipSegment(); runCurrent()
        assertEquals(listOf(3_000L), engine.seeks)
        assertNull(c.state.value.segmentNow)
    }

    @Test fun aFailedSegmentsCallIsIgnored() = runTest {
        gateway.segmentsFail = AppError.of(AppError.Kind.SERVER_ERROR)
        val c = controller(); openAndPlay(c)
        assertEquals(Phase.PLAYING, c.state.value.phase)
        assertTrue("asked, since the plan has segments", gateway.calls.contains("segments:$ep1"))
    }

    @Test fun playingPastNinetyPercentIsFinished() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_079_000); at(1_081_000)
        c.end(null); runCurrent()
        assertTrue(stopCalls.single().endsWith(" finished"))
    }

    @Test fun seekingPastNinetyPercentIsNotFinished() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_000); c.seekTo(1_140_000); runCurrent()
        at(1_141_000); at(1_142_000)
        c.end(null); runCurrent()
        assertEquals(listOf("stopped@1142000"), stopCalls)
    }

    @Test fun theStateSaysFinishedOnceThePlaybackCrossesTheMark() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_079_000); assertFalse(c.state.value.finished)
        at(1_081_000); assertTrue(c.state.value.finished)
    }

    @Test fun aSeekPastTheMarkLeavesTheStateNotFinished() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_000); c.seekTo(1_140_000); runCurrent()
        at(1_141_000); at(1_142_000)
        assertFalse(c.state.value.finished)
    }

    /** The controller is at its size limit (Milestone 11 T6 kept it at 399: the queue neighbours are `EpisodeNavigator`). */
    @Test fun theControllerIsUnchanged() {
        assertEquals(399, java.io.File("src/main/java/app/shoalwatch/data/playback/PlaybackController.kt").readLines().size)
    }

    @Test fun becomingNoisyReportsTheRealPosition() = runTest {
        val c = controller(); openAndPlay(c)
        at(42_000)
        engine.positionMs = 0; engine.isPlaying = false // the output changed and the engine let go of its position
        engine.emit(EngineEvent.BecameNoisy); engine.emit(EngineEvent.PlayingChanged(false)); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
        assertEquals("progress@42000 paused", reports.calls.last())
        assertEquals(1, reports.calls.count { it.endsWith("paused") })
    }

    @Test fun losingAudioFocusPausesAndReportsAndRegainingItResumes() = runTest {
        val c = controller(); openAndPlay(c)
        at(42_000)
        engine.isPlaying = false; engine.emit(EngineEvent.PlayingChanged(false)); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase); assertEquals("progress@42000 paused", reports.calls.last())
        engine.isPlaying = true; engine.emit(EngineEvent.PlayingChanged(true)); runCurrent()
        assertEquals(Phase.PLAYING, c.state.value.phase); assertEquals("progress@42000", reports.calls.last())
    }

    @Test fun aRebufferIsNotAPause() = runTest {
        val c = controller(); openAndPlay(c)
        at(42_000)
        engine.emit(EngineEvent.Buffering); engine.isPlaying = false; engine.emit(EngineEvent.PlayingChanged(false)); runCurrent()
        assertEquals(Phase.BUFFERING, c.state.value.phase)
        assertTrue(reports.calls.none { it.endsWith("paused") })
    }

    @Test fun hostStoppedPausesAndReports() = runTest {
        val c = controller(); openAndPlay(c)
        at(42_000); c.onHostStopped(); runCurrent()
        assertFalse(engine.isPlaying); assertEquals("progress@42000 paused", reports.calls.last())
    }

    @Test fun sleepTimerPausesAfterFifteenMinutes() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.MIN_15)
        assertTrue(c.state.value.sleep is SleepState.Minutes)
        clock.ms += 15 * 60_000L - 1; at(1_000); assertTrue(engine.isPlaying)
        clock.ms += 1; at(1_250)
        assertFalse(engine.isPlaying); assertEquals(SleepState.Off, c.state.value.sleep)
        assertEquals("progress@1250 paused", reports.calls.last())
    }

    @Test fun sleepAtTheEndOfTheItemPausesThereAndSuppressesUpNext() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.END_OF_ITEM)
        engine.isPlaying = false; engine.emit(EngineEvent.Ended); advanceTimeBy(20_000); runCurrent()
        assertNull(c.state.value.upNext); assertEquals(ep1, c.state.value.itemId)
        assertEquals(SleepState.Off, c.state.value.sleep)
    }

    @Test fun speedIsLimitedToTheOfferedValues() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSpeed(3f); assertEquals(1f, engine.speedSet); assertEquals(1f, c.state.value.speed)
        c.setSpeed(1.5f); assertEquals(1.5f, engine.speedSet); assertEquals(1.5f, c.state.value.speed)
    }

    @Test fun nextPlaysTheNextEpisodeAndStopsThisOne() = runTest {
        val c = controller(); openAndPlay(c)
        at(60_000); c.next(); runCurrent()
        assertEquals(ep2, c.state.value.itemId); assertEquals(listOf("stopped@60000"), stopCalls)
        assertEquals(ep1, c.state.value.previous)
    }

    @Test fun previousNearTheStartGoesBackAndLaterRestarts() = runTest {
        val c = controller(); openAndPlay(c, ep2)
        at(60_000); c.previous(); runCurrent()
        assertEquals(ep2, c.state.value.itemId); assertEquals(listOf(0L), engine.seeks)
        at(3_000); c.previous(); runCurrent()
        assertEquals(ep1, c.state.value.itemId)
    }

    @Test fun backingOutBeforeTheResumeSeekLandedKeepsTheResumePoint() = runTest {
        saved(episode(101, positionMs = 300_000))
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        engine.positionMs = 0 // still preparing
        engine.emit(EngineEvent.Buffering); advanceTimeBy(1_000); runCurrent()
        c.end(null); runCurrent()
        assertEquals(listOf("stopped@300000"), reports.calls)
    }
}
