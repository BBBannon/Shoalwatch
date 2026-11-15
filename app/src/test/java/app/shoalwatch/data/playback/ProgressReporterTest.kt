package app.shoalwatch.data.playback

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.PSID
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** WHEN to report: start, every 10 s while playing, pause and seek at once, ping a paused transcode, stop once with the real position. */
class ProgressReporterTest {
    private val session = Session(S1, U1)
    private val clock = TestMonotonic(0)
    private val fake = FakePlaybackReports()

    private fun at(ms: Long, method: PlayMethod = PlayMethod.DIRECT_PLAY, paused: Boolean = false) =
        PlaybackReport(ITEM, SOURCE, PSID, method, ms, paused, audioIndex = 1, subtitleIndex = null)

    private fun TestScope.reporter(scope: CoroutineScope = this) = ProgressReporter(fake, session, scope, clock)

    @Test fun startsOnceThenEveryTenSecondsWhilePlaying() = runTest {
        val r = reporter(); r.onPlaying(at(0)); advanceTimeBy(1); r.tick(at(5_000)); clock.ms += 10_000; r.tick(at(10_000)); runCurrent()
        assertEquals(listOf("started@0", "progress@10000"), fake.calls)
    }

    @Test fun ticksBeforeTheIntervalSendNothingAndTheIntervalRestartsAfterEachReport() = runTest {
        val r = reporter(); r.onPlaying(at(0)); runCurrent()
        clock.ms = 9_999; r.tick(at(9_999)); runCurrent()
        clock.ms = 10_000; r.tick(at(10_000)); runCurrent()
        clock.ms = 15_000; r.onSeeked(at(300_000)); runCurrent()
        clock.ms = 24_999; r.tick(at(309_999)); runCurrent()
        clock.ms = 25_000; r.tick(at(310_000)); runCurrent()
        assertEquals(listOf("started@0", "progress@10000", "progress@300000", "progress@310000"), fake.calls)
    }

