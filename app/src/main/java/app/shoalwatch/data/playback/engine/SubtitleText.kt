package app.shoalwatch.data.playback.engine

/**
 * An untrusted text subtitle file decoded once by the app and re-written before Media3 sees it (final review I-1), so that
 * what Media3 parses is exactly what the app has checked: the bytes handed over are the UTF-8 of the app's own lines, ended by
 * `\n` only, none starting with a byte order mark. Media3 1.9.2 (`ParsableByteArray.readLine`) then splits them into the same
 * lines the app read.
 */
internal object SubtitleText {
    /** A SubRip line is cut here before Media3 sees it: its tag removal costs the square of a line's length. */
    const val MAX_SRT_LINE_CHARS = 2 * SubtitleFiles.MAX_LINE_CHARS

    /**
     * The lines of [bytes] as Media3's SubRip and SSA parsers read them: the charset from a byte order mark (UTF-8, UTF-16BE,
     * UTF-16LE), otherwise UTF-8; split at `\r\n`, `\n` or a lone `\r`; leading byte order marks dropped from every line
     * (`readLine` drops one at the start of each line; dropping all of them means the re-written lines have none to drop).
     */
    fun lines(bytes: ByteArray): List<String> {
        val text = decode(bytes)
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                out += text.substring(start, i).trimStart('﻿')
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) out += text.substring(start).trimStart('﻿')
        return out
    }

    private fun decode(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        else -> String(b, Charsets.UTF_8)
    }

    /** [lines] as UTF-8, each ended by `\n`. */
    fun encode(lines: StringBuilder): ByteArray = lines.toString().toByteArray(Charsets.UTF_8)

    /**
     * [line] without any `{` that has no `}` after it. Media3's tag patterns (`\{([^}]*)\}` for SSA, `\{\\.*?\}` for SubRip)
     * scan from every unclosed `{` to the end of the line, so a line of them costs the square of its length; with every `{`
     * closed, each match ends at the next `}` and the whole line is one pass.
     */
    fun closeBraces(line: String): String {
        val last = line.lastIndexOf('}')
        if (line.indexOf('{', last + 1) < 0) return line
        return line.substring(0, last + 1) + line.substring(last + 1).replace("{", "")
    }

    /** A SubRip file re-written: each line cut to [MAX_SRT_LINE_CHARS] (a surrogate pair is not split) and its braces closed. */
    fun subRip(bytes: ByteArray): ByteArray {
        val out = StringBuilder(bytes.size + 16)
        for (line in lines(bytes)) out.append(closeBraces(cut(line))).append('\n')
        return encode(out)
    }

    private fun cut(line: String): String {
        if (line.length <= MAX_SRT_LINE_CHARS) return line
        val end = if (Character.isHighSurrogate(line[MAX_SRT_LINE_CHARS - 1])) MAX_SRT_LINE_CHARS - 1 else MAX_SRT_LINE_CHARS
        return line.substring(0, end)
    }

    /** Java's `String.trim()`: every character up to a space, at both ends (Media3 trims this way, not by `isWhitespace`). */
    fun javaTrim(s: String): String = s.trim { it <= ' ' }
}

