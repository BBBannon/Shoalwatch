package app.shoalwatch.data.playback

import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.text.Cue
import app.shoalwatch.data.playback.engine.SubtitleFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's own SubRip, WebVTT and ASS readers on ordinary files: the details `SubtitleHostileTest` does not pin down. */
@RunWith(RobolectricTestRunner::class)
class SubtitleFormatsTest {
    private fun cue(text: String, format: String, ms: Long) = SubtitleFiles.parse(text.toByteArray(), format).frameAt(ms).cues.single()
    private fun texts(text: String, format: String, ms: Long) = SubtitleFiles.parse(text.toByteArray(), format).frameAt(ms).cues.map { it.text.toString() }
    private fun spans(c: Cue): List<Any> = (c.text as? Spanned)?.let { it.getSpans(0, it.length, Any::class.java).toList() }.orEmpty()

    // ---- SubRip

    @Test fun subRipTimesMayLeaveOutHoursUseADotAndHaveShortFractions() {
        val srt = "00:01.5 --> 00:02.25 X1 Y2\nShort\n\n3\n00:00:03.000 --> 00:00:04,000\nDot\n"
        assertEquals(listOf("Short"), texts(srt, "srt", 1_500))
        assertEquals(emptyList<String>(), texts(srt, "srt", 2_250))
        assertEquals(listOf("Dot"), texts(srt, "srt", 3_500))
    }

    @Test fun subRipAndWebVttTimesMayHaveNoFraction() {
        // subtitle-parsers review m-3: hand-made files often leave out the milliseconds.
        assertEquals(listOf("Whole seconds"), texts("1\n00:00:01 --> 00:00:03\nWhole seconds\n", "srt", 2_000))
        assertEquals(emptyList<String>(), texts("1\n00:00:01 --> 00:00:03\nWhole seconds\n", "srt", 3_000))
        assertEquals(listOf("Vtt"), texts("WEBVTT\n\n00:01 --> 00:02.500 align:end\nVtt\n", "vtt", 2_000))
    }

    // ---- subtitle-parsers review m-1: a header line is bounded before it is split

