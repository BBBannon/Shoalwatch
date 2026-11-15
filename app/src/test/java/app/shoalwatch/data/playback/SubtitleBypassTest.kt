package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.engine.AssScript
import app.shoalwatch.data.playback.engine.CueTimeline
import app.shoalwatch.data.playback.engine.SubtitleFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ASS work budget cannot be bypassed by a file the budget and Media3 read differently (final review I-1): the file is
 * decoded and re-written once, and Media3 parses only the lines the budget counted. Each shape is tested both ways: built to
 * overload (refused quickly, "Too many subtitles.") and well formed (its cues still show).
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleBypassTest {
    private fun cs(cs: Int) = "%d:%02d:%02d.%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)

    private val head = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour\nStyle: Default,Arial,18,&H00FFFFFF\n\n"
    private val format = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    /** [lines] dialogue lines; [time] writes a time; [events] is everything between `[Events]` and the first dialogue line. */
    private fun ass(lines: Int, events: String = "$format\n", time: (String) -> String = { it }, line: (Int) -> String) = buildString {
        append(head).append("[Events]\n").append(events)
        repeat(lines) { append(line(it)).append('\n') }
    }.let { s -> s.replace(Regex("""\{T:([^}]*)\}""")) { time(it.groupValues[1]) } }

    /** 2,500 lines each starting a little after the last, all ending at 9:59:59.00: about 3 million placements. */
    private fun overlapping(events: String = "$format\n", time: (String) -> String = { it }) =
        ass(2_500, events, time) { "Dialogue: 0,{T:${cs(it + 1)}},{T:9:59:59.00},Default,,0,0,0,,Line $it" }

    /** Three ordinary lines at 1-2 s, 3-4 s and 5-6 s. */
    private fun wellFormed(events: String = "$format\n", time: (String) -> String = { it }) =
        ass(3, events, time) { "Dialogue: 0,{T:${cs(100 + it * 200)}},{T:${cs(200 + it * 200)}},Default,,0,0,0,,Line $it" }

    private fun overBudget(bytes: ByteArray): Boolean {
        val started = System.nanoTime()
        val message = runCatching { SubtitleFiles.parse(bytes, "ass") }.exceptionOrNull()?.message
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("refused in $ms ms", ms < 3_000)
        return message == "Too many subtitles."
    }

    private fun texts(t: CueTimeline, ms: Long) = t.frameAt(ms).cues.map { it.text.toString() }

    private fun showsItsCues(bytes: ByteArray) {
        val t = SubtitleFiles.parse(bytes, "ass")
        assertEquals(listOf("Line 0"), texts(t, 1_500))
        assertEquals(emptyList<String>(), texts(t, 2_500))
        assertEquals(listOf("Line 1"), texts(t, 3_500))
        assertEquals(listOf("Line 2"), texts(t, 5_500))
    }

    // ---- 1. padding and leading zeros: length is checked after trimming, and zeros do not count

    @Test fun timesPaddedWithSpacesPastSixtyFourCharactersAreCounted() {
        val pad = { t: String -> " ".repeat(60) + t + " ".repeat(10) }
        assertTrue(overBudget(overlapping(time = pad).toByteArray()))
        showsItsCues(wellFormed(time = pad).toByteArray())
    }

    @Test fun timesWithSeventyLeadingZerosAreCounted() {
        val zeros = { t: String -> "0".repeat(70) + t }
        assertTrue(overBudget(overlapping(time = zeros).toByteArray()))
        showsItsCues(wellFormed(time = zeros).toByteArray())
    }

    // ---- 2. no readable times: the lines still count towards the cap, and never reach Media3

    @Test fun aFileWithNoReadableTimesStillCountsItsLines() {
        val bytes = ass(SubtitleFiles.MAX_GROUPS + 1) { "Dialogue: 0,soon,later,Default,,0,0,0,,x" }.toByteArray()
        assertTrue(overBudget(bytes))
        val some = ass(3) { if (it == 1) "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Readable" else "Dialogue: 0,soon,later,Default,,0,0,0,,x" }
        assertEquals(listOf("Readable"), texts(SubtitleFiles.parse(some.toByteArray(), "ass"), 1_500))
    }

    // ---- 3. line endings Media3 reads: a lone carriage return

    @Test fun carriageReturnOnlyLineEndingsAreCounted() {
        assertTrue(overBudget(overlapping().replace('\n', '\r').toByteArray()))
        showsItsCues(wellFormed().replace('\n', '\r').toByteArray())
    }

    // ---- 4. the charsets Media3 picks from a byte order mark

    private fun utf16(s: String, littleEndian: Boolean): ByteArray {
        val bom = if (littleEndian) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) else byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        return bom + s.toByteArray(if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }

    @Test fun utf16LittleEndianWithAByteOrderMarkIsCounted() {
        assertTrue(overBudget(utf16(overlapping(), littleEndian = true)))
        showsItsCues(utf16(wellFormed(), littleEndian = true))
    }

    @Test fun utf16BigEndianWithAByteOrderMarkIsCounted() {
        assertTrue(overBudget(utf16(overlapping(), littleEndian = false)))
        showsItsCues(utf16(wellFormed(), littleEndian = false))
    }

    // ---- 5. Format lines: only the case-sensitive `Format:` Media3 honours, wherever it is in the events

    /** Lines written with End first, as a swapped Format line would read them. */
    private fun swappedLines(n: Int, start: (Int) -> String, end: (Int) -> String, events: String) =
        ass(n, events) { "Dialogue: 0,${end(it)},${start(it)},Default,,0,0,0,,Line $it" }

    private val swappedFormat = "Format: Layer, End, Start, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    @Test fun aFormatLineInAnotherCaseIsNotHonoured() {
        // The lines are written in the real Format's order; a scan that honoured "FORMAT:" would read them as ending first.
        val events = "$format\n${swappedFormat.replace("Format:", "FORMAT:")}\n"
        assertTrue(overBudget(overlapping(events).toByteArray()))
        showsItsCues(wellFormed(events).toByteArray())
    }

    @Test fun aFormatLineAfterAnotherSectionIsStillHonoured() {
        // After [Events], every line is event body (Media3 does not end it at another [Section]): the swapped Format governs.
        val events = "$format\n[Fonts]\n$swappedFormat\n"
        assertTrue(overBudget(swappedLines(2_500, { cs(it + 1) }, { "9:59:59.00" }, events).toByteArray()))
        showsItsCues(swappedLines(3, { cs(100 + it * 200) }, { cs(200 + it * 200) }, events).toByteArray())
    }

    // ---- other per-line work in Media3's SSA parser, bounded by the re-write

    @Test fun aLongRunOfUnclosedBracesIsParsedQuickly() {
        // Media3's `\{([^}]*)\}` would scan to the end of the line from every unclosed brace: quadratic in the line length.
        val bytes = ass(1) { "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Start" + "{".repeat(60_000) }.toByteArray()
        val started = System.nanoTime()
        val t = SubtitleFiles.parse(bytes, "ass")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("parsed in $ms ms", ms < 2_000)
        assertEquals("Start", texts(t, 1_500).single().take(5))
    }

    @Test fun linesInReverseTimeOrderAreParsedQuickly() {
        // Media3 searches its time list from the end for every start and end: lines listed latest first are re-sorted.
        val bytes = ass(30_000) { val n = 30_000 - it; "Dialogue: 0,${cs(n * 4)},${cs(n * 4 + 2)},Default,,0,0,0,,Line $n" }.toByteArray()
        val started = System.nanoTime()
        val t = SubtitleFiles.parse(bytes, "ass")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("parsed in $ms ms", ms < 4_000)
        assertEquals(listOf("Line 1"), texts(t, 45))
    }

    @Test fun mediaThreesTimeSearchIsBudgetedForTheOrderTheLinesAreHandedOver() {
        val n = 10_000
        val starts = LongArray(n) { (n - it) * 4L }
        val ends = LongArray(n) { (n - it) * 4L + 2 }
        assertTrue(runCatching { AssScript.checkBudget(starts, ends) }.exceptionOrNull()?.message == "Too many subtitles.")
        AssScript.checkBudget(starts.reversedArray(), ends.reversedArray())
    }

    @Test fun aSubRipLineOfUnclosedTagsIsParsedQuickly() {
        // Media3's SubRip tag pattern `\{\\.*?\}` would scan to the end of the line from every unclosed `{\`.
        val bytes = ("1\n00:00:01,000 --> 00:00:02,000\nStart" + "{\\".repeat(60_000) + "\n").toByteArray()
        val started = System.nanoTime()
        val t = SubtitleFiles.parse(bytes, "srt")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("parsed in $ms ms", ms < 2_000)
        assertEquals("Start", texts(t, 1_500).single().take(5))
    }

    @Test fun mediaThreeParsesOnlyTheLinesTheBudgetCounted() {
        val hostile = "$head[Events]\n$format\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Kept\n" +
            "﻿Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,After a byte order mark\n" +
            "dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,Lower case\n" +
            "Comment: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,Comment\n"
        val out = String(AssScript.normalise(hostile.toByteArray()), Charsets.UTF_8)
        val cueLines = out.lines().filter { it.startsWith("Dialogue:") }
        assertEquals(listOf("Dialogue: 0,0:00:01.00,0:00:02.00,Default,Kept", "Dialogue: 0,0:00:03.00,0:00:04.00,Default,After a byte order mark"), cueLines)
        assertEquals("one Format line, ours", listOf(AssScript.FORMAT), out.lines().dropWhile { !it.equals("[Events]", true) }.filter { it.startsWith("Format:") })
        assertTrue("no carriage return or byte order mark", '\r' !in out && '﻿' !in out)
    }
}
