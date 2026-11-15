package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.api.VideoInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11 at the playback boundary: everything the server sends is checked before it becomes a model. */
class PlaybackGatewayHostileTest : PlaybackGatewayTestBase() {

    @Test fun badSourcesAreDroppedAndTheRestAreBounded() {
        val info = info("hostile.json")
        assertNull("a play session id that is not alphanumeric is dropped", info.playSessionId)
        assertEquals(listOf(SOURCE, "secondsource1"), info.sources.map { it.id })
        assertNull("../ in a TranscodingUrl", info.sources[1].transcoding)
        assertEquals("mp4", info.sources[1].container)
    }

    @Test fun anAbsoluteTranscodingUrlIsRefusedAndItsReasonsWithIt() {
        val source = info("hostile.json").sources.first()
        assertNull(source.transcoding)
        assertEquals(emptySet<TranscodeReason>(), source.transcodeReasons)
    }

    @Test fun sourceFieldsOutOfRangeBecomeNull() {
        val source = info("hostile.json").sources.first()
        assertNull("container with markup", source.container)
        assertNull("eTag that is not alphanumeric", source.eTag)
        assertNull("runtime beyond 48 hours", source.runtimeMs)
        assertNull("negative bitrate", source.bitrate)
        assertEquals(VideoInfo("h264", VideoRange.UNKNOWN, null, null, null, null), source.video)
    }

    @Test fun streamsAreValidatedOneByOne() {
        val source = info("hostile.json").sources.first()
        assertEquals(
            listOf(
                AudioTrack(1, "evil title", null, "aac", null, isDefault = false),
                AudioTrack(2, "A".repeat(120), "en-GB", null, null, isDefault = false),
            ),
            source.audio,
        )
        assertEquals(listOf(SubtitleTrack(7, "", "eng", "webvtt", SubtitleKind.TEXT, false, false, false, external = true, fileFormat = "vtt")), source.subtitles)
        assertNull("default audio that is not a kept audio track", source.defaultAudioIndex)
        assertNull("default subtitle that is an audio index", source.defaultSubtitleIndex)
    }

    @Test fun duplicateStreamIndexKeepsTheFirst() {
        val audio = info("hostile.json").sources.first().audio
        assertEquals("aac", audio.single { it.index == 1 }.codec)
    }

    @Test fun fiveHundredStreamsKeepTwoHundred() {
        val streams = (0 until 500).joinToString(",") { """{"Codec":"aac","Type":"Audio","Index":$it}""" }
        val source = infoOf("""{"MediaSources":[{"Id":"abc","MediaStreams":[$streams]}]}""").sources.single()
        assertEquals(200, source.audio.size)
        assertEquals((0 until 200).toList(), source.audio.map { it.index })
    }

    @Test fun atMostFiftySources() {
        val sources = (0 until 60).joinToString(",") { """{"Id":"source$it"}""" }
        assertEquals(50, infoOf("""{"MediaSources":[$sources]}""").sources.size)
    }

    @Test fun aDuplicateSourceIdKeepsTheFirst() {
        val info = infoOf("""{"MediaSources":[{"Id":"abc","Container":"mkv"},{"Id":"abc","Container":"mp4"}]}""")
        assertEquals(listOf("mkv"), info.sources.map { it.container })
    }

    @Test fun languageAndCodecShapes() {
        val streams = listOf("eng", "en", "pt-BR", "zh-Hans", "e", "engl", "en-", "en_GB", "x-klingon-long", "<eng>").mapIndexed { i, lang ->
            """{"Codec":"AAC","Language":"$lang","Type":"Audio","Index":$i}"""
        }.joinToString(",")
        val audio = infoOf("""{"MediaSources":[{"Id":"abc","MediaStreams":[$streams]}]}""").sources.single().audio
        assertEquals(listOf("eng", "en", "pt-BR", "zh-Hans", null, null, null, null, null, null), audio.map { it.language })
        assertTrue(audio.all { it.codec == "aac" })
    }

