package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.SubtitleChoice.On
import app.shoalwatch.data.playback.api.SubtitleProfileDto
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8 review m-2 (final review N-2): a picture track shown with an answer whose request does not carry it is asked for
 * again through `TrackRules.step`, so an external picture track (the stream cannot carry it) is asked for burned in with a
 * forced conversion, never as a picture index the server would leave out of a direct play.
 */
internal class TrackControlCarriesTest : PlaybackControllerTestBase() {
    private val caps = DeviceCapabilities(
        video = setOf(VideoCodec.H264), audio = setOf("aac"), h264MaxLevel = 41, hevcMain10 = false, vp9Profile2 = false, av1Main10 = false,
        decoderHdr = emptyMap(), dolbyVisionProfiles = emptySet(), display = emptySet(),
    )
    private val external = SubtitleTrack(6, "PGS file", "eng", "pgssub", SubtitleKind.PICTURE, false, false, false, external = true)
    private var reload: (suspend () -> Resolved)? = null

    private fun TestScope.control(current: () -> PlayingItem?) = TrackControl(
        session, engine, PlaybackResolver(gateway, { caps }, { PlaybackFakes.route(it) }, StandardTestDispatcher(testScheduler)), trackPrefs,
        SubtitleLoader(gateway, StandardTestDispatcher(testScheduler)), { captions }, backgroundScope, backgroundScope,
        object : TrackControl.Host {
            override val current get() = current()
            override fun reload(c: PlayingItem, how: suspend () -> Resolved) { reload = how }
            override fun tracksChanged(c: PlayingItem) = Unit
            override fun publish() = Unit
            override fun cuePositionMs(c: PlayingItem) = 0L
            override val reloading = false
        },
    )

    @Test fun anExternalPictureTrackAnsweredWithoutItsBurnInIsAskedForBurnedInWithAForcedConversion() = runTest {
        val info = fixtureInfo("direct-play.json").let { i -> i.copy(sources = listOf(i.sources.single().copy(subtitles = listOf(external)))) }
        // An answer built from a request that did not ask for the track (an older request).
        val plan = PlaybackDecision.decide(PlaybackRequest(PlaybackFakes.ITEM, Quality.MAXIMUM), info)
        val c = PlayingItem(PlaybackFakes.ITEM).apply { resolved = Resolved(plan, PlaybackFakes.route(session)); subtitle = On(6, SubtitleDelivery.BURNED_IN) }
        val control = control { c }
        control.prepare(c, plan); runCurrent()
        assertNotNull("asked again", reload)
        val how = reload!!
        gateway.infos.clear(); gateway.answer(fixtureInfo("transcode.json"))
        backgroundScope.launch { how() }; runCurrent()
        val body = gateway.bodies.last()
        assertEquals(6, body.subtitleStreamIndex)
        assertTrue("burned in", body.alwaysBurnInSubtitleWhenTranscoding)
        assertTrue("its format is Encode only", SubtitleProfileDto("pgssub", "Encode") in body.deviceProfile.subtitleProfiles)
        assertFalse("not offered in the stream", SubtitleProfileDto("pgssub", "Embed") in body.deviceProfile.subtitleProfiles)
        assertFalse("a forced conversion", body.enableDirectPlay || body.enableDirectStream)
        assertEquals(6, control.wanted(c).burnInSubtitleIndex)
    }
}
