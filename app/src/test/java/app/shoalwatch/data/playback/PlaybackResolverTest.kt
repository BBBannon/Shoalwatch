package app.shoalwatch.data.playback

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ask, decide, route: the body sent, the one forced-transcode retry, the one fallback, and failures. */
class PlaybackResolverTest {
    private val session = Session(S1, U1)
    private val gateway = FakePlaybackGateway()
    private val caps = DeviceCapabilities(
        video = setOf(VideoCodec.H264), audio = setOf("aac"), h264MaxLevel = 41, hevcMain10 = false, vp9Profile2 = false, av1Main10 = false,
        decoderHdr = emptyMap(), dolbyVisionProfiles = emptySet(), display = emptySet(),
    )
    private val routesMade = mutableListOf<Session>()
    private val routes: suspend (Session) -> MediaRoute = { s -> routesMade += s; PlaybackFakes.route(s) }

    private fun resolver(io: kotlinx.coroutines.CoroutineDispatcher) = PlaybackResolver(gateway, { caps }, routes, io)

    private fun request(quality: Quality = Quality.MBPS_8, audio: Int? = null, picture: Int? = null, burnIn: Int? = null, force: Boolean = false) =
        PlaybackRequest(ITEM, quality, audio, picture, burnIn, force)

    private suspend fun failureOf(block: suspend () -> Unit): AppError {
        try {
            block()
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    @Test fun aPlainRequestSendsNoSubtitleAndAllowsEverything() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        val resolved = resolver(StandardTestDispatcher(testScheduler)).resolve(session, request())
        val body = gateway.bodies.single()
        assertEquals(-1, body.subtitleStreamIndex)
        assertEquals(null, body.audioStreamIndex)
        assertTrue(body.enableDirectPlay && body.enableDirectStream && body.enableTranscoding)
        assertTrue(body.allowVideoStreamCopy && body.allowAudioStreamCopy)
        assertFalse(body.alwaysBurnInSubtitleWhenTranscoding)
        assertEquals(U1, body.userId)
        assertEquals(PlayMethod.DIRECT_PLAY, resolved.plan.method)
        assertEquals(listOf(session), routesMade)
        assertSame(session, resolved.route!!.session)
    }

    @Test fun checkOriginalAsksForDirectPlayOnlyAtMaximumAndMakesNoRoute() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        val info = resolver(StandardTestDispatcher(testScheduler)).checkOriginal(session, ITEM)
        val body = gateway.bodies.single()
        assertEquals(listOf("playbackInfo:$ITEM"), gateway.calls)
        assertTrue(body.enableDirectPlay)
        assertFalse("no conversion is set up", body.enableDirectStream || body.enableTranscoding)
        assertFalse(body.allowVideoStreamCopy || body.allowAudioStreamCopy || body.alwaysBurnInSubtitleWhenTranscoding)
        assertEquals(Quality.MAXIMUM.maxBitrate, body.maxStreamingBitrate)
        assertEquals(Quality.MAXIMUM.maxBitrate, body.deviceProfile.maxStreamingBitrate)
        assertEquals(-1, body.subtitleStreamIndex)
        assertEquals(null, body.audioStreamIndex)
        assertEquals(U1, body.userId)
        assertEquals(1, info.sources.size)
        assertTrue("checking makes no route", routesMade.isEmpty())
    }

    @Test fun checkOriginalPassesTheGatewaysFailureOn() = runTest {
        gateway.failWith = AppError.of(AppError.Kind.OFFLINE)
        assertEquals(AppError.Kind.OFFLINE, failureOf { resolver(StandardTestDispatcher(testScheduler)).checkOriginal(session, ITEM) }.kind)
    }

    @Test fun aBurnInCarriesTheQualitysPictureSizeCeilingAsAForcedTranscodeDoes() = runTest {
        gateway.answer(fixtureInfo("transcode.json"), fixtureInfo("transcode.json"))
        val r = resolver(StandardTestDispatcher(testScheduler))
        r.resolve(session, request(quality = Quality.MBPS_4, burnIn = 4), burnInFormat = "ass")
        r.resolve(session, request(quality = Quality.MBPS_4, force = true))
        fun ceiling(i: Int) = gateway.bodies[i].deviceProfile.codecProfiles.singleOrNull { it.type == "Video" && it.codec == null }
            ?.conditions?.map { it.property to it.value }
        assertEquals(listOf("Width" to "1280", "Height" to "720"), ceiling(0))
        assertEquals(ceiling(1), ceiling(0))
    }