    @Test fun aPictureSubtitleCodecIsRecognisedAndAnUnknownTextOneIsText() {
        val streams = listOf("pgssub" to false, "dvdsub" to false, "dvbsub" to false, "vobsub" to false, "srt" to true, "webvtt" to true, "ssa" to true, "mov_text" to true, "xsub" to false)
            .mapIndexed { i, (codec, text) -> """{"Codec":"$codec","Type":"Subtitle","Index":$i,"IsTextSubtitleStream":$text}""" }.joinToString(",")
        val kinds = infoOf("""{"MediaSources":[{"Id":"abc","MediaStreams":[$streams]}]}""").sources.single().subtitles.map { it.kind }
        assertEquals(
            listOf(SubtitleKind.PICTURE, SubtitleKind.PICTURE, SubtitleKind.PICTURE, SubtitleKind.PICTURE, SubtitleKind.TEXT, SubtitleKind.TEXT,
                SubtitleKind.STYLED_TEXT, SubtitleKind.TEXT, SubtitleKind.PICTURE),
            kinds,
        )
    }

    @Test fun anExplicitNullOrWrongTypeAtTheTopIsAServerError() {
        web.enqueue(json("""{"MediaSources":"nope"}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.playbackInfo(session, ITEM, body()) }.kind)
        web.enqueue(json("[]"))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.playbackInfo(session, ITEM, body()) }.kind)
    }

    @Test fun segmentsWithBadRangesOrUnknownTypesAreDropped() {
        web.enqueue(json(
            """{"Items":[
              {"Type":"Intro","StartTicks":30000000,"EndTicks":10000000},
              {"Type":"Intro","StartTicks":10000000,"EndTicks":10000000},
              {"Type":"Outro","StartTicks":-10,"EndTicks":10000000},
              {"Type":"Outro","StartTicks":0,"EndTicks":1728000000001},
              {"Type":"Unknown","StartTicks":0,"EndTicks":10000000},
              {"Type":"Mystery","StartTicks":0,"EndTicks":10000000},
              {"Type":"Recap","EndTicks":10000000},
              {"Type":"Recap","StartTicks":"zero","EndTicks":10000000},
              {"Type":"Intro","ItemId":"ffffffffffffffffffffffffffffffff","StartTicks":0,"EndTicks":10000000},
              "junk",
              {"Type":"Intro","StartTicks":0,"EndTicks":1728000000000}
            ]}""",
        ))
        assertEquals(listOf(MediaSegment(SegmentKind.INTRO, 0, 172_800_000)), runBlocking { gateway.segments(session, ITEM) })
    }

    @Test fun atMostTwoHundredSegments() {
        val items = (0 until 300).joinToString(",") { """{"Type":"Commercial","StartTicks":${it * 10_000L},"EndTicks":${it * 10_000L + 5_000}}""" }
        web.enqueue(json("""{"Items":[$items]}"""))
        assertEquals(200, runBlocking { gateway.segments(session, ITEM) }.size)
    }

    @Test fun segmentsForABadItemIdSendNothing() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.segments(session, "../x") }.kind)
        assertEquals(0, web.requestCount)
    }

    /** 4,294,967,297 is 1 once narrowed to Int: the range is checked on the wire value first, so it names no track. */
    @Test fun aDefaultIndexBeyondIntIsDroppedNotWrapped() {
        val source = infoOf(
            """{"MediaSources":[{"Id":"abc","DefaultAudioStreamIndex":4294967297,"DefaultSubtitleStreamIndex":4294967298,"MediaStreams":[
              {"Codec":"aac","Type":"Audio","Index":1},{"Codec":"subrip","Type":"Subtitle","Index":2}]}]}""",
        ).sources.single()
        assertNull(source.defaultAudioIndex)
        assertNull(source.defaultSubtitleIndex)
    }
}