/**
 * An ASS/SSA file re-written so that Media3 parses only cue lines the app has read and counted (final review I-1).
 *
 * - The header (everything before the first line equal, ignoring case, to `[Events]`) passes through, without any
 *   `Dialogue:` line; Media3 builds only its script info and style map from it (one pass, a map by name).
 * - In the events, as Media3 reads them: `Format:` and `Dialogue:` are matched case-sensitively at the start of the line, the
 *   latest `Format:` governs (another `[Section]` does not end the events), its fields are split on `,` and named ignoring
 *   ASCII case (the last of a name wins), and a `Format:` without Start, End and Text leaves the lines after it unreadable.
 *   A `Dialogue:` line is split into exactly that many fields; Start and End are trimmed as Java trims and read as
 *   `[H:]M:S[:.]F` (F in hundredths whatever its length, leading zeros of any length); a line that cannot be read, or ends
 *   before it starts, or starts after 48 hours, is dropped. Every `Dialogue:` line counts towards the line cap, read or not.
 * - What Media3 is given: the header, a blank line, `[Events]`, the app's own [FORMAT] and the read lines sorted by start, each
 *   as `Dialogue: Layer,H:MM:SS.cc,H:MM:SS.cc,Style,Text`, with every unclosed `{` removed from the text. No other line in the
 *   events reaches Media3, so its parser cannot see a line, a time or a Format the budget did not.
 * - The budget ([checkBudget]) is worked out on exactly those lines in that order: Media3 keeps one list per distinct time and
 *   adds each line to every list its time spans (K overlapping lines cost about K squared / 2 references), and finds each time
 *   by searching its list from the end (lines listed latest first cost the square of their number, which the sort removes).
 */
internal object AssScript {
    const val MAX_PLACEMENTS = 2_000_000L
    /** Steps of Media3's search of its time list from the end, and the matching shifts when a time is inserted. */
    const val MAX_SEARCH_STEPS = 20_000_000L
    const val FORMAT = "Format: Layer, Start, End, Style, Text"
    private const val MAX_CS = 48L * 60 * 60 * 100
    private const val EVENTS = "[Events]"

    private class Fields(val layer: Int, val start: Int, val end: Int, val style: Int, val text: Int, val count: Int)
    private class Line(val layer: Int, val start: Long, val end: Long, val style: String, val text: String)

    /** Throws the fixed "Too many subtitles." over the line cap or the budget, and "Stopped." once [cancelled]. */
    fun normalise(bytes: ByteArray, cancelled: () -> Boolean = { false }): ByteArray {
        val all = SubtitleText.lines(bytes)
        val events = all.indexOfFirst { it.equals(EVENTS, ignoreCase = true) }
        val read = ArrayList<Line>()
        if (events >= 0) {
            var format: Fields? = null
            var count = 0
            for (n in events + 1 until all.size) {
                if (n and 0x3FF == 0 && cancelled()) throw IllegalArgumentException("Stopped.")
                val line = all[n]
                if (line.startsWith("Format:")) {
                    format = fieldsOf(line)
                } else if (line.startsWith("Dialogue:")) {
                    if (++count > SubtitleFiles.MAX_GROUPS) throw IllegalArgumentException("Too many subtitles.")
                    format?.let { lineOf(line, it) }?.let { read += it }
                }
            }
        }
        read.sortBy { it.start } // stable: lines starting together keep the file's order
        checkBudget(LongArray(read.size) { read[it].start }, LongArray(read.size) { read[it].end })
        val out = StringBuilder(bytes.size + 64)
        for (n in 0 until (if (events >= 0) events else all.size)) if (!all[n].startsWith("Dialogue:")) out.append(all[n]).append('\n')
        // The blank line: Media3's script-info and style loops read the line after their heading before looking for the next
        // `[`, so `[Events]` straight after a heading would be eaten; after a blank line it is always read as the heading.
        out.append('\n').append(EVENTS).append('\n').append(FORMAT).append('\n')
        for (l in read) {
            out.append("Dialogue: ").append(l.layer).append(',').append(clock(l.start)).append(',').append(clock(l.end))
                .append(',').append(l.style).append(',').append(l.text).append('\n')
        }
        return SubtitleText.encode(out)
    }

    /** As `SsaDialogueFormat.fromFormatLine`: null without Start, End and Text. */
    private fun fieldsOf(line: String): Fields? {
        val rest = line.substring("Format:".length)
        val keys = if (rest.isEmpty()) emptyList() else rest.split(',')
        var layer = -1; var start = -1; var end = -1; var style = -1; var text = -1
        keys.forEachIndexed { i, k ->
            when (asciiLower(SubtitleText.javaTrim(k))) {
                "layer" -> layer = i
                "start" -> start = i
                "end" -> end = i
                "style" -> style = i
                "text" -> text = i
            }
        }
        return if (start < 0 || end < 0 || text < 0) null else Fields(layer, start, end, style, text, keys.size)
    }

