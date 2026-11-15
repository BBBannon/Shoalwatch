package app.shoalwatch.data.playback

import app.shoalwatch.data.db.playback.TrackChoiceEntity
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SubtitleChoice.On
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.SubtitleProfileDto
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.id
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audio and subtitle choice, the subtitles the app draws itself, and their memory per series, over the controller's fakes
 * (Robolectric: the subtitle files go through Media3's real parsers).
 */
@RunWith(RobolectricTestRunner::class)
internal class PlaybackControllerTracksTest : PlaybackControllerTestBase() {
    private val english = AudioTrack(1, "English", "eng", "aac", 2, isDefault = true)
    private val japanese = AudioTrack(2, "Japanese", "jpn", "aac", 2, isDefault = false)
    private val srt = SubtitleTrack(3, "English", "eng", "subrip", SubtitleKind.TEXT, false, false, false, external = true, fileFormat = "srt")
    private val ass = SubtitleTrack(4, "Signs", "eng", "ass", SubtitleKind.STYLED_TEXT, false, false, false, external = false, fileFormat = "ass")
    private val pgs = SubtitleTrack(5, "PGS", "eng", "pgssub", SubtitleKind.PICTURE, false, false, false, external = false)
    private val pgsJapanese = SubtitleTrack(7, "PGS JP", "jpn", "pgssub", SubtitleKind.PICTURE, false, false, false, external = false)
    private val allSubs = listOf(srt, ass, pgs, pgsJapanese)

    /** A fixture's answer with these tracks (and no default subtitle, so nothing starts on unless a test says). */
    private fun info(name: String, subs: List<SubtitleTrack> = allSubs, defaultSubtitle: Int? = null): PlaybackInfo {
        val i = fixtureInfo(name)
        return i.copy(sources = listOf(i.sources.single().copy(audio = listOf(english, japanese), subtitles = subs, defaultAudioIndex = 1, defaultSubtitleIndex = defaultSubtitle)))
    }

    private fun answers(vararg infos: PlaybackInfo) { gateway.infos.clear(); infos.forEach { gateway.answer(it) } }

    private fun file(name: String) = javaClass.getResource("/subtitles/$name")!!.readBytes()

    private fun PlaybackController.cueTexts() = cues.value.cues.map { it.text.toString() }

    private val subtitleFetches get() = gateway.calls.filter { it.startsWith("subtitle:") }

    private fun TestScope.started(c: PlaybackController) { openAndPlay(c); at(1_000) }

    // ---- what needs the server and what does not

    @Test fun aTextSubtitleChangeDuringTranscodeDoesNotReResolve() = runTest {
        answers(info("transcode.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        assertEquals(1, playbackInfos)
        assertEquals(1, engine.loads.size)
        assertEquals(listOf("subtitle:3.srt"), subtitleFetches)
        assertEquals(On(3, SubtitleDelivery.FILE), c.state.value.subtitle)
        assertEquals(Phase.PLAYING, c.state.value.phase)
    }

    @Test fun anAudioChangeDuringTranscodeReResolvesOnceAtTheCurrentPosition() = runTest {
        answers(info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        at(60_000)
        c.selectAudio(2); runCurrent()
        assertEquals(2, playbackInfos)
        assertEquals(2, gateway.bodies.last().audioStreamIndex)
        assertEquals(60_000L, engine.loadedAt)
        assertEquals(1, stopCalls.size)
        assertEquals(2, c.state.value.audioIndex)
    }

    @Test fun anAudioChangeDuringDirectPlaySwitchesInThePlayerByPosition() = runTest {
        answers(info("direct-play.json"))
        val c = controller(); started(c)
        c.selectAudio(2); runCurrent()
        assertEquals(1, playbackInfos)
        assertEquals(1, engine.audioOrdinal)
        assertEquals(2, c.state.value.audioIndex)
        assertTrue("the change is reported", reports.calls.any { it.startsWith("progress@") })
    }

    @Test fun aDirectPlayAudioTrackTheStreamDoesNotMatchIsConvertedWithTheChosenTrack() = runTest {
        answers(info("direct-play.json"), info("transcode.json"))
        engine.audioLanguages = listOf("en", "fr") // the stream's second track is French, not Japanese
        val c = controller(); started(c)
        c.selectAudio(2); runCurrent()
        assertEquals(2, playbackInfos)
        assertEquals(2, gateway.bodies.last().audioStreamIndex)
        assertFalse(gateway.bodies.last().enableDirectPlay)
        assertEquals(PlayMethod.TRANSCODE, c.state.value.method)
    }

    @Test fun aPictureSubtitleDuringDirectPlaySelectsByOrdinalWithoutReResolving() = runTest {
        answers(info("direct-play.json"))
        val c = controller(); started(c)
        c.selectSubtitle(7); runCurrent()
        assertEquals(1, playbackInfos)
        assertEquals("the second picture track in the stream", 1, engine.pictureOrdinal)
        assertEquals(On(7, SubtitleDelivery.IN_STREAM), c.state.value.subtitle)
        val frame = SubtitleFrame(emptyList())
        engine.emit(EngineEvent.PictureCues(frame)); runCurrent()
        assertSame("the stream's picture cues are what is drawn", frame, c.cues.value)
    }

    @Test fun aPictureSubtitleDuringTranscodeReResolvesWithPictureSubtitleIndex() = runTest {
        answers(info("transcode.json"), info("transcode.json"))
        val c = controller(); started(c)
        c.selectSubtitle(5); runCurrent()
        assertEquals(2, playbackInfos)
        assertEquals(5, gateway.bodies.last().subtitleStreamIndex)
        assertFalse(gateway.bodies.last().alwaysBurnInSubtitleWhenTranscoding)
        assertEquals(On(5, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
        assertNull("nothing for the player to draw", engine.pictureOrdinal)
    }

    @Test fun burnInSubtitleReResolvesWithBurnInSubtitleIndexAndForceTranscode() = runTest {
        answers(info("direct-play.json"), info("transcode.json"))
        gateway.subtitleFiles[4] = file("effects.ass")
        val c = controller(); started(c)
        c.selectSubtitle(4); runCurrent()
        assertTrue(c.state.value.subtitleEffectsNotDrawn)
        c.burnInSubtitle(4); runCurrent()
        val body = gateway.bodies.last()
        assertEquals(4, body.subtitleStreamIndex)
        assertTrue(body.alwaysBurnInSubtitleWhenTranscoding)
        assertFalse(body.enableDirectPlay)
        assertTrue("the profile lists the format as Encode only", SubtitleProfileDto("ass", "Encode") in body.deviceProfile.subtitleProfiles)
        assertFalse(SubtitleProfileDto("ass", "External") in body.deviceProfile.subtitleProfiles)
        assertEquals(On(4, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
        assertSame("nothing drawn by the app", SubtitleFrame.NONE, c.cues.value)
    }

    @Test fun leavingABurnInAsksAgainWithoutIt() = runTest {
        answers(info("direct-play.json"), info("transcode.json"), info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.burnInSubtitle(4); runCurrent()
        c.selectSubtitle(3); runCurrent()
        assertEquals(3, playbackInfos)
        assertEquals(-1, gateway.bodies.last().subtitleStreamIndex)
        assertTrue(gateway.bodies.last().enableDirectPlay)
        assertEquals(On(3, SubtitleDelivery.FILE), c.state.value.subtitle)
    }

    @Test fun anExternalPictureTrackIsAlwaysBurnedIn() = runTest {
        val sup = pgs.copy(index = 8, external = true)
        answers(info("direct-play.json", allSubs + sup), info("transcode.json", allSubs + sup))
        val c = controller(); started(c)
        c.selectSubtitle(8); runCurrent()
        assertEquals(8, gateway.bodies.last().subtitleStreamIndex)
        assertTrue(gateway.bodies.last().alwaysBurnInSubtitleWhenTranscoding)
        assertEquals(On(8, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
    }

    // ---- the starting choice

    @Test fun aPictureSubtitleChosenAtTheStartOfATranscodeIsBurnedInWithOneMoreAsk() = runTest {
        answers(info("transcode.json", defaultSubtitle = 5), info("transcode.json", defaultSubtitle = 5))
        val c = controller(); openAndPlay(c)
        assertEquals(2, playbackInfos)
        assertEquals(5, gateway.bodies.last().subtitleStreamIndex)
        assertEquals("loaded once, with the burned-in stream", 1, engine.loads.size)
        assertEquals(On(5, SubtitleDelivery.BURNED_IN), c.state.value.subtitle)
    }

    @Test fun anotherProfilesRememberedChoiceIsNeverUsed() = runTest {
        trackDao.rows[Triple(AMY.serverId, AMY.userId, id(50))] = TrackChoiceEntity(AMY.serverId, AMY.userId, id(50), "jpn", "eng", false, false, false, 1)
        answers(info("direct-play.json"))
        val c = controller(); openAndPlay(c)
        assertEquals("Ben starts with the server's default audio", 1, c.state.value.audioIndex)
        assertEquals(SubtitleChoice.Off, c.state.value.subtitle)
    }

    @Test fun aRememberedChoiceForThisSeriesIsUsedAtTheStart() = runTest {
        trackDao.rows[Triple(session.serverId, session.userId, id(50))] = TrackChoiceEntity(session.serverId, session.userId, id(50), "jpn", "eng", false, false, false, 1)
        answers(info("direct-stream.json"), info("direct-stream.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); openAndPlay(c)
        assertEquals("one more ask for the remembered audio, before anything loads", 2, playbackInfos)
        assertEquals(2, gateway.bodies.last().audioStreamIndex)
        assertEquals(1, engine.loads.size)
        assertEquals(On(3, SubtitleDelivery.FILE), c.state.value.subtitle)
    }

    @Test fun changesAreRememberedForTheSeriesAndAppliedWhenUpNextPlaysTheNextEpisode() = runTest {
        answers(info("direct-play.json"), info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectAudio(2); c.selectSubtitle(3); runCurrent()
        assertEquals(
            TrackChoiceEntity(session.serverId, session.userId, id(50), "jpn", "eng", subtitlesOff = false, subtitleForced = false, subtitleHearingImpaired = false, updatedAt = 7),
            trackDao.rows[Triple(session.serverId, session.userId, id(50))],
        )
        engine.positionMs = runtime; engine.isPlaying = false; engine.emit(EngineEvent.Ended); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
        assertEquals("the next episode starts in Japanese", 1, engine.audioOrdinal)
        assertEquals(2, c.state.value.audioIndex)
        assertEquals(On(3, SubtitleDelivery.FILE), c.state.value.subtitle)
    }

    @Test fun subtitlesTurnedOffAreRemembered() = runTest {
        answers(info("direct-play.json", defaultSubtitle = 4))
        gateway.subtitleFiles[4] = file("styled.ass")
        val c = controller(); started(c)
        assertEquals(On(4, SubtitleDelivery.FILE), c.state.value.subtitle)
        c.selectSubtitle(null); runCurrent()
        assertEquals(SubtitleChoice.Off, c.state.value.subtitle)
        assertTrue(trackDao.rows.values.single().subtitlesOff)
    }

    @Test fun aFilmsChoiceIsNotRemembered() = runTest {
        val film = app.shoalwatch.ui.browse.entry(5)
        saved(film)
        answers(info("direct-play.json"))
        val c = controller()
        c.open(film.item.id, StartFrom.BEGINNING); runCurrent(); engine.startPlaying(); runCurrent()
        c.selectAudio(2); runCurrent()
        assertTrue(trackDao.rows.isEmpty())
    }

    // ---- drawing, delay and problems

    @Test fun cuesFollowThePositionWhilePlaying() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        engine.positionMs = 1_500; advanceTimeBy(50); runCurrent()
        assertEquals(listOf("First cue"), c.cueTexts())
        engine.positionMs = 2_500; advanceTimeBy(50); runCurrent()
        assertEquals(emptyList<String>(), c.cueTexts())
    }

    @Test fun theDelayWorksBothWaysAndSurvivesASeek() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        c.setSubtitleDelay(1_000)
        engine.positionMs = 2_500; advanceTimeBy(50); runCurrent()
        assertEquals("later: the 1.5 s cue at 2.5 s", listOf("First cue"), c.cueTexts())
        c.setSubtitleDelay(-1_000)
        c.seekTo(500); runCurrent()
        engine.positionMs = 500; advanceTimeBy(50); runCurrent()
        assertEquals("earlier: the 1.5 s cue at 0.5 s, after a seek", listOf("First cue"), c.cueTexts())
        assertEquals(-1_000, c.state.value.subtitleDelayMs)
        c.playPause(); runCurrent()
        c.seekTo(4_500); runCurrent()
        assertEquals("paused, a seek still redraws", listOf("Third cue"), c.cueTexts())
    }

    @Test fun theDelayIsOnlyForSubtitlesTheAppDraws() = runTest {
        answers(info("direct-play.json"))
        val c = controller(); started(c)
        c.selectSubtitle(5); runCurrent()
        c.setSubtitleDelay(2_000)
        assertEquals(0, c.state.value.subtitleDelayMs)
    }

    @Test fun aSubtitleFileThatFailsToLoadLeavesPlaybackRunningWithSubtitlesOffAndTheMessage() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = "1\n00:00:01,000 --> 00:00:02,000\nfine\n".toByteArray().copyOf(9 * 1024 * 1024)
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        assertEquals(SubtitleChoice.Off, c.state.value.subtitle)
        assertEquals(SubtitleNotice.UNREADABLE, c.state.value.subtitleNotice)
        assertEquals(Phase.PLAYING, c.state.value.phase)
        assertTrue(engine.isPlaying)
        assertEquals(1, playbackInfos)
        c.dismissSubtitleNotice()
        assertNull(c.state.value.subtitleNotice)
    }

    @Test fun effectsThePhoneCannotDrawAreToldOncePerItem() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        gateway.subtitleFiles[4] = file("effects.ass")
        val c = controller(); started(c)
        c.selectSubtitle(4); runCurrent()
        assertEquals(SubtitleNotice.EFFECTS_NOT_DRAWN, c.state.value.subtitleNotice)
        assertTrue(c.state.value.subtitleEffectsNotDrawn)
        c.dismissSubtitleNotice()
        c.selectSubtitle(3); runCurrent()
        assertFalse(c.state.value.subtitleEffectsNotDrawn)
        c.selectSubtitle(4); runCurrent()
        assertNull("not told twice for the same item", c.state.value.subtitleNotice)
        assertTrue("the offer stays in the menu", c.state.value.subtitleEffectsNotDrawn)
    }

    // ---- the session rule

    @Test fun aSessionChangeDropsTheLoadedCues() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        engine.positionMs = 1_500; advanceTimeBy(50); runCurrent()
        assertEquals(listOf("First cue"), c.cueTexts())
        activePlayback.sessionChanged(AMY); runCurrent()
        assertSame(SubtitleFrame.NONE, c.cues.value)
        assertEquals(PlaybackState.EMPTY, c.state.value)
    }

    @Test fun aSubtitleFileThatArrivesAfterTheEndIsNeverShown() = runTest {
        answers(info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        val gate = CompletableDeferred<Unit>().also { gateway.subtitleGate = it }
        c.selectSubtitle(3); runCurrent()
        c.end(null)
        gate.complete(Unit); engine.positionMs = 1_500; advanceTimeBy(500); runCurrent()
        assertSame(SubtitleFrame.NONE, c.cues.value)
        assertEquals(PlaybackState.EMPTY, c.state.value)
    }

    @Test fun theNextEpisodeStartsWithNoneOfThisOnesCues() = runTest {
        answers(info("direct-play.json"), info("direct-play.json"))
        gateway.subtitleFiles[3] = file("sample.srt")
        val c = controller(); started(c)
        c.selectSubtitle(3); runCurrent()
        c.setSubtitleDelay(1_000)
        engine.positionMs = 2_500; advanceTimeBy(50); runCurrent()
        assertEquals(listOf("First cue"), c.cueTexts())
        val gate = CompletableDeferred<Unit>().also { gateway.infoGate = it }
        c.next(); runCurrent()
        assertSame("nothing of the last episode while the next one resolves", SubtitleFrame.NONE, c.cues.value)
        assertEquals("the delay was that item's", 0, c.state.value.subtitleDelayMs)
        gate.complete(Unit); runCurrent()
        assertEquals(ep2, c.state.value.itemId)
    }
}
