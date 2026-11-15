package app.shoalwatch.data.playback

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import app.shoalwatch.data.playback.engine.CueTimeline
import app.shoalwatch.data.playback.engine.SubtitleFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Subtitle files built to make a parser do quadratic work or run out of memory (final-fixes review I-1, I-2 and every earlier
 * round). The app's own parsers are linear by construction; each shape is tested both ways: the hostile file is refused or
 * handled within [LIMIT_MS] (generous: these take tens of milliseconds), and a normal file of the same shape gives the right
 * cues, times, line breaks and styles.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleHostileTest {
    /** A hang fails the test instead of the build. */
    @get:Rule val hang: Timeout = Timeout.seconds(60)

    @Before fun warmUp() { SubtitleFiles.parse("1\n00:00:01,000 --> 00:00:02,000\n<b>x</b>\n".toByteArray(), "srt") }

    private fun <T> quickly(block: () -> T): T {
        val started = System.nanoTime()
        val result = runCatching(block)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < LIMIT_MS)
        return result.getOrThrow()
    }

    private fun parse(text: String, format: String) = quickly { SubtitleFiles.parse(text.toByteArray(), format) }

    private fun refused(text: String, format: String): String? = quickly { runCatching { SubtitleFiles.parse(text.toByteArray(), format) }.exceptionOrNull() }
        .also { assertTrue("refused as unreadable: $it", it is IllegalArgumentException) }?.message

    private fun texts(t: CueTimeline, ms: Long) = t.frameAt(ms).cues.map { it.text.toString() }

    private fun spans(cue: Cue): List<Any> = (cue.text as? Spanned)?.let { it.getSpans(0, it.length, Any::class.java).toList() }.orEmpty()

    private val assHead = "[Script Info]\nPlayResX: 320\nPlayResY: 180\n\n[V4+ Styles]\nFormat: Name, Bold, Italic, PrimaryColour, Alignment\n" +
        "Style: Default,0,0,&H00FFFFFF,2\n\n[Events]\n"
    private val assFormat = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

    // ---- I-1: the ASS Style field, and a megabyte of braces

    @Test fun anAssFormatWithStyleAfterTextIsRefused() {
        val flood = "{".repeat(1_000_000)
        refused("$assHead" + "Format: Layer, Start, End, Text, Style\nDialogue: 0,0:00:01.00,0:00:05.00,Hello,Default,$flood\n", "ass")
        refused("$assHead" + "Format: Layer, Start, End, Style, Text, Style\nDialogue: 0,0:00:01.00,0:00:05.00,Default,Hello,$flood\n", "ass")
    }

    @Test fun aMegabyteOfBracesInTheAssStyleFieldIsOneTokenAndTheTextStillShows() {
        val flood = "{".repeat(1_000_000)
        val t = parse("$assHead" + "Format: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:05.00,$flood,Hello\n", "ass")
        assertEquals(listOf("Hello"), texts(t, 1_500))
        val normal = parse("$assHead" + "Format: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:05.00,Default,Hello\n", "ass")
        assertEquals(listOf("Hello"), texts(normal, 4_999))
        assertEquals(emptyList<String>(), texts(normal, 5_000))
    }

    @Test fun aMegabyteOfUnclosedBracesInAssTextIsLiteralAndCut() {
        val t = parse("$assHead$assFormat" + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Start" + "{".repeat(1_000_000) + "\n", "ass")
        val text = texts(t, 1_500).single()
        assertTrue(text.startsWith("Start{{{"))
        assertEquals(SubtitleFiles.MAX_CUE_CHARS, text.length)
        val normal = parse("$assHead$assFormat" + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\i1}Start\\Nnext {line}\n", "ass")
        val cue = normal.frameAt(1_500).cues.single()
        assertEquals("a later block is removed, \\N is a line break", "Start\nnext", cue.text.toString())
        assertTrue("italic from the first block", spans(cue).any { it is StyleSpan })
    }

    @Test fun aMegabyteOfBracesInSubRipAndWebVttIsQuick() {
        val srt = parse("1\n00:00:01,000 --> 00:00:02,000\nStart" + "{\\".repeat(500_000) + "\n", "srt")
        assertTrue(texts(srt, 1_500).single().startsWith("Start{\\"))
        val vtt = parse("WEBVTT\n\n00:01.000 --> 00:02.000\nStart" + "{".repeat(1_000_000) + "\n", "vtt")
        assertTrue(texts(vtt, 1_500).single().startsWith("Start{"))
    }

    // ---- I-2: SubRip tags (Html.fromHtml was quadratic in open tags)

    @Test fun aSubRipCueWithAHundredThousandUnclosedBoldTagsIsQuick() {
        val t = parse("1\n00:00:01,000 --> 00:00:02,000\n" + "<b>".repeat(100_000) + "Bold text\n", "srt")
        val cue = t.frameAt(1_500).cues.single()
        assertEquals("Bold text", cue.text.toString())
        assertTrue("still bold", spans(cue).any { it is StyleSpan })
        val lines = parse("1\n00:00:01,000 --> 00:00:02,000\n" + (1..1_000).joinToString("\n") { "<b>".repeat(700) } + "\nEnd\n", "srt")
        assertTrue(lines.size > 0)
    }

    // ---- subtitle-parsers review I-2 and m-2: styled runs, and what one frame hands the view

    private fun alternating(cues: Int) = buildString {
        repeat(cues) { append(stamp(it * 10L, ',')).append(" --> ").append(stamp(it * 10L + 5, ',')).append("\n<b><i>").append("<u>a</u>b".repeat(1_000)).append("\n\n") }
    }

    @Test fun nineHundredCuesOfAlternatingStylesToTheSizeLimitAreQuickAndCancellable() {
        val srt = alternating(900)
        assertTrue("near the 8 MiB limit: ${srt.length}", srt.length in 7_000_000..SubtitleFiles.MAX_BYTES)
        val t = parse(srt, "srt")
        val cue = t.frameAt(0).cues.single()
        assertEquals(SubtitleFiles.MAX_CUE_CHARS, cue.text!!.length)
        val styled = spans(cue)
        assertTrue("styled runs are capped: ${styled.size} spans", styled.size <= SubtitleFiles.MAX_STYLED_RUNS * 2 + 2)
        assertTrue("still bold and italic", styled.any { it is StyleSpan })
        var asked = 0
        quickly { SubtitleFiles.parse(srt.toByteArray(), "srt") { asked++; false } }
        assertTrue("asked at least every 50 cues: $asked", asked >= 900 / 50)
        var stopAt = 0
        val e = quickly { runCatching { SubtitleFiles.parse(srt.toByteArray(), "srt") { ++stopAt > 3 } }.exceptionOrNull() }
        assertEquals("Stopped.", e?.message)
    }

    @Test fun aNormalStyledCueKeepsItsSpansExactly() {
        val cue = parse("1\n00:00:01,000 --> 00:00:02,000\n<b>Bold</b> <i>it</i> <u>un</u> <b><i>both</i></b>\n", "srt").frameAt(1_500).cues.single()
        val text = cue.text as Spanned
        assertEquals("Bold it un both", text.toString())
        fun ranges(match: (Any) -> Boolean) = text.getSpans(0, text.length, Any::class.java).filter(match).map { text.getSpanStart(it) to text.getSpanEnd(it) }.sortedBy { it.first }
        val bold = { s: Any -> s is StyleSpan && (s.style and android.graphics.Typeface.BOLD) != 0 }
        val italic = { s: Any -> s is StyleSpan && (s.style and android.graphics.Typeface.ITALIC) != 0 }
        assertEquals(listOf(0 to 4, 11 to 15), ranges(bold))
        assertEquals(listOf(5 to 7, 11 to 15), ranges(italic))
        assertEquals(listOf(8 to 10), ranges { it is android.text.style.UnderlineSpan })
    }

    @Test fun oneFrameHandsTheViewAtMostFourThousandCharacters() {
        val srt = buildString { repeat(16) { append(stamp(1_000L + it, ',')).append(" --> 00:00:09,000\n").append(('a' + it).toString().repeat(1_999)).append("\n\n") } }
        val shown = parse(srt, "srt").frameAt(5_000).cues
        assertEquals("two cues of 1,999 characters fit, a third would not", 2, shown.size)
        assertTrue(shown.sumOf { it.text!!.length } <= SubtitleFiles.MAX_FRAME_CHARS)
        val short = parse(buildString { repeat(20) { append(stamp(1_000L + it, ',')).append(" --> 00:00:09,000\nLine $it\n\n") } }, "srt")
        assertEquals("short cues: still up to 16", SubtitleFiles.MAX_CUES_AT_ONCE, short.frameAt(5_000).cues.size)
    }

    @Test fun aMegabyteOfUnclosedAngleBracketsIsLiteral() {
        val t = parse("1\n00:00:01,000 --> 00:00:02,000\nA" + "<".repeat(1_000_000) + "\n", "srt")
        assertTrue(texts(t, 1_500).single().startsWith("A<<<"))
    }

    @Test fun normalSubRipTagsStyleOnlyTheirText() {
        val t = parse("1\n00:00:01,000 --> 00:00:02,500\n<b>Bold</b> plain\n<i>second</i> line\n\n2\n00:00:03,000 --> 00:00:04,000\nNext\n", "srt")
        val cue = t.frameAt(1_000).cues.single()
        assertEquals("Bold plain\nsecond line", cue.text.toString())
        val text = cue.text as Spanned
        val bold = spans(cue).filterIsInstance<StyleSpan>().first { it.style == android.graphics.Typeface.BOLD }
        assertEquals(0, text.getSpanStart(bold))
        assertEquals(4, text.getSpanEnd(bold))
        assertEquals(emptyList<String>(), texts(t, 2_500))
        assertEquals(listOf("Next"), texts(t, 3_000))
    }

    // ---- I-2: WebVTT settings, ruby, STYLE blocks

    @Test fun aMegabyteOfWebVttSettingsWithNoColonIsIgnored() {
        val t = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 " + "a".repeat(1_000_000) + "\nHello\n", "vtt")
        assertEquals(listOf("Hello"), texts(t, 1_500))
        val normal = parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 line:10% position:20% align:start\nHello\n", "vtt")
        val cue = normal.frameAt(1_500).cues.single()
        assertEquals(0.1f, cue.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(0.2f, cue.position, 0.0001f)
        assertEquals(android.text.Layout.Alignment.ALIGN_NORMAL, cue.textAlignment)
    }

    @Test fun deeplyNestedRubyIsQuick() {
        val t = parse("WEBVTT\n\n00:01.000 --> 00:02.000\n<c>" + "<ruby>".repeat(300_000) + "x" + "</ruby>".repeat(300_000) + "\n", "vtt")
        assertEquals(listOf("x"), texts(t, 1_500))
        val normal = parse("WEBVTT\n\n00:01.000 --> 00:02.000\n<ruby>漢<rt>kan</rt></ruby>字 <v Bob>hi</v> <c.loud>you</c> <00:00:01.500>&amp; &lt;me&gt;\n", "vtt")
        assertEquals(listOf("漢字 hi you & <me>"), texts(normal, 1_500))
    }

    @Test fun tenThousandStyleRulesAreSkipped() {
        val styles = "STYLE\n" + (1..10_000).joinToString("\n") { "::cue(.c$it) { color: red; }" } + "\n\n"
        val cues = (0 until 2_000).joinToString("") { "00:%02d.%03d --> 00:%02d.%03d\n<c.c1><c.c2><c.c3>Cue $it</c></c></c>\n\n".format(it / 1_000, it % 1_000, it / 1_000, it % 1_000 + 1) }
        val t = parse("WEBVTT\n\n$styles$cues", "vtt")
        assertEquals(listOf("Cue 0"), texts(t, 0))
        val normal = parse("WEBVTT\n\nSTYLE\n::cue { color: lime }\n\nNOTE a comment\n-->\n\nREGION\nid:x\n\nid-1\n00:01.000 --> 00:02.000\nShown\n", "vtt")
        assertEquals(listOf("Shown"), texts(normal, 1_500))
    }

    @Test fun aWebVttFileWithoutItsHeaderIsRefused() {
        refused("00:01.000 --> 00:02.000\nNo header\n", "vtt")
    }

    // ---- counts: 50,001 cues, 20,000 fully overlapping cues, 1,000 overlapping ASS lines

    private fun stamp(ms: Long, sep: Char = '.') = "%02d:%02d:%02d$sep%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

    private fun cs(cs: Int) = "%d:%02d:%02d.%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)

    @Test fun fiftyThousandAndOneCuesAreRefusedInEveryFormat() {
        val n = SubtitleFiles.MAX_CUES + 1
        val srt = buildString { for (i in 0 until n) append(i + 1).append('\n').append(stamp(i * 10L, ',')).append(" --> ").append(stamp(i * 10L + 5, ',')).append("\nx\n\n") }
        assertEquals("Too many subtitles.", refused(srt, "srt"))
        val vtt = buildString { append("WEBVTT\n\n"); for (i in 0 until n) append(stamp(i * 10L)).append(" --> ").append(stamp(i * 10L + 5)).append("\nx\n\n") }
        assertEquals("Too many subtitles.", refused(vtt, "vtt"))
        val ass = buildString { append(assHead).append(assFormat); for (i in 0 until n) append("Dialogue: 0,").append(cs(i * 2)).append(',').append(cs(i * 2 + 1)).append(",Default,,0,0,0,,x\n") }
        assertEquals("Too many subtitles.", refused(ass, "ass"))
        val unreadableTimes = buildString { append(assHead).append(assFormat); repeat(n) { append("Dialogue: 0,soon,later,Default,,0,0,0,,x\n") } }
        assertEquals("every Dialogue line counts, read or not", "Too many subtitles.", refused(unreadableTimes, "ass"))
    }

    @Test fun fiftyThousandCuesAreRead() {
        val n = SubtitleFiles.MAX_CUES
        val srt = buildString { for (i in 0 until n) append(stamp(i * 10L, ',')).append(" --> ").append(stamp(i * 10L + 5, ',')).append("\nCue ").append(i).append("\n\n") }
        val t = parse(srt, "srt")
        assertEquals(listOf("Cue 0"), texts(t, 0))
        assertEquals(listOf("Cue ${n - 1}"), texts(t, (n - 1) * 10L + 4))
        assertEquals(emptyList<String>(), texts(t, (n - 1) * 10L + 5))
    }

    @Test fun twentyThousandFullyOverlappingCuesShowSixteenAtOnce() {
        val srt = buildString { repeat(20_000) { append(it + 1).append("\n00:00:01,000 --> 00:59:00,000\nCue ").append(it).append("\n\n") } }
        val t = parse(srt, "srt")
        assertEquals(SubtitleFiles.MAX_CUES_AT_ONCE, t.frameAt(2_000).cues.size)
        assertEquals("the first in the file first", "Cue 0", texts(t, 2_000).first())
        val staggered = buildString { repeat(20_000) { append(stamp(1_000L + it, ',')).append(" --> 00:59:00,000\nCue ").append(it).append("\n\n") } }
        val s = parse(staggered, "srt")
        assertEquals(SubtitleFiles.MAX_CUES_AT_ONCE, s.frameAt(30_000).cues.size)
        val normal = parse("1\n00:00:01,000 --> 00:00:05,000\nLong\n\n2\n00:00:02,000 --> 00:00:03,000\nShort\n", "srt")
        assertEquals(listOf("Long", "Short"), texts(normal, 2_500))
        assertEquals(listOf("Long"), texts(normal, 3_500))
    }

    @Test fun twoThousandFiveHundredOverlappingAssLinesAreReadQuickly() {
        // Media3's SsaParser would have built about 3 million cue references for this (the old budget refused it).
        val ass = buildString { append(assHead).append(assFormat); repeat(2_500) { append("Dialogue: 0,").append(cs(it + 1)).append(",9:59:59.00,Default,,0,0,0,,Line ").append(it).append('\n') } }
        val t = parse(ass, "ass")
        assertEquals(SubtitleFiles.MAX_CUES_AT_ONCE, t.frameAt(9_000).cues.size)
        assertEquals("Line 0", texts(t, 9_000).first())
        assertEquals(listOf("Line 0"), texts(t, 15))
    }

    @Test fun thirtyThousandAssLinesInReverseOrderAreSorted() {
        val ass = buildString { append(assHead).append(assFormat); repeat(30_000) { val n = 30_000 - it; append("Dialogue: 0,${cs(n * 4)},${cs(n * 4 + 2)},Default,,0,0,0,,Line $n\n") } }
        val t = parse(ass, "ass")
        assertEquals(listOf("Line 1"), texts(t, 45))
        assertEquals(listOf("Line 30000"), texts(t, 1_200_005))
    }

    @Test fun aSixteenLineCapAndATwoThousandCharacterCapCutOneCueOnly() {
        val t = parse("1\n00:00:01,000 --> 00:00:02,000\n" + (1..100_000).joinToString("\n") { "Line $it" } + "\n\n2\n00:00:03,000 --> 00:00:04,000\n" + "y".repeat(10_000) + "\n", "srt")
        val lines = texts(t, 1_500).single().split('\n')
        assertEquals(SubtitleFiles.MAX_CUE_LINES, lines.size)
        assertEquals("Line 16", lines.last())
        assertEquals(SubtitleFiles.MAX_CUE_CHARS, texts(t, 3_500).single().length)
    }

    // ---- the decoding Media3 used to do, done once by the app

    private fun utf16(s: String, littleEndian: Boolean): ByteArray {
        val bom = if (littleEndian) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        return bom + s.toByteArray(if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }

    private val threeAss = "$assHead$assFormat" + (0 until 3).joinToString("") { "Dialogue: 0,${cs(100 + it * 200)},${cs(200 + it * 200)},Default,,0,0,0,,Line $it\n" }

    private fun showsThree(t: CueTimeline) {
        assertEquals(listOf("Line 0"), texts(t, 1_500))
        assertEquals(emptyList<String>(), texts(t, 2_500))
        assertEquals(listOf("Line 1"), texts(t, 3_500))
        assertEquals(listOf("Line 2"), texts(t, 5_500))
    }

    @Test fun byteOrderMarksLineEndingsAndPaddedTimesAreRead() {
        showsThree(quickly { SubtitleFiles.parse(utf16(threeAss, littleEndian = true), "ass") })
        showsThree(quickly { SubtitleFiles.parse(utf16(threeAss, littleEndian = false), "ass") })
        showsThree(quickly { SubtitleFiles.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + threeAss.toByteArray(), "ass") })
        showsThree(parse(threeAss.replace('\n', '\r'), "ass"))
        showsThree(parse(threeAss.replace("\n", "\r\n"), "ass"))
        showsThree(parse(threeAss.replace(Regex("""(\d:\d\d:\d\d\.\d\d)""")) { " ".repeat(60) + "0".repeat(70) + it.value + " ".repeat(10) }, "ass"))
    }

    @Test fun controlCharactersAndLineSeparatorsAreRemovedFromText() {
        val t = parse("1\n00:00:01,000 --> 00:00:02,000\nA\u0000B\u0007C\u2028D\u2029E\u0085F\tG\n", "srt")
        assertEquals(listOf("ABCDEF\tG"), texts(t, 1_500))
        val invalid = quickly { SubtitleFiles.parse("1\n00:00:01,000 --> 00:00:02,000\nOK ".toByteArray() + ByteArray(1_000_000) { 0xFF.toByte() }, "srt") }
        assertTrue(texts(invalid, 1_500).single().startsWith("OK \uFFFD"))
    }

    @Test fun onlyRealAssEventLinesAreRead() {
        val events = "$assHead$assFormat" + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Kept\n" +
            "\uFEFFDialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,After a byte order mark\n" +
            "dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,Lower case\n" +
            "Comment: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,Comment\n" +
            "FORMAT: Layer, End, Start, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:09.00,0:00:10.00,Default,,0,0,0,,After a FORMAT in capitals\n" +
            "[Fonts]\nDialogue: 0,0:00:11.00,0:00:12.00,Default,,0,0,0,,In another section\n"
        val t = parse(events, "ass")
        assertEquals(listOf("Kept"), texts(t, 1_500))
        assertEquals(listOf("After a byte order mark"), texts(t, 3_500))
        assertEquals(emptyList<String>(), texts(t, 5_500))
        assertEquals(emptyList<String>(), texts(t, 7_500))
        assertEquals("a FORMAT in capitals is not a Format line", listOf("After a FORMAT in capitals"), texts(t, 9_500))
        assertEquals(emptyList<String>(), texts(t, 11_500))
    }

    @Test fun moreThanTwoHundredAndFiftySixAssStylesKeepTheFirst() {
        val styles = (1..10_000).joinToString("\n") { "Style: S$it,-1,0,&H000000FF,8" } + "\nStyle: S1,0,0,&H00FFFFFF,2\nStyle: ${"L".repeat(65)},-1,0,&H000000FF,8\n"
        val ass = "[Script Info]\n\n[V4+ Styles]\nFormat: Name, Bold, Italic, PrimaryColour, Alignment\n$styles\n[Events]\nFormat: Layer, Start, End, Style, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,S1,First style\nDialogue: 0,0:00:03.00,0:00:04.00,S300,Past the cap\n"
        val t = parse(ass, "ass")
        val first = t.frameAt(1_500).cues.single()
        assertEquals("the first S1 wins", Cue.ANCHOR_TYPE_START, first.lineAnchor)
        assertTrue("bold", spans(first).any { it is StyleSpan })
        val past = t.frameAt(3_500).cues.single()
        assertEquals("Past the cap", past.text.toString())
        assertFalse("style 300 was never read", spans(past).any { it is StyleSpan })
    }

    // ---- cancellation really stops the work

    @Test fun aParseChecksForCancellationAtLeastEveryThousandLines() {
        val srt = buildString { repeat(5_000) { append(stamp(it * 10L, ',')).append(" --> ").append(stamp(it * 10L + 5, ',')).append("\nx\n\n") } } // 20,000 lines
        val vtt = "WEBVTT\n\n" + srt.replace(',', '.')
        val ass = buildString { append(assHead).append(assFormat); repeat(20_000) { append("Dialogue: 0,").append(cs(it * 2)).append(',').append(cs(it * 2 + 1)).append(",Default,,0,0,0,,x\n") } }
        for ((text, format) in listOf(srt to "srt", vtt to "vtt", ass to "ass")) {
            var asked = 0
            SubtitleFiles.parse(text.toByteArray(), format) { asked++; false }
            assertTrue("$format asked $asked times", asked >= 20)
        }
    }

    @Test fun aParseCancelledMidFileStops() {
        val srt = buildString { repeat(40_000) { append(stamp(it * 10L, ',')).append(" --> ").append(stamp(it * 10L + 5, ',')).append("\nx\n\n") } } // 160,000 lines
        var asked = 0
        val e = runCatching { SubtitleFiles.parse(srt.toByteArray(), "srt") { ++asked > 5 } }.exceptionOrNull()
        assertEquals("Stopped.", e?.message)
        assertTrue("stopped at about line 6,000, asked $asked", asked <= 7)
    }

    @Test fun runningOutOfMemoryWhileParsingIsUnreadableNotACrash() {
        assertTrue(runCatching { SubtitleFiles.guarded { throw OutOfMemoryError() } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SubtitleFiles.guarded { throw IllegalStateException("x") } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SubtitleFiles.guarded { throw StackOverflowError() } }.exceptionOrNull() is IllegalArgumentException)
    }

    private companion object {
        const val LIMIT_MS = 2_000L
    }
}