    @Test fun anOverLongAssFormatOrHeaderLineIsRefused() {
        val wide = "Format: " + "a,".repeat(4_000_000) + "Start,End,Text"
        for (ass in listOf("[Events]\n$wide\nDialogue: 0,0:00:01.00,0:00:02.00,x\n", "[V4+ Styles]\n$wide\n", "[V4+ Styles]\nFormat: Name\nStyle: " + "n".repeat(5_000) + "\n")) {
            val started = System.nanoTime()
            val e = runCatching { SubtitleFiles.parse(ass.toByteArray(), "ass") }.exceptionOrNull()
            assertTrue("refused: $e", e is IllegalArgumentException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }
        val ordinary = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,," + "x".repeat(10_000) + "\n"
        assertEquals("a long Dialogue line is text, not a header", SubtitleFiles.MAX_CUE_CHARS, texts(ordinary, "ass", 1_500).single().length)
    }

    @Test fun aReversedSubRipCueIsDroppedAndTheNextStillShows() {
        val srt = "1\n00:00:05,000 --> 00:00:04,000\nReversed\n\n2\n00:00:06,000 --> 00:00:07,000\nAfter\n"
        assertEquals(emptyList<String>(), texts(srt, "srt", 4_500))
        assertEquals(listOf("After"), texts(srt, "srt", 6_500))
    }

    @Test fun anAlignmentTagAtTheStartPlacesTheCueAndOthersAreRemoved() {
        val top = cue("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Top {\\i1}here\n", "srt", 1_500)
        assertEquals("Top here", top.text.toString())
        assertEquals(Cue.ANCHOR_TYPE_START, top.lineAnchor)
        assertEquals(0.08f, top.line, 0.0001f)
        val later = cue("1\n00:00:01,000 --> 00:00:02,000\nText {\\an8}later\n", "srt", 1_500)
        assertEquals("not at the start: removed, not applied", Cue.DIMEN_UNSET, later.line, 0f)
    }

    @Test fun underlineAndNestedTagsCloseCleanly() {
        val c = cue("1\n00:00:01,000 --> 00:00:02,000\n<u>a<u>b</u>c</u>d</b></b>e &amp; &unknown;\n", "srt", 1_500)
        assertEquals("abcde & &unknown;", c.text.toString())
        val text = c.text as Spanned
        val underline = spans(c).filterIsInstance<UnderlineSpan>()
        assertEquals(1, underline.size)
        assertEquals(0, text.getSpanStart(underline[0]))
        assertEquals(3, text.getSpanEnd(underline[0]))
        assertFalse("stray closers do nothing", spans(c).any { it is StyleSpan })
    }

    // ---- WebVTT

    @Test fun webVttLineNumbersAnchorsAndAlignment() {
        val vtt = "WEBVTT - a title\nKind: captions\n\n1\n00:00:01.000 --> 00:00:02.000 line:-2 align:end\nEnd\n\n" +
            "00:00:03.000 --> 00:00:04.000 line:50%,center position:10%,line-left size:50%\nMiddle\n"
        val end = cue(vtt, "vtt", 1_500)
        assertEquals(-2f, end.line, 0f)
        assertEquals(Cue.LINE_TYPE_NUMBER, end.lineType)
        assertEquals(1f, end.position, 0f)
        assertEquals(Cue.ANCHOR_TYPE_END, end.positionAnchor)
        val middle = cue(vtt, "vtt", 3_500)
        assertEquals(0.5f, middle.line, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, middle.lineAnchor)
        assertEquals(0.1f, middle.position, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_START, middle.positionAnchor)
    }

    @Test fun webVttStylingTagsAndEntities() {
        val c = cue("WEBVTT\n\n00:01.000 --> 00:02.000\n<i.loud>Hi</i>&nbsp;<b>there</b>\nnext&lrm;\n", "vtt", 1_500)
        assertEquals("Hi there\nnext‎", c.text.toString())
        assertEquals(2, spans(c).filterIsInstance<StyleSpan>().size)
    }

    @Test fun aWebVttCueWithAnUnreadableTimeIsSkipped() {
        val vtt = "WEBVTT\n\n0x:01.000 --> 00:02.000\nNot a time\n\n00:03.000 --> 00:04.000\nFine\n"
        assertEquals(emptyList<String>(), texts(vtt, "vtt", 1_500))
        assertEquals(listOf("Fine"), texts(vtt, "vtt", 3_500))
    }

    // ---- ASS / SSA

    private val head = "[Script Info]\nPlayResX: 640\nPlayResY: 360\n\n[V4+ Styles]\n" +
        "Format: Name, Fontname, PrimaryColour, Bold, Italic, Underline, Alignment\nStyle: Default,Arial,&H00FFFFFF,-1,0,0,2\n\n[Events]\n" +
        "Format: Layer, Start, End, Style, Text\n"

    @Test fun assTimesWithoutHoursOrWithAColonBeforeTheFractionPlay() {
        val ass = head + "Dialogue: 0,0:01.00,0:02.00,Default,No hours\nDialogue: 0,0:00:03:00,0:00:04:00,Default,Colon\n"
        assertEquals(listOf("No hours"), texts(ass, "ass", 1_500))
        assertEquals(listOf("Colon"), texts(ass, "ass", 3_500))
    }

    @Test fun firstBlockOverridesApplyToTheWholeCueAndLaterOnesAreRemoved() {
        val ass = head + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,{\\b0\\u1\\an7\\fs20}Left{\\i1} top\\hhere\\Nline two\n"
        val c = cue(ass, "ass", 1_500)
        assertEquals("Left top here\nline two", c.text.toString())
        assertFalse("\\b0 turns the style's bold off", spans(c).any { it is StyleSpan })
        assertTrue(spans(c).any { it is UnderlineSpan })
        assertEquals(Cue.ANCHOR_TYPE_START, c.lineAnchor)
        assertEquals(Cue.ANCHOR_TYPE_START, c.positionAnchor)
        assertEquals(0.05f, c.position, 0.0001f)
    }

    @Test fun legacySsaStylesAndAlignmentsAreRead() {
        val ssa = "[Script Info]\nScriptType: v4.00\n\n[V4 Styles]\nFormat: Name, Fontname, Bold, Alignment\nStyle: Top,Arial,-1,6\n\n[Events]\n" +
            "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: Marked=0,0:00:01.00,0:00:02.00,Top,,0,0,0,,Sign\n"
        val c = cue(ssa, "ssa", 1_500)
        assertEquals("legacy 6 is top centre", Cue.ANCHOR_TYPE_START, c.lineAnchor)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, c.positionAnchor)
        assertTrue(spans(c).any { it is StyleSpan })
    }

    @Test fun withoutPlayResAPositionIsIgnoredAndTheAlignmentPlaces() {
        val ass = "[V4+ Styles]\nFormat: Name, Alignment\nStyle: Default,2\n\n[Events]\nFormat: Layer, Start, End, Style, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,{\\pos(10,10)}Bottom\n"
        val c = cue(ass, "ass", 1_500)
        assertEquals(0.95f, c.line, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_END, c.lineAnchor)
    }

    @Test fun anUnclosedBraceIsLiteralAndADrawingIsDropped() {
        val ass = head + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,Open { brace\nDialogue: 0,0:00:03.00,0:00:04.00,Default,Shown{\\p1}m 0 0 l 1 1{\\p0} again\n"
        val t = SubtitleFiles.parse(ass.toByteArray(), "ass")
        assertEquals(listOf("Open { brace"), t.frameAt(1_500).cues.map { it.text.toString() })
        assertEquals(listOf("Shown again"), t.frameAt(3_500).cues.map { it.text.toString() })
        assertTrue("a drawing is an effect the phone cannot draw", t.usesEffectsThePhoneCannotDraw)
    }

    @Test fun simultaneousLinesAreDrawnInLayerOrder() {
        val ass = head + "Dialogue: 2,0:00:01.00,0:00:02.00,Default,Top layer\nDialogue: 0,0:00:01.00,0:00:02.00,Default,Bottom layer\n"
        assertEquals(listOf("Bottom layer", "Top layer"), texts(ass, "ass", 1_500))
    }

    @Test fun anEventsFormatWithoutTimesIsRefused() {
        val e = runCatching { SubtitleFiles.parse("[Events]\nFormat: Layer, Style, Text\nDialogue: 0,Default,x\n".toByteArray(), "ass") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
