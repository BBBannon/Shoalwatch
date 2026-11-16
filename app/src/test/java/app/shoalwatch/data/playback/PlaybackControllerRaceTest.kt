package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SubtitleChoice.On
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import app.shoalwatch.data.playback.engine.SubtitleFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Task 7 review's fixes: a track change made while an earlier one (or a fallback) is still asking the server (I-1); the
 * rest of Task 6 M-9's list; the media session's title during a resolve (m-7); a sleep timer due while resolving (m-3).
 */
internal class PlaybackControllerRaceTest : PlaybackControllerTestBase() {
    private val english = AudioTrack(1, "English", "eng", "aac", 2, isDefault = true)
    private val japanese = AudioTrack(2, "Japanese", "jpn", "aac", 2, isDefault = false)
    private val pgs = SubtitleTrack(5, "PGS", "eng", "pgssub", SubtitleKind.PICTURE, false, false, false, external = false)

    private fun info(name: String): PlaybackInfo {
        val i = fixtureInfo(name)
        return i.copy(sources = listOf(i.sources.single().copy(audio = listOf(english, japanese), subtitles = listOf(pgs), defaultAudioIndex = 1, defaultSubtitleIndex = null)))
    }

    private fun answers(vararg infos: PlaybackInfo) { gateway.infos.clear(); infos.forEach { gateway.answer(it) } }

    private fun TestScope.started(c: PlaybackController) { openAndPlay(c); at(1_000) }

    /** Holds every PlaybackInfo call from now on until [release]. */
    private fun hold() = CompletableDeferred<Unit>().also { gateway.infoGate = it }

    private fun TestScope.release(gate: CompletableDeferred<Unit>) { gateway.infoGate = null; gate.complete(Unit); runCurrent() }

    // ---- I-1: the latest wanted request, not the plan still playing

    @Test fun anAudioChangeThenBackBeforeTheAnswerEndsOnTheLastChoice() = runTest {
        answers(info("transcode.json"), info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        val gate = hold()
        c.selectAudio(2); runCurrent()
        c.selectAudio(1); runCurrent()
        release(gate)
        assertEquals(1, c.state.value.audioIndex)
        assertEquals("the last ask is the last choice", 1, gateway.bodies.last().audioStreamIndex)
        assertEquals("eng", trackDao.rows.values.single().audioLanguage)
    }

    @Test fun anAudioChangeThenAPictureSubtitleBeforeTheAnswerKeepsBoth() = runTest {
        answers(info("transcode.json"), info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        val gate = hold()
        c.selectAudio(2); runCurrent()
        c.selectSubtitle(5); runCurrent()
        release(gate)
        assertEquals(2, gateway.bodies.last().audioStreamIndex)
        assertEquals(5, gateway.bodies.last().subtitleStreamIndex)
        assertEquals(2, c.state.value.audioIndex)
        assertEquals(On(5, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
        assertEquals("jpn", trackDao.rows.values.single().audioLanguage)
    }

    @Test fun aPictureSubtitleChosenDuringAFallbackIsAskedForNotJustLabelledBurnedIn() = runTest {
        answers(info("direct-play.json"), info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        val gate = hold()
        engine.emit(EngineEvent.Failed(EngineFailure.DECODER)); runCurrent()
        c.selectSubtitle(5); runCurrent()
        release(gate)
        val last = gateway.bodies.last()
        assertEquals("the stream that plays carries the picture track", 5, last.subtitleStreamIndex)
        assertFalse("still the fallback's conversion", last.enableDirectPlay == true)
        assertEquals(On(5, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
        assertTrue(c.state.value.fellBackToTranscode)
    }

    @Test fun aStaleRouteWhileATrackChangeIsBeingAskedForAsksAgainWithTheLatestChoice() = runTest {
        answers(info("transcode.json"), info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        val gate = hold()
        c.selectAudio(2); runCurrent()
        engine.emit(EngineEvent.Failed(EngineFailure.ROUTE_STALE)); runCurrent()
        release(gate)
        assertEquals("the reroute does not bring back the old plan (Task 8 review m-1)", 2, c.state.value.audioIndex)
        assertEquals(2, gateway.bodies.last().audioStreamIndex)
    }

    // ---- Task 6 M-9, the rest

    @Test fun aSecondUnauthorisedAfterASuccessfulReResolveFailsAsUnauthorised() = runTest {
        val c = controller(); openAndPlay(c)
        engine.emit(EngineEvent.Failed(EngineFailure.HTTP_UNAUTHORISED)); runCurrent()
        assertEquals(2, playbackInfos)
        engine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, c.state.value.phase)
        engine.emit(EngineEvent.Failed(EngineFailure.HTTP_UNAUTHORISED)); runCurrent()
        assertEquals(Phase.FAILED, c.state.value.phase)
        assertEquals(AppError.Kind.UNAUTHORISED, c.state.value.error)
        assertEquals("asked again only once", 2, playbackInfos)
    }

    @Test fun anEventFromTheOldItemAfterNextChangesNothingForTheNewOne() = runTest {
        val c = controller(); openAndPlay(c)
        val gate = hold()
        c.next(); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
        engine.emit(EngineEvent.Ready)
        engine.emit(EngineEvent.Ended)
        engine.emit(EngineEvent.PictureCues(SubtitleFrame.NONE)); runCurrent()
        assertEquals(Phase.RESOLVING, c.state.value.phase)
        assertNull(c.state.value.upNext)
        release(gate)
        assertEquals(Phase.BUFFERING, c.state.value.phase)
        assertEquals(2, engine.loads.size)
    }

    @Test fun theHostStoppingDuringAnUpNextCountdownCancelsIt() = runTest {
        val c = controller(); openAndPlay(c)
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        assertEquals(10, c.state.value.upNext?.secondsLeft)
        c.onHostStopped(); runCurrent()
        assertNull(c.state.value.upNext?.secondsLeft)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, engine.loads.size)
        assertEquals(ep1, c.state.value.itemId)
    }

    // ---- m-7: the media session shows only what is loaded

    @Test fun theMediaSessionKeepsTheOldTitleUntilTheNextEpisodeIsLoaded() = runTest {
        val c = controller(); openAndPlay(c)
        val gate = hold()
        c.next(); runCurrent()
        assertEquals("Episode 101", engine.metadataTitle)
        release(gate)
        assertEquals("Episode 102", engine.metadataTitle)
    }

    // ---- m-3: a sleep timer due while resolving

    @Test fun aSleepTimerDueWhileTheNextEpisodeResolvesLoadsItPaused() = runTest {
        val c = controller(); openAndPlay(c)
        c.setSleep(SleepChoice.MIN_15)
        val gate = hold()
        c.next(); runCurrent()
        clock.ms += 15 * 60_000L
        advanceTimeBy(250); runCurrent()
        assertEquals(SleepState.Off, c.state.value.sleep)
        release(gate)
        assertEquals(2, engine.loads.size)
        assertFalse("loaded paused, not started and paused on a later tick", engine.playWhenReady)
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(Phase.PAUSED, c.state.value.phase)
    }
}