    @Test fun anAskedAudioTrackIsSent() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(audio = 2))
        assertEquals(2, gateway.bodies.single().audioStreamIndex)
    }

    @Test fun aPictureIndexIsSentAsItself() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(picture = 5))
        val body = gateway.bodies.single()
        assertEquals(5, body.subtitleStreamIndex)
        assertTrue(body.enableDirectPlay)
    }

    @Test fun aBurnInTurnsDirectPlayOffAndAsksForBurnIn() = runTest {
        gateway.answer(fixtureInfo("transcode.json"))
        resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(burnIn = 4), burnInFormat = "ass")
        val body = gateway.bodies.single()
        assertEquals(4, body.subtitleStreamIndex)
        assertFalse(body.enableDirectPlay)
        assertFalse(body.enableDirectStream)
        assertTrue(body.alwaysBurnInSubtitleWhenTranscoding)
        assertEquals("Encode", body.deviceProfile.subtitleProfiles.single { it.format == "ass" }.method)
    }

    @Test fun aBurnInTakesPrecedenceOverAPictureTrack() = runTest {
        gateway.answer(fixtureInfo("transcode.json"))
        resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(picture = 5, burnIn = 4))
        assertEquals(4, gateway.bodies.single().subtitleStreamIndex)
    }

    @Test fun theQualityReachesTheBody() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(quality = Quality.MBPS_1_5))
        val body = gateway.bodies.single()
        assertEquals(1_500_000, body.maxStreamingBitrate)
        assertEquals(1_500_000, body.deviceProfile.maxStreamingBitrate)
    }

    @Test fun aBurnInWithNoServerPathAsksExactlyOnceMoreAsAForcedTranscode() = runTest {
        // Both answers offer only direct play: the second ask is made, and no third.
        gateway.answer(fixtureInfo("direct-play.json"), fixtureInfo("direct-play.json"), fixtureInfo("transcode.json"))
        val e = failureOf { resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(burnIn = 4)) }
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, e.kind)
        assertEquals(2, gateway.bodies.size)
        val second = gateway.bodies[1]
        assertFalse(second.enableDirectPlay)
        assertFalse(second.enableDirectStream)
        assertTrue(routesMade.isEmpty())
    }

    @Test fun theForcedAskIsTheOneThatPlays() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"), fixtureInfo("transcode.json"))
        val resolved = resolver(StandardTestDispatcher(testScheduler)).resolve(session, request(burnIn = 4))
        assertEquals(PlayMethod.TRANSCODE, resolved.plan.method)
        assertTrue(resolved.plan.request.forceTranscode)
        assertEquals(2, gateway.bodies.size)
    }

    @Test fun fallBackSendsExactlyOneForcedRequestAndReturnsATranscode() = runTest {
        val resolver = resolver(StandardTestDispatcher(testScheduler))
        gateway.answer(fixtureInfo("direct-play.json"))
        val first = resolver.resolve(session, request(audio = 2))
        gateway.answer(fixtureInfo("transcode.json"), fixtureInfo("transcode.json"))
        val fallen = resolver.fallBack(session, first.plan)
        assertEquals(2, gateway.bodies.size)
        val body = gateway.bodies[1]
        assertFalse(body.enableDirectPlay)
        assertFalse(body.enableDirectStream)
        assertEquals(2, body.audioStreamIndex)
        assertEquals(PlayMethod.TRANSCODE, fallen.plan.method)
        assertTrue(fallen.plan.request.forceTranscode)
        assertEquals(2, routesMade.size)
    }

    @Test fun fallBackForABurnInKeepsTheTracksFormat() = runTest {
        val resolver = resolver(StandardTestDispatcher(testScheduler))
        gateway.answer(fixtureInfo("transcode.json"))
        val first = resolver.resolve(session, request(burnIn = 4), burnInFormat = "ass")
        // The burn-in plan's tracks come from the transcode answer (no index 4), so the format falls back to the one in the plan's tracks.
        gateway.answer(fixtureInfo("transcode.json"))
        resolver.fallBack(session, first.plan.copy(subtitleTracks = fixtureInfo("direct-play.json").sources.first().subtitles))
        assertEquals("Encode", gateway.bodies[1].deviceProfile.subtitleProfiles.single { it.format == "ass" }.method)
    }

    @Test fun offlineFromTheGatewayPropagatesUnchangedAndNoRouteIsMade() = runTest {
        val offline = AppError.of(AppError.Kind.OFFLINE)
        gateway.failWith = offline
        val e = failureOf { resolver(StandardTestDispatcher(testScheduler)).resolve(session, request()) }
        assertSame(offline, e)
        assertTrue(routesMade.isEmpty())
    }

    @Test fun aRefusalMakesNoRoute() = runTest {
        gateway.answer(fixtureInfo("refused.json"))
        val e = failureOf { resolver(StandardTestDispatcher(testScheduler)).resolve(session, request()) }
        assertEquals(AppError.Kind.PLAYBACK_NOT_ALLOWED, e.kind)
        assertTrue(routesMade.isEmpty())
        assertEquals(1, gateway.bodies.size)
    }

    @Test fun aRouteFailurePropagates() = runTest {
        gateway.answer(fixtureInfo("direct-play.json"))
        val resolver = PlaybackResolver(gateway, { caps }, { throw AppError.of(AppError.Kind.NOT_SIGNED_IN) }, StandardTestDispatcher(testScheduler))
        assertEquals(AppError.Kind.NOT_SIGNED_IN, failureOf { resolver.resolve(session, request()) }.kind)
    }

    @Test fun rerouteKeepsThePlanAndAsksTheServerNothing() = runTest {
        val resolver = resolver(StandardTestDispatcher(testScheduler))
        gateway.answer(fixtureInfo("direct-play.json"))
        val first = resolver.resolve(session, request())
        val again = resolver.reroute(first)
        assertSame(first.plan, again.plan)
        assertEquals(1, gateway.bodies.size)
        assertEquals(2, routesMade.size)
        assertFalse(first.route === again.route)
    }

    @Test fun resolvedNeverPrintsItsHalves() = runTest {
        gateway.answer(fixtureInfo("transcode.json"))
        val text = resolver(StandardTestDispatcher(testScheduler)).resolve(session, request()).toString()
        assertFalse(text.contains("videos") || text.contains("media.example") || text.contains(ITEM))
    }
}