    @Test fun pauseReportsTheRealPositionAtOnce() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(42_000).copy(paused = true)); runCurrent()
        assertEquals("progress@42000 paused", fake.calls.last())
    }

    @Test fun aPauseIsReportedAsPausedEvenIfTheSnapshotSaysOtherwise() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(42_000)); runCurrent()
        assertEquals("progress@42000 paused", fake.calls.last())
    }

    @Test fun playingAgainAfterAPauseIsAProgressNotASecondStart() = runTest {
        val r = reporter(); r.onPlaying(at(0)); runCurrent(); r.onPaused(at(5_000)); runCurrent(); r.onPlaying(at(5_000)); runCurrent()
        assertEquals(listOf("started@0", "progress@5000 paused", "progress@5000"), fake.calls)
    }

    @Test fun pausedTicksSendNoProgress() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(1_000)); runCurrent()
        clock.ms += 60_000; r.tick(at(1_000, paused = true)); runCurrent()
        assertEquals(listOf("started@0", "progress@1000 paused"), fake.calls)
    }

    @Test fun reportsAskedForTogetherKeepOnlyTheNewestProgress() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onSeeked(at(90_000)); r.onTracksChanged(at(91_000)); runCurrent()
        assertEquals(listOf("started@0", "progress@91000"), fake.calls)
    }

    @Test fun seeksAndTrackChangesReportAtOnce() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onSeeked(at(90_000)); runCurrent(); r.onTracksChanged(at(91_000)); runCurrent()
        assertEquals(listOf("started@0", "progress@90000", "progress@91000"), fake.calls)
    }

    @Test fun stopIsSentExactlyOnceWithTheLastPosition() = runTest {
        val r = reporter(); r.onPlaying(at(0)); clock.ms = 10_000; r.tick(at(10_000)); runCurrent()
        r.stop(at(61_000), finished = false); r.stop(at(99_000), finished = true); runCurrent()
        assertEquals(listOf("started@0", "progress@10000", "stopped@61000"), fake.calls)
        r.onPlaying(at(70_000)); r.onSeeked(at(80_000)); clock.ms += 60_000; r.tick(at(90_000)); runCurrent()
        assertEquals("nothing after the stop", 3, fake.calls.size)
    }

    /** Research: other clients sent 0 when Bluetooth disconnected. The output changing must never cost the real position. */
    @Test fun aStopAtZeroAfterTheOutputChangedSendsTheRealPosition() = runTest {
        val r = reporter(); r.onPlaying(at(0)); clock.ms = 10_000; r.tick(at(1_200_000)); runCurrent()
        r.onPaused(at(1_234_000)) // becoming noisy: headphones gone
        r.stop(at(0), finished = false) // the engine has already let go of its position
        runCurrent()
        // The pause was still waiting when the stop came, so the stop replaced it; the stop carries the pause's position.
        assertEquals(listOf("started@0", "progress@1200000", "stopped@1234000"), fake.calls)
        assertEquals(1_234_000, fake.stops.single().positionMs)
    }

    @Test fun aStopAtZeroLongAfterTheNoisyPauseStillSendsThePausePosition() = runTest {
        val r = reporter(); r.onPlaying(at(0)); runCurrent()
        r.onPaused(at(1_234_000)); runCurrent()
        clock.ms += 120_000; r.tick(at(0, paused = true)) // a released engine reports 0 on a tick too
        r.stop(at(0), finished = false); runCurrent()
        assertEquals(listOf("started@0", "progress@1234000 paused", "stopped@1234000"), fake.calls)
    }

    @Test fun aStopAfterASeekBackToTheStartReallyIsAtZero() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onSeeked(at(600_000)); r.onSeeked(at(0)); r.stop(at(0), finished = false); runCurrent()
        assertEquals("stopped@0", fake.calls.last())
    }

    @Test fun theFinishedFlagReachesTheStop() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.stop(at(1_000_000), finished = true); runCurrent()
        assertEquals("stopped@1000000 finished", fake.calls.last())
    }

    @Test fun aStopBeforeAnythingPlayedIsStillSent() = runTest {
        val r = reporter(); r.stop(at(0), finished = false); runCurrent()
        assertEquals(listOf("stopped@0"), fake.calls)
    }

    @Test fun pausedTranscodeIsPingedEveryThirtySeconds() = runTest {
        val r = reporter(); r.onPlaying(at(0, PlayMethod.TRANSCODE)); r.onPaused(at(5_000, PlayMethod.TRANSCODE)); runCurrent()
        clock.ms = 29_999; r.tick(at(5_000, PlayMethod.TRANSCODE, paused = true)); runCurrent()
        clock.ms = 30_000; r.tick(at(5_000, PlayMethod.TRANSCODE, paused = true)); runCurrent()
        clock.ms = 45_000; r.tick(at(5_000, PlayMethod.TRANSCODE, paused = true)); runCurrent()
        clock.ms = 60_000; r.tick(at(5_000, PlayMethod.TRANSCODE, paused = true)); runCurrent()
        assertEquals(listOf("started@0", "progress@5000 paused", "ping:$PSID", "ping:$PSID"), fake.calls)
    }

    @Test fun aPausedDirectPlayIsNeverPinged() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(5_000)); runCurrent()
        clock.ms = 120_000; r.tick(at(5_000, paused = true)); runCurrent()
        assertEquals(listOf("started@0", "progress@5000 paused"), fake.calls)
    }

    @Test fun aTranscodeWithoutAPlaySessionIsNeverPinged() = runTest {
        val r = reporter(); r.onPlaying(at(0, PlayMethod.TRANSCODE).copy(playSessionId = null)); r.onPaused(at(5_000, PlayMethod.TRANSCODE).copy(playSessionId = null)); runCurrent()
        clock.ms = 120_000; r.tick(at(5_000, PlayMethod.TRANSCODE, paused = true).copy(playSessionId = null)); runCurrent()
        assertTrue(fake.calls.none { it.startsWith("ping") })
    }

    @Test fun aFailingReportNeverThrowsToTheCaller() = runTest {
        fake.answer = { ReportOutcomeOrThrow.Throw(IOException("boom")) }
        val r = reporter()
        r.onPlaying(at(0)); runCurrent()
        fake.answer = { ReportOutcomeOrThrow.Outcome(ReportOutcome.NotSent(app.shoalwatch.core.AppError.Kind.OFFLINE)) }
        r.onPaused(at(1_000)); runCurrent()
        fake.answer = { ReportOutcomeOrThrow.Throw(IllegalStateException("bad")) }
        r.onPlaying(at(1_000)); runCurrent(); clock.ms += 10_000; r.tick(at(11_000)); runCurrent()
        r.stop(at(12_000), finished = false); runCurrent()
        assertEquals(listOf("started@0", "progress@1000 paused", "progress@1000", "progress@11000", "stopped@12000"), fake.calls)
    }

    @Test fun aHangingReportNeverBlocksTheCallerAndOnlyTheNewestProgressWaits() = runTest {
        val hold = CompletableDeferred<Unit>()
        fake.gate = hold
        val r = reporter(backgroundScope)
        r.onPlaying(at(0)); runCurrent() // started is in flight and stuck
        for (i in 1..20) { clock.ms += 10_000; r.tick(at(i * 10_000L)) } // the caller is never held up
        r.onPaused(at(205_000)); runCurrent()
        assertEquals(listOf("started@0"), fake.calls)
        fake.gate = null; hold.complete(Unit); runCurrent()
        // Older progress reports waiting behind the stuck one were replaced by the newest: one request, not twenty.
        assertEquals(listOf("started@0", "progress@205000 paused"), fake.calls)
    }

    @Test fun aStopWaitingBehindAStuckReportReplacesQueuedProgress() = runTest {
        val hold = CompletableDeferred<Unit>()
        fake.gate = hold
        val r = reporter(backgroundScope)
        r.onPlaying(at(0)); runCurrent()
        r.onSeeked(at(50_000)); r.stop(at(60_000), finished = false); runCurrent()
        fake.gate = null; hold.complete(Unit); runCurrent()
        assertEquals(listOf("started@0", "stopped@60000"), fake.calls)
    }

    @Test fun everyReportGoesWithTheReportersOwnSession() = runTest {
        val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(1)); r.stop(at(2), finished = false); runCurrent()
        assertEquals(setOf(session), fake.sessions.toSet())
        assertTrue(Session(S2, U1) !in fake.sessions)
    }
}
