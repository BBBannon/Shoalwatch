package app.shoalwatch.data.playback.engine

import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.TreeSet

/**
 * Text subtitle files parsed with Media3's own parsers (SubRip, WebVTT, SSA/ASS), as untrusted input: called off the main
 * thread by `SubtitleLoader` with a size already checked. Nothing from the file is logged or put in an exception message
 * (Media3's own logging is switched off app-wide). What comes out is only text, the limited styling Media3 draws (bold,
 * italic, underline, strike-through, colours, sizes, alignment, position, ASS layer order), and nothing else: links, images
 * and any other span are removed, each line is cut to [MAX_LINE_CHARS] characters and each cue to [MAX_LINES] lines.
 *
 * Bounded in work as well as in bytes (Task 7 review I-2, final review I-1): an ASS/SSA file is decoded and re-written by the
 * app ([AssScript]) so that Media3 parses only the cue lines the work budget counted, and is refused before Media3 sees it when
 * those lines would make Media3's parser build quadratic lists; a SubRip file is re-written with its lines cut and its braces
 * closed ([SubtitleText.subRip]); only the cues that can be drawn at once are cleaned; running out of memory is "unreadable",
 * not a crash (a last line of defence); and a parse that has been given up ([cancelled]) stops at the next cue.
 */
@OptIn(UnstableApi::class)
internal object SubtitleFiles {
    const val MAX_GROUPS = 50_000
    const val MAX_LINE_CHARS = 1_000
    const val MAX_LINES = 20
    /** Cues drawn at one instant (a hostile file could stack thousands). */
    const val MAX_CUES_AT_ONCE = 16
    private const val MAX_TIME_US = 48L * 60 * 60 * 1_000_000
    /** A cue with no end shows for this long. */
    private const val UNSET_DURATION_US = 5_000_000L

    private val MIME = mapOf("srt" to MimeTypes.APPLICATION_SUBRIP, "vtt" to MimeTypes.TEXT_VTT, "ass" to MimeTypes.TEXT_SSA, "ssa" to MimeTypes.TEXT_SSA)

    /** For tests: called once for every cue cleaned. */
    @Volatile internal var cleanHook: (() -> Unit)? = null

    /**
     * Throws [IllegalArgumentException] (a fixed message) for anything unreadable, including a parser failure, a file over the
     * work budget, running out of memory, and [cancelled] turning true (checked at every cue and while building the timeline).
     */
    fun parse(bytes: ByteArray, format: String, cancelled: () -> Boolean = { false }): CueTimeline = guarded {
        val mime = MIME[format] ?: throw IllegalArgumentException("Unsupported subtitle format.")
        val factory = DefaultSubtitleParserFactory()
        val media = Format.Builder().setSampleMimeType(mime).build()
        if (!factory.supportsFormat(media)) throw IllegalArgumentException("Unsupported subtitle format.")
        val ass = format == "ass" || format == "ssa"
        val input = when {
            ass -> AssScript.normalise(bytes, cancelled)
            format == "srt" -> SubtitleText.subRip(bytes)
            else -> bytes
        }
        val groups = ArrayList<Group>()
        factory.create(media).parse(input, SubtitleParser.OutputOptions.allCues()) { c ->
            if (cancelled()) throw IllegalArgumentException("Stopped.")
            groups.add(groupOf(c, groups.size) ?: return@parse)
        }
        CueTimeline.of(groups, ass && AssEffects.found(input), cancelled)
    }

    /**
     * [block], with every failure as the fixed [IllegalArgumentException]: Media3's runtime exceptions, a stack overflow, and
     * running out of memory (a last line of defence: [AssScript] is the bound).
     */
    internal inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("Unreadable subtitles.")
    } catch (e: StackOverflowError) {
        throw IllegalArgumentException("Unreadable subtitles.")
    } catch (e: OutOfMemoryError) {
        throw IllegalArgumentException("Unreadable subtitles.")
    }

    internal class Group(val startUs: Long, val endUs: Long, val seq: Int, val cues: List<Cue>)

    private fun groupOf(c: CuesWithTiming, count: Int): Group? {
        if (count >= MAX_GROUPS) throw IllegalArgumentException("Too many subtitles.")
        if (c.startTimeUs == C.TIME_UNSET) return null
        val start = c.startTimeUs.coerceAtLeast(0)
        val end = if (c.durationUs == C.TIME_UNSET) start + UNSET_DURATION_US else c.endTimeUs
        if (end <= start || start > MAX_TIME_US) return null
        // Lazily: a group can hold every line showing at that moment, and only the first few can be drawn.
        val cues = c.cues.asSequence().mapNotNull(::safe).take(MAX_CUES_AT_ONCE).toList()
        return if (cues.isEmpty()) null else Group(start, end.coerceAtMost(MAX_TIME_US), count, cues)
    }

    /** Text only as text and the styles above; a cue with nothing left to show is dropped. */
    private fun safe(cue: Cue): Cue? {
        val text = cue.text ?: return if (cue.bitmap != null) cue else null
        val clean = cleanText(text)
        return if (clean.isBlank()) null else cue.buildUpon().setText(clean).build()
    }

    internal fun cleanText(text: CharSequence): CharSequence {
        cleanHook?.invoke()
        val out = SpannableStringBuilder()
        var lineStart = 0
        var lines = 0
        while (lineStart <= text.length && lines < MAX_LINES) {
            val newline = indexOf(text, '\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            if (lines > 0) out.append('\n')
            out.append(text, lineStart, minOf(lineEnd, lineStart + MAX_LINE_CHARS))
            lines++
            if (newline < 0) break
            lineStart = newline + 1
        }
        for (span in out.getSpans(0, out.length, Any::class.java)) if (!allowed(span)) out.removeSpan(span)
        for (i in out.length - 1 downTo 0) if (out[i] == OBJECT_CHAR) out.delete(i, i + 1)
        return if (out.getSpans(0, out.length, Any::class.java).isEmpty()) out.toString() else out
    }

    private fun indexOf(text: CharSequence, c: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == c) return i
        return -1
    }

    private const val OBJECT_CHAR = '￼'

    private fun allowed(span: Any) = span is StyleSpan || span is UnderlineSpan || span is StrikethroughSpan || span is ForegroundColorSpan ||
        span is BackgroundColorSpan || span is RelativeSizeSpan || span is AbsoluteSizeSpan ||
        span.javaClass.name.startsWith("androidx.media3.common.text.")
}

