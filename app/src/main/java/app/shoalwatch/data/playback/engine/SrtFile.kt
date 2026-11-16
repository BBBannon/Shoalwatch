package app.shoalwatch.data.playback.engine

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * SubRip, read by the app (also what the server sends for `mov_text` and every other text codec it converts). One pass over
 * the lines; each line is looked at a fixed number of times.
 *
 * - A cue starts at a line holding `-->` (an index line before it is optional and ignored, as is any other stray line):
 *   `[HH:]MM:SS,mmm --> [HH:]MM:SS,mmm` (`.` also accepted, 1 to 3 fraction digits, anything after the end time ignored).
 *   A start or end that cannot be read (a negative time among them) drops that cue; so does an end before its start.
 * - Its text is every line up to the next blank one, each trimmed, through [Markup] (`<i>`, `<b>`, `<u>`, `{\anN}`).
 */
@OptIn(UnstableApi::class)
internal object SrtFile {
    fun read(lines: SubtitleLines, out: Drafts) {
        var line = lines.next()
        while (line != null) {
            val arrow = line.indexOf(Times.ARROW)
            if (arrow < 0) { line = lines.next(); continue }
            out.count()
            val start = whole(line.substring(0, arrow).trim())
            val end = Times.read(line, skipSpaces(line, arrow + 3), ".,", fractionRequired = false)?.ms
            val text = CueText()
            val markup = Markup(subRip = true)
            line = lines.next()
            while (line != null && line.isNotBlank()) {
                if (start != null && end != null && !text.full) {
                    if (!text.isEmpty) text.newLine()
                    markup.line(line.trim(), text)
                }
                line = lines.next()
            }
            if (start != null && end != null) out.add(start, end, 0, cueOf(text, markup.alignment))
        }
    }

    private fun whole(s: String): Long? = Times.read(s, 0, ".,", fractionRequired = false)?.takeIf { it.end == s.length }?.ms

    internal fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }

    /** As Media3's SubRip parser placed `{\anN}`: 8% in from the side or edge it names, otherwise centred. */
    private fun cueOf(text: CueText, alignment: Int): Cue? {
        val t = text.build() ?: return null
        val cue = Cue.Builder().setText(t)
        if (alignment != 0) {
            val positionAnchor = when (alignment) { 1, 4, 7 -> Cue.ANCHOR_TYPE_START; 3, 6, 9 -> Cue.ANCHOR_TYPE_END; else -> Cue.ANCHOR_TYPE_MIDDLE }
            val lineAnchor = when (alignment) { 7, 8, 9 -> Cue.ANCHOR_TYPE_START; 4, 5, 6 -> Cue.ANCHOR_TYPE_MIDDLE; else -> Cue.ANCHOR_TYPE_END }
            cue.setPositionAnchor(positionAnchor).setPosition(fraction(positionAnchor))
                .setLineAnchor(lineAnchor).setLine(fraction(lineAnchor), Cue.LINE_TYPE_FRACTION)
        }
        return cue.build()
    }

    private fun fraction(anchor: Int) = when (anchor) { Cue.ANCHOR_TYPE_START -> 0.08f; Cue.ANCHOR_TYPE_END -> 0.92f; else -> 0.5f }
}
