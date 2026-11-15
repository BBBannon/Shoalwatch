package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.PSID
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlaybackInfo -> PlaybackPlan: direct play, then direct stream, then transcode; refusals; hostile answers. Pure. */
class PlaybackDecisionTest {
    private fun request(forceTranscode: Boolean = false, audio: Int? = null, picture: Int? = null, burnIn: Int? = null) =
        PlaybackRequest(ITEM, Quality.MBPS_8, audio, picture, burnIn, forceTranscode)

    private fun info(name: String) = fixtureInfo(name)

    private fun kindOf(request: PlaybackRequest, info: PlaybackInfo): AppError.Kind =
        assertThrows(AppError::class.java) { PlaybackDecision.decide(request, info) }.kind

    private fun only(info: PlaybackInfo, change: (SourceInfo) -> SourceInfo) = info.copy(sources = listOf(change(info.sources.first())))

    // ---- the order

    @Test fun directPlayWhenAllowed() {
        val plan = PlaybackDecision.decide(request(), info("direct-play.json"))
        assertEquals(PlayMethod.DIRECT_PLAY, plan.method)
        assertEquals("/Videos/$ITEM/stream", plan.source.path)
        assertEquals(listOf("static" to "true", "mediaSourceId" to SOURCE, "tag" to "9f0e1d2c3b4a59687766554433221100"), plan.source.query)
        assertFalse(plan.source.hls)
        assertEquals(SOURCE, plan.mediaSourceId)
        assertEquals(PSID, plan.playSessionId)
        assertEquals(emptySet<TranscodeReason>(), plan.transcodeReasons)
        assertEquals(7_200_000L, plan.runtimeMs)
        assertEquals(VideoRange.SDR, plan.videoRange)
        assertTrue(plan.hasSegments)
        assertEquals(listOf(1, 2), plan.audioTracks.map { it.index })
        assertEquals(listOf(3, 4, 5), plan.subtitleTracks.map { it.index })
    }

    @Test fun directStreamUsesTheServersPath() {
        val plan = PlaybackDecision.decide(request(), info("direct-stream.json"))
        assertEquals(PlayMethod.DIRECT_STREAM, plan.method)
        assertTrue(plan.source.path.startsWith("/videos/") && plan.source.hls)
        assertEquals("/videos/$ITEM/master.m3u8", plan.source.path)
        assertTrue(plan.source.query.none { it.first.equals("api_key", ignoreCase = true) })
        assertEquals(VideoRange.HDR10, plan.videoRange)
    }

    @Test fun transcodeCarriesItsReasons() {
        val plan = PlaybackDecision.decide(request(), info("transcode.json"))
        assertEquals(PlayMethod.TRANSCODE, plan.method)
        // The fixture names four reasons; SecondaryAudioNotSupported is one this app does not explain.
        assertEquals(setOf(TranscodeReason.CONTAINER, TranscodeReason.VIDEO_RANGE, TranscodeReason.AUDIO_CHANNELS, TranscodeReason.OTHER), plan.transcodeReasons)
        assertTrue(plan.source.hls)
        assertEquals(VideoRange.DOVI_WITH_HDR10, plan.videoRange)
    }

    @Test fun forcedTranscodeNeverDirectPlays() =
        assertEquals(PlayMethod.TRANSCODE, PlaybackDecision.decide(request(forceTranscode = true), info("transcode.json")).method)

    @Test fun forcedTranscodeNeverDirectStreamsEither() {
        val plan = PlaybackDecision.decide(request(forceTranscode = true), info("direct-stream.json"))
        assertEquals(PlayMethod.TRANSCODE, plan.method)
        assertTrue(plan.request.forceTranscode)
    }

    @Test fun aBurnInSkipsDirectPlayAndDirectStream() {
        val both = only(info("direct-play.json")) { it.copy(transcoding = info("transcode.json").sources.first().transcoding, supportsDirectStream = true) }
        assertEquals(PlayMethod.TRANSCODE, PlaybackDecision.decide(request(burnIn = 4), both).method)
    }

    @Test fun aPictureSubtitleInTheStreamStillDirectPlays() =
        assertEquals(PlayMethod.DIRECT_PLAY, PlaybackDecision.decide(request(picture = 5), info("direct-play.json")).method)

