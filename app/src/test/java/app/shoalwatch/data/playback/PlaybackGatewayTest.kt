package app.shoalwatch.data.playback

import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.SourceInfo
import app.shoalwatch.data.playback.api.VideoInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each playback endpoint: method, path, the exact JSON field names from the 12.4.0 reference, and the mapped result. */
class PlaybackGatewayTest : PlaybackGatewayTestBase() {

    // ---- PlaybackInfo

    @Test fun playbackInfoPostsTheReferenceBodyToTheItemsRoute() {
        web.enqueue(json(fixture("direct-play.json")))
        runBlocking { gateway.playbackInfo(session, ITEM, body().copy(audioStreamIndex = 2)) }
        val request = nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/Items/$ITEM/PlaybackInfo", request.requestUrl!!.encodedPath)
        assertEquals(setOf("userId"), request.requestUrl!!.queryParameterNames)
        assertEquals(U1, request.requestUrl!!.queryParameter("userId"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val json = bodyOf(request)
        assertEquals(
            setOf("UserId", "MaxStreamingBitrate", "AudioStreamIndex", "SubtitleStreamIndex", "DeviceProfile", "EnableDirectPlay", "EnableDirectStream",
                "EnableTranscoding", "AllowVideoStreamCopy", "AllowAudioStreamCopy", "AlwaysBurnInSubtitleWhenTranscoding"),
            json.keys,
        )
        assertEquals(JsonPrimitive(U1), json["UserId"])
        assertEquals(JsonPrimitive(8_000_000), json["MaxStreamingBitrate"])
        assertEquals(JsonPrimitive(2), json["AudioStreamIndex"])
        assertEquals(JsonPrimitive(-1), json["SubtitleStreamIndex"])
        assertEquals(JsonPrimitive(true), json["EnableTranscoding"])
        assertEquals("Shoalwatch", json["DeviceProfile"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
    }

    @Test fun aNullIndexIsLeftOutOfTheBody() {
        web.enqueue(json(fixture("direct-play.json")))
        runBlocking { gateway.playbackInfo(session, ITEM, body()) }
        assertFalse("AudioStreamIndex" in bodyOf(nextRequest()).keys)
    }

    @Test fun anItemIdIsNormalisedAndABadOneSendsNothing() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.playbackInfo(session, "../System", body()) }.kind)
        assertEquals(0, web.requestCount)
        web.enqueue(json(fixture("direct-play.json")))
        runBlocking { gateway.playbackInfo(session, "01234567-89AB-CDEF-0123-456789ABCDEF", body()) }
        assertEquals("/Items/$ITEM/PlaybackInfo", nextRequest().requestUrl!!.encodedPath)
    }

    @Test fun directPlayFixtureMapsEveryField() {
        val expected = PlaybackInfo(
            playSessionId = PSID,
            sources = listOf(
                SourceInfo(
                    id = SOURCE, container = "mkv", eTag = "9f0e1d2c3b4a59687766554433221100", runtimeMs = 7_200_000, bitrate = 8_200_000,
                    supportsDirectPlay = true, supportsDirectStream = true, supportsTranscoding = true, transcoding = null, transcodeReasons = emptySet(),
                    video = VideoInfo("h264", VideoRange.SDR, 8, 1920, 1080, null),
                    audio = listOf(
                        AudioTrack(1, "English - AAC - Stereo - Default", "eng", "aac", 2, isDefault = true),
                        AudioTrack(2, "Commentaire", "fre", "ac3", 6, isDefault = false),
                    ),
                    subtitles = listOf(
                        SubtitleTrack(3, "English - SUBRIP - External", "eng", "subrip", SubtitleKind.TEXT, forced = false, hearingImpaired = true, isDefault = false, external = true, fileFormat = "srt"),
                        SubtitleTrack(4, "Japanese - ASS", "jpn", "ass", SubtitleKind.STYLED_TEXT, forced = false, hearingImpaired = false, isDefault = true, external = false, fileFormat = "ass"),
                        SubtitleTrack(5, "English - PGSSUB - Forced", "eng", "pgssub", SubtitleKind.PICTURE, forced = true, hearingImpaired = false, isDefault = false, external = false),
                    ),
                    defaultAudioIndex = 1, defaultSubtitleIndex = 4, hasSegments = true,
                ),
            ),
            refusal = null,
        )
        assertEquals(expected, info("direct-play.json"))
    }

    @Test fun directStreamFixtureKeepsHdrDetailsAndDropsTheNoneSubtitleIndex() {
        val source = info("direct-stream.json").sources.single()
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", source.container)
        assertFalse(source.supportsDirectPlay)
        assertTrue(source.supportsDirectStream)
        assertEquals(VideoInfo("hevc", VideoRange.HDR10, 10, 3840, 2160, null), source.video)
        assertEquals(listOf(AudioTrack(1, "English - Dolby Digital+ - 5.1", "eng", "eac3", 6, true)), source.audio)
        assertNull(source.defaultSubtitleIndex)
        assertFalse(source.hasSegments)
        assertEquals(3_600_000L, source.runtimeMs)
    }

    @Test fun transcodeFixtureKeepsTheServersUrlWithoutTheKeyAndReadsTheReasons() {
        val info = info("transcode.json")
        val source = info.sources.single()
        val url = source.transcoding!!
        assertTrue(url.hls)
        assertEquals("/videos/$ITEM/master.m3u8", url.path)
        assertTrue(url.query.none { it.first.equals("api_key", ignoreCase = true) || "MOCKLEAK" in it.second })
        assertEquals("ContainerNotSupported,VideoRangeTypeNotSupported,AudioChannelsNotSupported,SecondaryAudioNotSupported", url.query.toMap()["TranscodeReasons"])
        assertEquals(PSID, url.query.toMap()["PlaySessionId"])
        assertEquals(setOf(TranscodeReason.CONTAINER, TranscodeReason.VIDEO_RANGE, TranscodeReason.AUDIO_CHANNELS, TranscodeReason.OTHER), source.transcodeReasons)
        assertEquals(VideoInfo("hevc", VideoRange.DOVI_WITH_HDR10, 10, 3840, 2160, 8), source.video)
    }

    @Test fun everyNamedTranscodeReasonMaps() {
        val names = mapOf(
            "ContainerNotSupported" to TranscodeReason.CONTAINER, "VideoCodecNotSupported" to TranscodeReason.VIDEO_CODEC,
            "AudioCodecNotSupported" to TranscodeReason.AUDIO_CODEC, "SubtitleCodecNotSupported" to TranscodeReason.SUBTITLE,
            "ContainerBitrateExceedsLimit" to TranscodeReason.BITRATE, "VideoBitrateNotSupported" to TranscodeReason.BITRATE,
            "AudioBitrateNotSupported" to TranscodeReason.BITRATE, "VideoRangeTypeNotSupported" to TranscodeReason.VIDEO_RANGE,
            "VideoResolutionNotSupported" to TranscodeReason.RESOLUTION, "AudioChannelsNotSupported" to TranscodeReason.AUDIO_CHANNELS,
            "DirectPlayError" to TranscodeReason.DIRECT_PLAY_ERROR, "VideoLevelNotSupported" to TranscodeReason.OTHER, "MadeUp" to TranscodeReason.OTHER,
        )
        for ((name, reason) in names) {
            val raw = """{"MediaSources":[{"Id":"abc","TranscodingUrl":"/videos/$ITEM/master.m3u8?TranscodeReasons=$name","TranscodingSubProtocol":"hls"}]}"""
            assertEquals(name, setOf(reason), infoOf(raw).sources.single().transcodeReasons)
        }
    }

    @Test fun eachErrorCodeIsARefusal() {
        assertEquals(PlaybackInfo(null, emptyList(), AppError.Kind.PLAYBACK_NOT_ALLOWED), info("refused.json"))
        assertEquals(AppError.Kind.NO_COMPATIBLE_STREAM, infoOf("""{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}""").refusal)
        assertEquals(AppError.Kind.TOO_MANY_STREAMS, infoOf("""{"MediaSources":[],"ErrorCode":"RateLimitExceeded"}""").refusal)
        assertNull(infoOf("""{"MediaSources":[],"ErrorCode":"SomethingNew"}""").refusal)
        assertEquals(PlaybackInfo(null, emptyList(), null), infoOf("{}"))
    }

    @Test fun theFourPlaybackErrorsHavePlainMessages() {
        assertEquals("Your account isn't allowed to play this. Ask the server's owner.", AppError.of(AppError.Kind.PLAYBACK_NOT_ALLOWED).userMessage)
        assertEquals("This can't be played on this phone, and the server couldn't convert it.", AppError.of(AppError.Kind.NO_COMPATIBLE_STREAM).userMessage)
        assertEquals("Too many people are watching on this server right now. Try again later.", AppError.of(AppError.Kind.TOO_MANY_STREAMS).userMessage)
        assertEquals("Playback stopped because of a problem with this video.", AppError.of(AppError.Kind.PLAYBACK_FAILED).userMessage)
    }

    // ---- reports

    private val report = PlaybackReport(ITEM, SOURCE, PSID, PlayMethod.TRANSCODE, positionMs = 61_500, paused = true, audioIndex = 1, subtitleIndex = null)

    @Test fun startAndProgressPostTheReferenceFields() {
        for ((path, send) in listOf<Pair<String, suspend () -> Unit>>(
            "/Sessions/Playing" to { gateway.reportStart(session, report) },
            "/Sessions/Playing/Progress" to { gateway.reportProgress(session, report) },
        )) {
            web.enqueue(MockResponse().setResponseCode(204))
            runBlocking { send() }
            val request = nextRequest()
            assertEquals("POST", request.method)
            assertEquals(path, request.requestUrl!!.encodedPath)
            assertEquals(emptySet<String>(), request.requestUrl!!.queryParameterNames)
            val json = bodyOf(request)
            assertEquals(
                setOf("ItemId", "MediaSourceId", "PlaySessionId", "PositionTicks", "IsPaused", "IsMuted", "CanSeek", "AudioStreamIndex", "PlayMethod", "RepeatMode", "PlaybackOrder"),
                json.keys,
            )
            assertEquals(JsonPrimitive(ITEM), json["ItemId"])
            assertEquals(JsonPrimitive(SOURCE), json["MediaSourceId"])
            assertEquals(JsonPrimitive(PSID), json["PlaySessionId"])
            assertEquals(JsonPrimitive(615_000_000L), json["PositionTicks"])
            assertEquals(JsonPrimitive(true), json["IsPaused"])
            assertEquals(JsonPrimitive(false), json["IsMuted"])
            assertEquals(JsonPrimitive(true), json["CanSeek"])
            assertEquals(JsonPrimitive(1), json["AudioStreamIndex"])
            assertEquals(JsonPrimitive("Transcode"), json["PlayMethod"])
            assertEquals(JsonPrimitive("RepeatNone"), json["RepeatMode"])
            assertEquals(JsonPrimitive("Default"), json["PlaybackOrder"])
        }
    }

    @Test fun playMethodWireNamesAreTheReferenceEnum() {
        for ((method, wire) in listOf(PlayMethod.DIRECT_PLAY to "DirectPlay", PlayMethod.DIRECT_STREAM to "DirectStream", PlayMethod.TRANSCODE to "Transcode")) {
            web.enqueue(MockResponse().setResponseCode(204))
            runBlocking { gateway.reportProgress(session, report.copy(method = method, subtitleIndex = 3)) }
            val json = bodyOf(nextRequest())
            assertEquals(JsonPrimitive(wire), json["PlayMethod"])
            assertEquals(JsonPrimitive(3), json["SubtitleStreamIndex"])
        }
    }

    @Test fun stoppedPostsOnlyTheStopFields() {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportStopped(session, report.copy(failed = true)) }
        val request = nextRequest()
        assertEquals("/Sessions/Playing/Stopped", request.requestUrl!!.encodedPath)
        val json = bodyOf(request)
        assertEquals(setOf("ItemId", "MediaSourceId", "PlaySessionId", "PositionTicks", "Failed"), json.keys)
        assertEquals(JsonPrimitive(true), json["Failed"])
        assertEquals(JsonPrimitive(615_000_000L), json["PositionTicks"])
    }