/**
 * All the cues of one text subtitle file in time order, for drawing at (position - delay). Built once (a sweep over every
 * start and end, so overlapping cues show together, in start order; at most [SubtitleFiles.MAX_CUES_AT_ONCE] at an instant);
 * [frameAt] is a binary search, cheap enough for the main thread many times a second.
 */
class CueTimeline internal constructor(
    private val startsUs: LongArray,
    private val frames: List<SubtitleFrame>,
    /** `\k`, `\K`, `\kf`, `\ko`, `\t(`, `\move(`, `\fad(`, `\fade(`, `\p1`..`\p9`, `\clip(`, `\iclip(` or `\fn` found in the ASS events. */
    val usesEffectsThePhoneCannotDraw: Boolean,
) {
    /** How many changes of what is shown the file has. */
    val size: Int get() = frames.size

    fun frameAt(positionMs: Long): SubtitleFrame {
        if (positionMs < 0 || startsUs.isEmpty()) return SubtitleFrame.NONE
        val us = positionMs * 1_000
        var lo = 0
        var hi = startsUs.size - 1
        if (us < startsUs[0]) return SubtitleFrame.NONE
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startsUs[mid] <= us) lo = mid else hi = mid - 1
        }
        return frames[lo]
    }

    override fun toString() = "CueTimeline(<redacted>)"

    internal companion object {
        fun of(groups: List<SubtitleFiles.Group>, effects: Boolean, cancelled: () -> Boolean = { false }): CueTimeline {
            val byStart = groups.sortedWith(compareBy({ it.startUs }, { it.seq }))
            val boundaries = (byStart.map { it.startUs } + byStart.map { it.endUs }).distinct().sorted()
            val active = TreeSet<Int>() // positions in byStart: start order
            val byEnd = byStart.indices.sortedBy { byStart[it].endUs }
            var nextStart = 0
            var nextEnd = 0
            val starts = ArrayList<Long>()
            val frames = ArrayList<SubtitleFrame>()
            for (t in boundaries) {
                if (cancelled()) throw IllegalArgumentException("Stopped.")
                while (nextEnd < byEnd.size && byStart[byEnd[nextEnd]].endUs <= t) active.remove(byEnd[nextEnd++])
                while (nextStart < byStart.size && byStart[nextStart].startUs <= t) {
                    if (byStart[nextStart].endUs > t) active.add(nextStart)
                    nextStart++
                }
                val cues = active.asSequence().flatMap { byStart[it].cues.asSequence() }.take(SubtitleFiles.MAX_CUES_AT_ONCE).toList()
                val frame = if (cues.isEmpty()) SubtitleFrame.NONE else SubtitleFrame(cues)
                if (frames.isNotEmpty() && frame.isEmpty && frames.last().isEmpty) continue
                starts += t
                frames += frame
            }
            return CueTimeline(starts.toLongArray(), frames, effects)
        }
    }
}

/** Finds the ASS override tags Media3 cannot draw, in `Dialogue:` lines' `{...}` blocks, on the re-written bytes (no second copy). */
internal object AssEffects {
    private val TAGS = listOf("t(", "move(", "fad(", "fade(", "clip(", "iclip(", "fn").map { it.toByteArray() }
    private val DIALOGUE = "dialogue:".toByteArray()

    fun found(bytes: ByteArray): Boolean {
        var i = 0
        var dialogue = false
        var lineStart = true
        var inBlock = false
        while (i < bytes.size) {
            val b = bytes[i]
            if (lineStart) { dialogue = startsWithIgnoringCase(bytes, i, DIALOGUE); lineStart = false; inBlock = false }
            when {
                b == '\n'.code.toByte() -> lineStart = true
                !dialogue -> Unit
                b == '{'.code.toByte() -> inBlock = true
                b == '}'.code.toByte() -> inBlock = false
                inBlock && b == '\\'.code.toByte() && unsupportedAt(bytes, i + 1) -> return true
            }
            i++
        }
        return false
    }

    private fun unsupportedAt(bytes: ByteArray, at: Int): Boolean {
        val c = bytes.getOrNull(at)?.toInt()?.toChar() ?: return false
        val next = bytes.getOrNull(at + 1)?.toInt()?.toChar()
        if ((c == 'k' || c == 'K') && next != null && (next.isDigit() || next == 'f' || next == 'o')) return true
        if (c == 'p' && next != null && next in '1'..'9') return true
        return TAGS.any { tag -> tag.indices.all { j -> bytes.getOrNull(at + j) == tag[j] } }
    }

    private fun startsWithIgnoringCase(bytes: ByteArray, at: Int, word: ByteArray): Boolean =
        word.indices.all { j -> bytes.getOrNull(at + j)?.toInt()?.toChar()?.lowercaseChar() == word[j].toInt().toChar() }
}