    private fun lineOf(line: String, f: Fields): Line? {
        val v = line.substring("Dialogue:".length).split(",", limit = f.count)
        if (v.size != f.count) return null
        val start = centiseconds(v[f.start]) ?: return null
        val end = minOf(centiseconds(v[f.end]) ?: return null, MAX_CS)
        if (start > MAX_CS || end <= start) return null
        val layer = if (f.layer < 0) 0 else SubtitleText.javaTrim(v[f.layer]).toIntOrNull() ?: 0
        val style = if (f.style < 0) "" else SubtitleText.javaTrim(v[f.style])
        return Line(layer, start, end, style, SubtitleText.closeBraces(v[f.text]))
    }

    /** `(?:(\d+):)?(\d+):(\d+)[:.](\d+)` on the trimmed field, in hundredths; null when it is not that (or a part has over 12 digits, which is over 48 hours). */
    private fun centiseconds(field: String): Long? {
        val s = SubtitleText.javaTrim(field)
        val parts = ArrayList<Long>(4)
        val seps = StringBuilder(3)
        var i = 0
        while (i <= s.length) {
            var j = i
            while (j < s.length && s[j] in '0'..'9') j++
            if (j == i) return null
            var k = i
            while (k < j - 1 && s[k] == '0') k++ // leading zeros do not count towards the length
            if (j - k > 12) return null
            parts += s.substring(k, j).toLong()
            if (parts.size > 4) return null
            if (j == s.length) break
            seps.append(s[j])
            i = j + 1
        }
        val (h, m, sec, f) = when {
            parts.size == 4 && seps[0] == ':' && seps[1] == ':' && (seps[2] == ':' || seps[2] == '.') -> parts
            parts.size == 3 && seps[0] == ':' && (seps[1] == ':' || seps[1] == '.') -> listOf(0L) + parts
            else -> return null
        }
        return h * 360_000 + m * 6_000 + sec * 100 + f // each part under 10^12: no overflow
    }

    private fun clock(cs: Long) = "%d:%02d:%02d.%02d".format(cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)

    private fun asciiLower(s: String) = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    /**
     * The work Media3's `SsaParser` does for lines with these times, handed over in this order: refuses more than
     * [MAX_PLACEMENTS] cue references in all, or more than [MAX_SEARCH_STEPS] steps finding and inserting times.
     */
    fun checkBudget(starts: LongArray, ends: LongArray) {
        if (starts.isEmpty()) return
        val times = (starts + ends).distinct().sorted().toLongArray()
        val present = BooleanArray(times.size)
        val tree = IntArray(times.size + 1) // a Fenwick tree of the times Media3 has listed so far
        var listed = 0
        var steps = 0L
        fun visit(t: Long) {
            val at = times.binarySearch(t)
            var upTo = 0
            var x = at + 1
            while (x > 0) { upTo += tree[x]; x -= x and -x }
            steps += listed - upTo + 1 // Media3 walks back past every later time to the one that stops it
            if (!present[at]) {
                present[at] = true
                listed++
                x = at + 1
                while (x <= times.size) { tree[x]++; x += x and -x }
            }
            if (steps > MAX_SEARCH_STEPS) throw IllegalArgumentException("Too many subtitles.")
        }
        for (n in starts.indices) { visit(starts[n]); visit(ends[n]) }
        var placements = 0L
        for (n in starts.indices) {
            placements += times.binarySearch(ends[n]) - times.binarySearch(starts[n])
            if (placements > MAX_PLACEMENTS) throw IllegalArgumentException("Too many subtitles.")
        }
    }
}
