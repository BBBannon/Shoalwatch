package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import app.shoalwatch.data.playback.engine.SubtitleFiles
import app.shoalwatch.ui.browse.BEN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Subtitle parsing bounded in work, not only in bytes (Task 7 review I-2): a small hostile ASS file whose lines all overlap is
 * refused before Media3 builds its quadratic lists, cleaning is lazy, an out-of-memory parse is "unreadable", and an abandoned
 * parse stops at the next cue.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleBudgetTest {
    private var cleaned = 0

    @After fun unhook() { SubtitleFiles.cleanHook = null }

    private fun counting() { cleaned = 0; SubtitleFiles.cleanHook = { cleaned++ } }

    private fun cs(cs: Int) = "%d:%02d:%02d.%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)

    /** [lines] dialogue lines, each starting 10 ms after the last, all ending at 9:59:59.00. */
    private fun overlappingAss(lines: Int) = buildString {
        append("[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour\nStyle: Default,Arial,18,&H00FFFFFF\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        repeat(lines) { append("Dialogue: 0,").append(cs(it + 1)).append(",9:59:59.00,Default,,0,0,0,,Line ").append(it).append('\n') }
    }.toByteArray()

    private fun refused(block: () -> Unit) = runCatching(block).exceptionOrNull() is IllegalArgumentException

    @Test fun anAssFileWhoseLinesAllOverlapIsRefusedQuickly() {
        val bytes = overlappingAss(2_500) // about 150 KB; Media3 would hold about 3 million cue references (the budget is 2 million)
        val started = System.nanoTime()
        assertTrue(refused { SubtitleFiles.parse(bytes, "ass") })
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("refused in $ms ms", ms < 2_000)
    }

    @Test fun theBudgetFollowsTheEventsFormatLine() {
        // Start and End are not in the usual places: the pre-scan must read the Format line, not assume fields 1 and 2.
        val swapped = String(overlappingAss(2_500)).replace("Format: Layer, Start, End,", "Format: Layer, End, Start,")
            .replace(Regex("Dialogue: 0,([^,]+),9:59:59.00,"), "Dialogue: 0,9:59:59.00,$1,")
        assertTrue(refused { SubtitleFiles.parse(swapped.toByteArray(), "ass") })
    }

    @Test fun moreThanFiftyThousandDialogueLinesAreRefusedBeforeParsing() {
        val bytes = buildString {
            append("[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            repeat(SubtitleFiles.MAX_GROUPS + 1) { append("Dialogue: 0,").append(cs(it * 2)).append(',').append(cs(it * 2 + 1)).append(",Default,,0,0,0,,x\n") }
        }.toByteArray()
        counting()
        assertTrue(refused { SubtitleFiles.parse(bytes, "ass") })
        assertEquals("Media3 never ran", 0, cleaned)
    }

    @Test fun anOrdinaryOverlapWithinTheBudgetParsesAndCleansOnlyWhatIsDrawn() {
        val lines = 1_000 // about 500,000 placements: within the budget
        counting()
        val t = SubtitleFiles.parse(overlappingAss(lines), "ass")
        assertTrue(t.size > 0)
        assertTrue("at most ${SubtitleFiles.MAX_CUES_AT_ONCE} cues at an instant", t.frameAt(9_000).cues.size <= SubtitleFiles.MAX_CUES_AT_ONCE)
        // Media3 gives about one group per start, each holding every line still showing: only the first 16 of each are cleaned.
        assertTrue("cleaned $cleaned", cleaned <= (lines + 1) * SubtitleFiles.MAX_CUES_AT_ONCE)
    }

    @Test fun tenThousandFullyOverlappingSrtCuesFinishWithinTheCaps() {
        val bytes = buildString {
            repeat(10_000) { append(it + 1).append("\n00:00:01,000 --> 00:59:00,000\nCue ").append(it).append("\n\n") }
        }.toByteArray()
        counting()
        val t = SubtitleFiles.parse(bytes, "srt")
        assertEquals(SubtitleFiles.MAX_CUES_AT_ONCE, t.frameAt(2_000).cues.size)
        assertTrue("cleaned $cleaned", cleaned <= 10_000)
    }

    @Test fun runningOutOfMemoryWhileParsingIsUnreadableNotACrash() {
        assertTrue(refused { SubtitleFiles.guarded { throw OutOfMemoryError() } })
        assertTrue(refused { SubtitleFiles.guarded { throw IllegalStateException("Media3's own words") } })
        assertTrue(refused { SubtitleFiles.guarded { throw StackOverflowError() } })
    }

    @Test fun aCancelledParseStopsAtTheNextCue() {
        val bytes = buildString { repeat(2_000) { append(it + 1).append("\n00:00:").append("%02d".format(it % 60)).append(",000 --> 01:00:00,000\nx\n\n") } }.toByteArray()
        var asked = 0
        counting()
        assertTrue(refused { SubtitleFiles.parse(bytes, "srt") { ++asked > 5 } })
        assertTrue("stopped after about 5 cues, cleaned $cleaned", cleaned <= 6)
    }

    @Test fun anAbandonedParseIsToldToStop() = runBlocking {
        val gateway = FakePlaybackGateway().apply { subtitleFiles[3] = "1\n00:00:01,000 --> 00:00:02,000\nx\n".toByteArray() }
        val stopped = CompletableDeferred<Unit>()
        val loader = SubtitleLoader(gateway, Dispatchers.Default, { _, _, cancelled ->
            while (!cancelled()) Thread.sleep(5)
            stopped.complete(Unit)
            throw IllegalArgumentException("Stopped.")
        }, parseTimeoutMs = 100)
        val track = SubtitleTrack(3, "English", "eng", "subrip", SubtitleKind.TEXT, false, false, false, false, "srt")
        val e = runCatching { loader.load(BEN, ITEM, SOURCE, track) }.exceptionOrNull()
        assertEquals(AppError.Kind.SERVER_ERROR, (e as AppError).kind)
        withTimeout(2_000) { stopped.await() }
    }
}
