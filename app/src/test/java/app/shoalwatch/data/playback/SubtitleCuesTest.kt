package app.shoalwatch.data.playback

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.media3.common.text.Cue
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.playback.engine.CueTimeline
import app.shoalwatch.data.playback.engine.SubtitleFiles
import app.shoalwatch.ui.browse.BEN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Text subtitle files through the app's own parsers (the fixtures in `src/test/resources/subtitles/`, the same files as
 * `tools/mockjelly/media/`), as untrusted input: the golden cue texts and times Media3's parsers gave, and the loader's limits.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleCuesTest {
    private fun bytes(name: String) = javaClass.getResource("/subtitles/$name")!!.readBytes()
    private fun timeline(name: String, format: String = name.substringAfterLast('.')) = SubtitleFiles.parse(bytes(name), format)
    private fun texts(t: CueTimeline, ms: Long): List<String> = t.frameAt(ms).cues.map { it.text.toString() }
    private fun track(index: Int = 3, kind: SubtitleKind = SubtitleKind.TEXT, fileFormat: String? = "srt") =
        SubtitleTrack(index, "English", "eng", "subrip", kind, false, false, false, false, fileFormat)

    private val gateway = FakePlaybackGateway()

    // ---- the fixtures (golden: the texts and times Media3's parsers gave, as seen on a device)

    @Test fun sampleSrtShowsItsCueAtItsTimeOnly() {
        val t = timeline("sample.srt")
        assertEquals(listOf("First cue"), texts(t, 1_500))
        assertEquals(emptyList<String>(), texts(t, 2_500))
        assertEquals(listOf("Third cue"), texts(t, 5_999))
        assertEquals(emptyList<String>(), texts(t, 6_000))
        assertFalse(t.usesEffectsThePhoneCannotDraw)
    }

    /** The player draws at (position - delay): later and earlier both work, at any position (so after any seek). */
    @Test fun aDelayMovesTheCueLaterOrEarlier() {
        val t = timeline("sample.srt")
        assertEquals("+1 s: the 1.5 s cue shows at 2.5 s", listOf("First cue"), texts(t, 2_500 - 1_000))
        assertEquals("-1 s: the 1.5 s cue shows at 0.5 s", listOf("First cue"), texts(t, 500 - -1_000))
        assertEquals("before the start there is nothing", emptyList<String>(), texts(t, 500 - 1_000))
    }

    @Test fun sampleVttParses() {
        val t = timeline("sample.vtt")
        assertEquals(listOf("Second cue"), texts(t, 3_500))
    }

    @Test fun styledAssKeepsAPositionOverrideAndOrdersSimultaneousCuesByLayer() {
        val t = timeline("styled.ass")
        assertEquals(listOf("Plain bottom line"), texts(t, 1_500))
        assertEquals(emptyList<String>(), texts(t, 2_000))
        val sign = t.frameAt(3_500).cues.single()
        assertEquals("Positioned sign", sign.text.toString())
        assertEquals("\\pos(160,20) over PlayResX 320", 0.5f, sign.position, 0.0001f)
        assertEquals("\\pos(160,20) over PlayResY 180: at the top", 20f / 180, sign.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, sign.lineType)
        assertEquals("the Sign style's alignment 8", Cue.ANCHOR_TYPE_START, sign.lineAnchor)
        assertTrue("the Sign style is italic", spans(sign).any { it is StyleSpan && (it.style and Typeface.ITALIC) != 0 })
        assertEquals(listOf("Red text on layer 0", "Layer one sits above"), texts(t, 5_500))
        val (red, above) = t.frameAt(5_500).cues
        assertTrue("\\c&H0000FF& is red", spans(red).any { it is ForegroundColorSpan && it.foregroundColor == 0xFFFF0000.toInt() })
        assertEquals("the Default style's alignment 2: at the bottom", Cue.ANCHOR_TYPE_END, red.lineAnchor)
        assertEquals("the Sign style: at the top", Cue.ANCHOR_TYPE_START, above.lineAnchor)
        assertTrue("the Sign style is yellow", spans(above).any { it is ForegroundColorSpan && it.foregroundColor == 0xFFFFFF00.toInt() })
        assertEquals(emptyList<String>(), texts(t, 6_000))
        assertFalse(t.usesEffectsThePhoneCannotDraw)
    }

    private fun spans(cue: Cue): List<Any> = (cue.text as? Spanned)?.let { it.getSpans(0, it.length, Any::class.java).toList() }.orEmpty()

    @Test fun effectsAssIsFlaggedAndItsTagsNeverShowAsText() {
        val t = timeline("effects.ass")
        assertTrue(t.usesEffectsThePhoneCannotDraw)
        assertEquals(listOf("Karaoke"), texts(t, 1_500))
        assertEquals(listOf("Growing text"), texts(t, 3_500))
        assertEquals(listOf("Moving text"), texts(t, 5_500))
        assertEquals("a drawing is not text", emptyList<String>(), texts(t, 7_500))
        for (ms in listOf(1_500L, 3_500L, 5_500L, 7_500L)) for (text in texts(t, ms)) assertFalse(text, '{' in text || '\\' in text)
    }

    @Test fun eachUndrawableTagIsFoundAndOrdinaryTagsAreNot() {
        fun ass(tags: String) = ("[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{$tags}Hello\n").toByteArray()
        for (tag in listOf("\\k20", "\\K20", "\\kf20", "\\ko20", "\\t(0,1,\\fs9)", "\\move(1,2,3,4)", "\\fad(1,2)", "\\fade(1,2,3,4,5,6,7)", "\\p1", "\\p9", "\\clip(1,2,3,4)", "\\iclip(1,2,3,4)", "\\fnArial")) {
            assertTrue(tag, SubtitleFiles.parse(ass(tag), "ass").usesEffectsThePhoneCannotDraw)
        }
        for (tag in listOf("\\b1", "\\i1", "\\an8", "\\pos(1,2)", "\\c&H0000FF&", "\\fs20", "\\p0")) {
            assertFalse(tag, SubtitleFiles.parse(ass(tag), "ass").usesEffectsThePhoneCannotDraw)
        }
    }

    @Test fun bomAndCrlfAreAccepted() {
        val t = timeline("bom-crlf.srt")
        assertEquals(listOf("BOM and CRLF first"), texts(t, 1_500))
        assertEquals(listOf("Second line one\nSecond line two"), texts(t, 3_500))
    }

    @Test fun hostileSrtNeitherThrowsNorExceedsTheCaps() {
        val t = timeline("hostile.srt")
        assertEquals("out of order cues are sorted", listOf("Out of order, listed second"), texts(t, 1_500))
        assertEquals(listOf("Out of order, listed first"), texts(t, 5_500))
        val long = texts(t, 7_500).single()
        assertEquals("a 10,000 character line is cut", SubtitleFiles.MAX_CUE_CHARS, long.length)
        for (ms in 0L..9_000L step 250) assertTrue(t.frameAt(ms).cues.size <= SubtitleFiles.MAX_CUES_AT_ONCE)
    }

    @Test fun aNegativeTimeNeverShowsBeforeZeroOrBreaksTheRest() {
        val t = SubtitleFiles.parse("1\n-00:00:01,000 --> 00:00:00,500\nNegative\n\n2\n00:00:01,000 --> 00:00:02,000\nAfter\n".toByteArray(), "srt")
        assertEquals(emptyList<String>(), texts(t, -1))
        assertEquals(listOf("After"), texts(t, 1_500))
    }

    @Test fun markupIsTextOrTheSupportedStylingAndNothingElse() {
        val raw = "1\n00:00:01,000 --> 00:00:02,000\n<b>bold</b> <a href=\"https://evil.example/\">link</a><img src=\"https://evil.example/x.png\"> <script>x</script>\n"
        val cue = SubtitleFiles.parse(raw.toByteArray(), "srt").frameAt(1_500).cues.single()
        val text = cue.text!!
        assertFalse("no object (image) placeholder", '￼' in text)
        assertTrue(text.toString(), text.toString().startsWith("bold link"))
        val spans = (text as? Spanned)?.getSpans(0, text.length, Any::class.java).orEmpty()
        assertTrue("bold is kept", spans.any { it is StyleSpan })
        assertFalse("no link, click or image", spans.any { it is URLSpan || it is ClickableSpan || it is ImageSpan })
    }

    @Test fun overlappingCuesShowTogetherInStartOrder() {
        val t = SubtitleFiles.parse("1\n00:00:01,000 --> 00:00:05,000\nLong\n\n2\n00:00:02,000 --> 00:00:03,000\nShort\n".toByteArray(), "srt")
        assertEquals(listOf("Long", "Short"), texts(t, 2_500))
        assertEquals(listOf("Long"), texts(t, 3_500))
    }

    @Test fun aFileWithMoreThanFiftyThousandCueGroupsIsRefused() {
        val vtt = buildString {
            append("WEBVTT\n\n")
            for (i in 0..SubtitleFiles.MAX_CUES) append(stamp(i * 10L)).append(" --> ").append(stamp(i * 10L + 5)).append("\nx\n\n")
        }
        try {
            SubtitleFiles.parse(vtt.toByteArray(), "vtt")
            fail("parsed")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun stamp(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

    @Test fun garbageIsEmptyOrRefusedNeverACrash() {
        for (format in listOf("srt", "vtt", "ass", "ssa")) {
            for (junk in listOf(ByteArray(0), ByteArray(4096) { (it * 31).toByte() }, "}{\\\\{{{".toByteArray())) {
                try {
                    SubtitleFiles.parse(junk, format)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    // ---- the loader

    private fun TestScope.loader(parse: (ByteArray, String) -> CueTimeline = SubtitleFiles::parse) =
        SubtitleLoader(gateway, StandardTestDispatcher(testScheduler), { b, f, _ -> parse(b, f) })

    @Test fun theLoaderFetchesWithTheTracksFileFormatAndParses() = runTest {
        gateway.subtitleFiles[3] = bytes("sample.srt")
        val t = loader().load(BEN, ITEM, SOURCE, track())
        assertEquals(listOf("subtitle:3.srt"), gateway.calls)
        assertEquals(listOf("First cue"), texts(t, 1_500))
    }

    @Test fun aGeneratedNineMebibyteBodyIsRefusedBeforeParsing() = runTest {
        gateway.subtitleFiles[3] = ByteArray(9 * 1024 * 1024) { 'a'.code.toByte() }
        var parsed = 0
        val e = runCatching { loader { b, f -> parsed++; SubtitleFiles.parse(b, f) }.load(BEN, ITEM, SOURCE, track()) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
        assertEquals("never parsed", 0, parsed)
    }

    @Test fun aParserFailureIsServerError() = runTest {
        gateway.subtitleFiles[3] = bytes("sample.srt")
        val e = runCatching { loader { _, _ -> throw IllegalStateException("boom") }.load(BEN, ITEM, SOURCE, track()) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
    }

    @Test fun aPictureTrackIsNeverFetchedAsText() = runTest {
        val e = runCatching { loader().load(BEN, ITEM, SOURCE, track(kind = SubtitleKind.PICTURE, fileFormat = null)) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
        assertEquals(emptyList<String>(), gateway.calls)
    }

    @Test fun aFetchFailureKeepsItsOwnKind() = runTest {
        val e = runCatching { loader().load(BEN, ITEM, SOURCE, track()) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, (e as AppError).kind)
    }

    /** A parse that never ends (on its own thread) is given up after the time limit; the caller is never held longer. */
    @Test fun aParseThatTakesTooLongIsGivenUp() = runTest {
        gateway.subtitleFiles[3] = bytes("sample.srt")
        val release = CountDownLatch(1)
        val thread = Executors.newSingleThreadExecutor()
        try {
            val slow = SubtitleLoader(gateway, thread.asCoroutineDispatcher(), { b, f, _ -> release.await(); SubtitleFiles.parse(b, f) }, parseTimeoutMs = 2_000)
            val e = runCatching { slow.load(BEN, ITEM, SOURCE, track()) }.exceptionOrNull()
            assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
        } finally {
            release.countDown()
            thread.shutdown()
        }
    }

    /** A given-up parse is told so, and stops (the parsers ask at least every 1,000 lines: `SubtitleHostileTest`). */
    @Test fun anAbandonedParseIsToldToStop() = runBlocking {
        gateway.subtitleFiles[3] = bytes("sample.srt")
        val stopped = CompletableDeferred<Unit>()
        val loader = SubtitleLoader(gateway, Dispatchers.Default, { _, _, cancelled ->
            while (!cancelled()) Thread.sleep(5)
            stopped.complete(Unit)
            throw IllegalArgumentException("Stopped.")
        }, parseTimeoutMs = 100)
        val e = runCatching { loader.load(BEN, ITEM, SOURCE, track()) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
        withTimeout(2_000) { stopped.await() }
    }
}