    @Test fun forcedWithOnlyDirectPlayOfferedIsNoCompatibleStream() =
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(forceTranscode = true), info("direct-play.json")))

    @Test fun theFirstSourceIsUsed() {
        val first = info("direct-play.json").sources.first()
        val other = first.copy(id = "secondsource1", supportsDirectPlay = false)
        assertEquals(SOURCE, PlaybackDecision.decide(request(), PlaybackInfo(PSID, listOf(first, other), null)).mediaSourceId)
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), PlaybackInfo(PSID, listOf(other, first), null)))
    }

    // ---- refusals and nothing usable

    @Test fun refusalsThrowTheirKind() {
        val e = assertThrows(AppError::class.java) { PlaybackDecision.decide(request(), info("refused.json")) }
        assertEquals(AppError.Kind.PLAYBACK_NOT_ALLOWED, e.kind)
    }

    @Test fun everyRefusalKeepsItsKindEvenWithSources() {
        val sources = info("direct-play.json").sources
        for (kind in listOf(AppError.Kind.PLAYBACK_NOT_ALLOWED, AppError.Kind.NO_COMPATIBLE_STREAM, AppError.Kind.TOO_MANY_STREAMS)) {
            assertEquals(kind, kindOf(request(), PlaybackInfo(PSID, sources, kind)))
        }
    }

    @Test fun noUsablePathIsNoCompatibleStream() {
        val none = only(info("transcode.json")) { it.copy(supportsDirectPlay = false, transcoding = null) }
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), none))
        // Direct stream allowed but no server path: no static-stream guess.
        val streamOnly = only(info("direct-stream.json")) { it.copy(transcoding = null) }
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), streamOnly))
    }

    @Test fun noSourcesIsNoCompatibleStream() =
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), PlaybackInfo(PSID, emptyList(), null)))

    // ---- tracks

    @Test fun defaultAudioIsTheServersWhenNoneAsked() {
        assertEquals(1, PlaybackDecision.decide(request(), info("direct-play.json")).audioIndex)
        val noDefault = only(info("direct-play.json")) { it.copy(defaultAudioIndex = null) }
        assertEquals(1, PlaybackDecision.decide(request(), noDefault).audioIndex)
        val noAudio = only(info("direct-play.json")) { it.copy(defaultAudioIndex = null, audio = emptyList()) }
        assertNull(PlaybackDecision.decide(request(), noAudio).audioIndex)
    }

    @Test fun anAskedAudioTrackIsKeptOnlyIfItExists() {
        assertEquals(2, PlaybackDecision.decide(request(audio = 2), info("direct-play.json")).audioIndex)
        assertEquals(1, PlaybackDecision.decide(request(audio = 9), info("direct-play.json")).audioIndex)
    }

    // ---- needsForcedTranscode

    @Test fun aBurnInWithNoServerPathNeedsAForcedTranscode() {
        assertTrue(PlaybackDecision.needsForcedTranscode(request(burnIn = 4), info("direct-play.json")))
        assertFalse(PlaybackDecision.needsForcedTranscode(request(burnIn = 4), info("transcode.json")))
        assertFalse(PlaybackDecision.needsForcedTranscode(request(), info("direct-play.json")))
        assertFalse(PlaybackDecision.needsForcedTranscode(request(burnIn = 4, forceTranscode = true), info("direct-play.json")))
        assertFalse(PlaybackDecision.needsForcedTranscode(request(burnIn = 4), info("refused.json")))
    }

    // ---- hostile answers cannot produce an unvalidated id or URL

    @Test fun theHostileFixtureGivesNoPlan() =
        // Its usable-looking source has an absolute TranscodingUrl (refused by the mapper) and no direct play.
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), info("hostile.json")))

    @Test fun aHandBuiltSourceWithAnUnsafeIdIsRefused() {
        val bad = only(info("direct-play.json")) { it.copy(id = "../evil") }
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), bad))
    }

    @Test fun unsafeSessionIdsAndTagsAreDropped() {
        val bad = only(info("direct-play.json")) { it.copy(eTag = "tag&api_key=1") }.copy(playSessionId = "../../x")
        val plan = PlaybackDecision.decide(request(), bad)
        assertNull(plan.playSessionId)
        assertNull(plan.eTag)
        assertEquals(listOf("static" to "true", "mediaSourceId" to SOURCE), plan.source.query)
    }

    @Test fun anUnvalidatedItemIdIsNotFound() {
        val e = assertThrows(AppError::class.java) {
            PlaybackDecision.decide(PlaybackRequest("../Users", Quality.MBPS_8), info("direct-play.json"))
        }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
    }

    @Test fun aHandBuiltServerPathOutsideVideosIsRefused() {
        val paths = listOf("/System/Info", "/videos", "/videos/../Users", "https://evil.example/videos/x", "/videos/a b", "//evil/videos/x")
        for (p in paths) {
            val bad = only(info("transcode.json")) { it.copy(transcoding = StreamSource(p, emptyList(), hls = true)) }
            assertEquals(p, AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), bad))
        }
        val keyed = only(info("transcode.json")) { it.copy(transcoding = StreamSource("/videos/x/master.m3u8", listOf("ApiKey" to "t"), hls = true)) }
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, kindOf(request(), keyed))
    }

    @Test fun thePlanNeverPrintsItsSource() =
        assertFalse(PlaybackDecision.decide(request(), info("transcode.json")).toString().contains("videos"))
}
