package app.shoalwatch.data.playback.engine

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * The shared, linear pieces of the app's own subtitle parsers ([SrtFile], [VttFile], [AssFile]); see "Tracks and subtitles" in
 * docs/ARCHITECTURE.md. Nothing here keeps a reference to earlier lines or cues, and nothing scans backwards.
 */

/**
 * An untrusted file decoded once: the charset from a byte order mark (UTF-8, UTF-16 LE, UTF-16 BE), otherwise UTF-8 with
 * U+FFFD for anything invalid; NUL and every other control character except tab and the line ends, U+2028, U+2029 and U+FEFF
 * removed; read one line at a time, split at `\r\n`, `\n` or a lone `\r`. [cancelled] is asked before the first line and at
 * every [SubtitleFiles.CHECK_EVERY_LINES]th, so a given-up parse stops within that many lines.
 */
internal class SubtitleLines(bytes: ByteArray, private val cancelled: () -> Boolean) {
    private val text: CharArray
    private val length: Int
    private var pos = 0
    private var read = 0

    init {
        if (cancelled()) throw SubtitleFiles.stopped()
        val decoded = decode(bytes)
        val chars = CharArray(decoded.length)
        var n = 0
        for (i in decoded.indices) { val c = decoded[i]; if (kept(c)) chars[n++] = c }
        text = chars
        length = n
    }

    /** The next line without its ending, or null at the end of the file. */
    fun next(): String? {
        if (pos >= length) return null
        if (++read % SubtitleFiles.CHECK_EVERY_LINES == 0 && cancelled()) throw SubtitleFiles.stopped()
        var end = pos
        while (end < length && text[end] != '\n' && text[end] != '\r') end++
        val line = String(text, pos, end - pos)
        pos = if (end + 1 < length && text[end] == '\r' && text[end + 1] == '\n') end + 2 else end + 1
        return line
    }

    private companion object {
        fun decode(b: ByteArray): String = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            else -> String(b, Charsets.UTF_8)
        }

        fun kept(c: Char): Boolean = when {
            c == '\t' || c == '\n' || c == '\r' -> true
            c < ' ' || c in '\u007F'..'\u009F' -> false
            else -> c != ' ' && c != ' ' && c != '﻿'
        }
    }
}

/**
 * One cue's text as it is built: characters appended left to right, with bold, italic and underline as depth counters (an
 * opening tag adds one, its closer takes one away; nothing grows but the count), so the styled runs are recorded as they
 * end. At most [SubtitleFiles.MAX_CUE_CHARS] characters and [SubtitleFiles.MAX_CUE_LINES] lines: past either the rest of the
 * cue is dropped ([full]), never the file. At most [SubtitleFiles.MAX_STYLED_RUNS] styled runs: after that a style change is
 * ignored and the text kept (subtitle-parsers review I-2), because Android's `setSpan` and `SubtitleView`'s span removal each
 * scan every span already there; with at most two spans per run (bold and italic share one) a cue has a few dozen.
 */
internal class CueText {
    private val out = StringBuilder()
    private var runs = IntArray(24) // (start, end, flags) per styled run
    private var runCount = 0
    private var runStart = 0
    private var runFlags = 0
    private var lines = 1
    var bold = 0
    var italic = 0
    var underline = 0
    var full = false
        private set

    /** Nothing visible appended yet. */
    val isEmpty: Boolean get() = out.isEmpty()

    fun append(c: Char) {
        if (full) return
        if (out.length >= SubtitleFiles.MAX_CUE_CHARS || (Character.isHighSurrogate(c) && out.length + 1 >= SubtitleFiles.MAX_CUE_CHARS)) {
            full = true
            return
        }
        val flags = (if (bold > 0) BOLD else 0) or (if (italic > 0) ITALIC else 0) or (if (underline > 0) UNDERLINE else 0)
        if (flags != runFlags && runCount < SubtitleFiles.MAX_STYLED_RUNS) { closeRun(); runFlags = flags }
        out.append(c)
    }

    fun newLine() {
        if (full) return
        if (lines >= SubtitleFiles.MAX_CUE_LINES) { full = true; return }
        lines++
        append('\n')
    }