    @Test fun aReportWithoutAPlaySessionLeavesItOut() {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportStopped(session, report.copy(playSessionId = null)) }
        assertFalse("PlaySessionId" in bodyOf(nextRequest()).keys)
    }

    @Test fun aReportWithBadIdsSendsNothing() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.reportStart(session, report.copy(itemId = "x")) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.reportProgress(session, report.copy(mediaSourceId = "a/b")) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.reportStopped(session, report.copy(playSessionId = "a&b")) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aNegativePositionIsSentAsZero() {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportProgress(session, report.copy(positionMs = -5)) }
        assertEquals(JsonPrimitive(0L), bodyOf(nextRequest())["PositionTicks"])
    }

    @Test fun aFailedReportIsTheMappedError() {
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.reportProgress(session, report) }.kind)
    }

    @Test fun pingNamesThePlaySession() {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.ping(session, PSID) }
        val request = nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/Sessions/Playing/Ping?playSessionId=$PSID", request.path)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.ping(session, "x&api_key=1") }.kind)
        assertEquals(1, web.requestCount)
    }

    // ---- segments

    /** No `includeSegmentTypes`: the reference gives it no style, so how a server binds one value is unknown; the mapper filters instead. */
    @Test fun segmentsAskForEveryKindAndKeepTheFive() {
        web.enqueue(json(
            """{"Items":[
              {"Id":"11111111111111111111111111111111","ItemId":"$ITEM","Type":"Outro","StartTicks":80000000,"EndTicks":100000000},
              {"Id":"22222222222222222222222222222222","ItemId":"$ITEM","Type":"Intro","StartTicks":10000000,"EndTicks":30000000},
              {"Type":"Recap","StartTicks":0,"EndTicks":5000000},
              {"Type":"Preview","StartTicks":100000000,"EndTicks":110000000},
              {"Type":"Commercial","StartTicks":40000000,"EndTicks":45000000},
              {"Type":"Unknown","StartTicks":50000000,"EndTicks":60000000},
              {"Type":"SomethingNew","StartTicks":60000000,"EndTicks":70000000}
            ],"TotalRecordCount":7,"StartIndex":0}""",
        ))
        val segments = runBlocking { gateway.segments(session, ITEM.uppercase()) }
        val request = nextRequest()
        assertEquals("GET", request.method)
        assertEquals("/MediaSegments/$ITEM", request.requestUrl!!.encodedPath)
        assertEquals(emptySet<String>(), request.requestUrl!!.queryParameterNames)
        assertEquals(
            listOf(
                MediaSegment(SegmentKind.RECAP, 0, 500), MediaSegment(SegmentKind.INTRO, 1_000, 3_000), MediaSegment(SegmentKind.COMMERCIAL, 4_000, 4_500),
                MediaSegment(SegmentKind.OUTRO, 8_000, 10_000), MediaSegment(SegmentKind.PREVIEW, 10_000, 11_000),
            ),
            segments,
        )
    }

    // ---- subtitle files

    private fun subtitle(contentType: String?, body: String = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"): ByteArray {
        web.enqueue(MockResponse().apply { contentType?.let { setHeader("Content-Type", it) } }.setBody(body))
        return runBlocking { gateway.subtitleFile(session, ITEM, SOURCE, 2, "srt") }
    }

    @Test fun subtitleFileGetsTheReferenceRouteAndAcceptsText() {
        val bytes = subtitle("text/plain; charset=utf-8")
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n", bytes.decodeToString())
        val request = nextRequest()
        assertEquals("GET", request.method)
        assertEquals("/Videos/$ITEM/$SOURCE/Subtitles/2/Stream.srt", request.path)
        for (type in listOf("application/x-subrip", "text/vtt", "TEXT/X-SSA", "text/plain")) assertTrue(type, subtitle(type).isNotEmpty())
    }

    /** Jellyfin names SRT `subrip` and VTT `webvtt`; each text track's file format is one the subtitle route takes, and the path is exact. */
    @Test fun everyTextTrackFromPlaybackInfoFetchesItsFile() {
        val tracks = info("subtitles.json").sources.single().subtitles
        assertEquals(
            listOf(2 to "srt", 3 to "vtt", 4 to "ass", 5 to "ssa", 6 to null, 7 to null),
            tracks.map { it.index to it.fileFormat },
        )
        for (track in tracks.filter { it.fileFormat != null }) {
            web.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("x"))
            runBlocking { gateway.subtitleFile(session, ITEM, SOURCE, track.index, track.fileFormat!!) }
            assertEquals("/Videos/$ITEM/$SOURCE/Subtitles/${track.index}/Stream.${track.fileFormat}", nextRequest().path)
        }
    }

    @Test fun subtitleFileRefusesOtherTypes() {
        for (type in listOf("application/octet-stream", "text/html", "text/html; charset=utf-8", "application/json", "image/png", null)) {
            web.enqueue(MockResponse().apply { type?.let { setHeader("Content-Type", it) } }.setBody("x"))
            assertEquals(type.toString(), AppError.Kind.SERVER_ERROR, failure { gateway.subtitleFile(session, ITEM, SOURCE, 2, "srt") }.kind)
        }
    }

    @Test fun subtitleFileRefusesAnythingOverEightMebibytes() {
        val limit = 8 * 1024 * 1024
        assertEquals(limit, subtitle("text/plain", "a".repeat(limit)).size)
        web.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("a".repeat(limit + 1)))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.subtitleFile(session, ITEM, SOURCE, 2, "srt") }.kind)
    }

    @Test fun subtitleFileRefusesBadArgumentsBeforeSending() {
        for (call in listOf<suspend () -> Unit>(
            { gateway.subtitleFile(session, "nope", SOURCE, 2, "srt") },
            { gateway.subtitleFile(session, ITEM, "../x", 2, "srt") },
            { gateway.subtitleFile(session, ITEM, SOURCE, -1, "srt") },
            { gateway.subtitleFile(session, ITEM, SOURCE, 2, "html") },
        )) assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure(call).kind)
        assertEquals(0, web.requestCount)
    }
}