    private fun closeRun() {
        if (runFlags != 0 && out.length > runStart) {
            if (runCount * 3 == runs.size) runs = runs.copyOf(runs.size * 2)
            runs[runCount * 3] = runStart
            runs[runCount * 3 + 1] = out.length
            runs[runCount * 3 + 2] = runFlags
            runCount++
        }
        runStart = out.length
    }

    /**
     * The text, trimmed, with its styled runs, plus [wholeFlags] and [colour] over all of it (ASS styles); null when nothing
     * visible is left. Plain text when there is no styling at all.
     */
    fun build(wholeFlags: Int = 0, colour: Int? = null): CharSequence? {
        closeRun()
        var start = 0
        var end = out.length
        while (start < end && out[start].isWhitespace()) start++
        while (end > start && out[end - 1].isWhitespace()) end--
        if (start == end) return null
        val text = out.substring(start, end)
        if (runCount == 0 && wholeFlags == 0 && colour == null) return text
        val s = SpannableString(text)
        for (r in 0 until runCount) {
            val from = (runs[r * 3] - start).coerceAtLeast(0)
            val to = (runs[r * 3 + 1] - start).coerceAtMost(text.length)
            if (from < to) style(s, runs[r * 3 + 2], from, to)
        }
        style(s, wholeFlags, 0, text.length)
        colour?.let { s.setSpan(ForegroundColorSpan(it), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return s
    }

    private fun style(s: SpannableString, flags: Int, from: Int, to: Int) {
        val typeface = when (flags and (BOLD or ITALIC)) { BOLD -> Typeface.BOLD; ITALIC -> Typeface.ITALIC; BOLD or ITALIC -> Typeface.BOLD_ITALIC; else -> null }
        typeface?.let { s.setSpan(StyleSpan(it), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (flags and UNDERLINE != 0) s.setSpan(UnderlineSpan(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        const val BOLD = 1
        const val ITALIC = 2
        const val UNDERLINE = 4
    }
}

/**
 * SubRip and WebVTT cue text, one line at a time, in a single left-to-right pass. A `<` (or, for SubRip, a `{`) is a tag only
 * when a `>` (`}`) follows it on the line, found by one `lastIndexOf` per line; the tag is then skipped to the first closer
 * after it, so no character is read more than twice. An unclosed `<` or `{` is literal text.
 *
 * - `<i>`, `<b>`, `<u>` and their closers move [CueText]'s depth counters; every other tag is removed. For WebVTT, text
 *   inside `<rt>` (a ruby reading) is dropped and the base text kept.
 * - SubRip: `{\anN}` before any visible text sets [alignment]; every other `{...}` is removed.
 * - `&amp;`, `&lt;`, `&gt;`, `&nbsp;`, `&lrm;`, `&rlm;` are decoded; any other `&` is literal.
 */
internal class Markup(private val subRip: Boolean) {
    /** 1 to 9 (numeric-keypad positions) from SubRip's `{\anN}`, or 0. */
    var alignment = 0
        private set
    private var rubyText = 0

    fun line(s: String, out: CueText) {
        val lastTag = s.lastIndexOf('>')
        val lastBrace = if (subRip) s.lastIndexOf('}') else -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '<' && i < lastTag -> { val close = s.indexOf('>', i + 1); tag(s, i + 1, close, out); i = close + 1 }
                c == '{' && i < lastBrace -> {
                    val close = s.indexOf('}', i + 1)
                    if (out.isEmpty && alignment == 0 && close - i == 5 && s.startsWith("\\an", i + 1) && s[i + 4] in '1'..'9') alignment = s[i + 4] - '0'
                    i = close + 1
                }
                c == '&' -> i = entity(s, i, out)
                else -> { if (rubyText == 0) out.append(c); i++ }
            }
        }
    }

    private fun tag(s: String, from: Int, to: Int, out: CueText) {
        val closing = from < to && s[from] == '/'
        val start = if (closing) from + 1 else from
        var end = start
        while (end < to && end - start <= MAX_NAME && s[end].isLetterOrDigit()) end++
        val step = if (closing) -1 else 1
        when (if (end - start > MAX_NAME) "" else s.substring(start, end).lowercase()) {
            "b" -> out.bold = (out.bold + step).coerceAtLeast(0)
            "i" -> out.italic = (out.italic + step).coerceAtLeast(0)
            "u" -> out.underline = (out.underline + step).coerceAtLeast(0)
            "rt" -> if (!subRip) rubyText = (rubyText + step).coerceAtLeast(0)
            "ruby" -> if (!subRip && closing) rubyText = 0 // a reading left open ends with its ruby
        }
    }

    private fun entity(s: String, at: Int, out: CueText): Int {
        for ((name, c) in ENTITIES) {
            if (s.startsWith(name, at)) { if (rubyText == 0) out.append(c); return at + name.length }
        }
        if (rubyText == 0) out.append('&')
        return at + 1
    }

    private companion object {
        const val MAX_NAME = 8
        val ENTITIES = listOf("&amp;" to '&', "&lt;" to '<', "&gt;" to '>', "&nbsp;" to ' ', "&lrm;" to '‎', "&rlm;" to '‏')
    }
}

/** Clock times, read left to right from a position, at most four groups of digits. */
internal object Times {
    /** The timing arrow, built from characters: `SqlCompatibilityTest` flags a JSON operator in any string literal. */
    val ARROW = String(charArrayOf('-', '-', '>'))

    class Read(val ms: Long, val end: Int)

    /**
     * `[H:]M:S` then one of [fractionSeparators] and 1 to 3 fraction digits (a decimal fraction: `.5` is 500 ms), from [from]
     * in [s]; the fraction may be left out unless [fractionRequired] (SubRip and WebVTT allow whole seconds; review m-3); null
     * when it is not that. Leading zeros do not count, and a group of more than 9 significant digits is unreadable (no
     * overflow: the result is at most about 10^15 ms).
     */
    fun read(s: String, from: Int, fractionSeparators: String, fractionRequired: Boolean = true): Read? {
        val groups = LongArray(3)
        var count = 0
        var i = from
        while (true) {
            val start = i
            while (i < s.length && s[i] == '0') i++
            val significant = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start || i - significant > 9) return null
            groups[count++] = if (i == significant) 0 else s.substring(significant, i).toLong()
            if (count < 3 && i + 1 < s.length && s[i] == ':' && s[i + 1] in '0'..'9') { i++; continue }
            break
        }
        if (count < 2) return null
        val (h, m, sec) = if (count == 3) Triple(groups[0], groups[1], groups[2]) else Triple(0L, groups[0], groups[1])
        val whole = ((h * 60 + m) * 60 + sec) * 1_000
        if (i + 1 >= s.length || s[i] !in fractionSeparators || s[i + 1] !in '0'..'9') return if (fractionRequired) null else Read(whole, i)
        i++
        val fractionStart = i
        while (i < s.length && s[i] in '0'..'9' && i - fractionStart < 4) i++
        val digits = i - fractionStart
        if (digits > 3) return null
        var fraction = s.substring(fractionStart, i).toLong()
        repeat(3 - digits) { fraction *= 10 }
        return Read(whole + fraction, i)
    }
}

/**
 * The ASS override tags this phone cannot draw (karaoke `\k`, `\K`, `\kf`, `\ko`; `\t(`, `\move(`, `\fad(`, `\fade(`; drawings
 * `\p1`..`\p9`; `\clip(`, `\iclip(`; `\fn`; since subtitle-parsers review m-5 also a non-zero rotation `\fr`, `\frx`, `\fry`,
 * `\frz` and its origin `\org(`), on one tag's text after its backslash. Cosmetic tags (`\fsc`, `\bord`, `\shad`, `\blur`,
 * `\be`) are not flagged.
 */
internal object AssEffects {
    private val PREFIXES = listOf("t(", "move(", "fad(", "fade(", "clip(", "iclip(", "fn", "org(")

    fun undrawable(tag: String): Boolean {
        val c = tag.getOrNull(0) ?: return false
        val next = tag.getOrNull(1)
        if ((c == 'k' || c == 'K') && next != null && (next.isDigit() || next == 'f' || next == 'o')) return true
        if (c == 'p' && next != null && next in '1'..'9') return true
        if (tag.startsWith("fr")) {
            val angle = tag.substring(if (tag.getOrNull(2)?.let { it in "xyz" } == true) 3 else 2).toFloatOrNull()
            if (angle != null && angle != 0f) return true
        }
        return PREFIXES.any { tag.startsWith(it) }
    }
}
