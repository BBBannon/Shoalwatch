package app.shoalwatch.data.playback.engine

import android.text.Layout
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * WebVTT, read by the app. One pass over the lines.
 *
 * - The first line must start with `WEBVTT` (then nothing, a space or a tab), or the file is refused; the header runs to the
 *   first blank line and is ignored.
 * - `NOTE`, `STYLE` and `REGION` blocks are skipped line by line without being interpreted (so no CSS is parsed).
 * - A cue is an optional id line, a timing line `[HH:]MM:SS.mmm --> [HH:]MM:SS.mmm` and its settings, then text up to a blank
 *   line. Of the settings only `line`, `position` and `align` are read: split at spaces and tabs, each token at most
 *   [SubtitleFiles.MAX_TOKEN] characters (a longer one is ignored, whatever it holds); unknown ones are ignored.
 * - Text goes through [Markup]: `<i>`, `<b>`, `<u>` style; `<c>`, `<v>`, `<lang>`, `<ruby>`, timestamps and any other tag are
 *   removed; `<rt>` text is dropped; six entities are decoded.
 */
@OptIn(UnstableApi::class)
internal object VttFile {
    fun read(lines: SubtitleLines, out: Drafts) {
        val first = lines.next() ?: throw SubtitleFiles.unreadable()
        if (!first.startsWith("WEBVTT") || (first.length > 6 && first[6] != ' ' && first[6] != '\t')) throw SubtitleFiles.unreadable()
        var line = skipBlock(lines)
        while (line != null) {
            if (line.isBlank()) { line = lines.next(); continue }
            if (opens(line, "NOTE") || opens(line, "STYLE") || opens(line, "REGION")) { line = skipBlock(lines); continue }
            var timing: String = line
            if (!timing.contains(Times.ARROW)) {
                timing = lines.next() ?: return
                if (timing.isBlank()) { line = timing; continue }
                if (!timing.contains(Times.ARROW)) { line = skipBlock(lines); continue }
            }
            out.count()
            line = cue(timing, lines, out)
        }
    }

    /** The lines up to the next blank one skipped; that blank line (or null at the end). */
    private fun skipBlock(lines: SubtitleLines): String? {
        var line = lines.next()
        while (line != null && line.isNotBlank()) line = lines.next()
        return line
    }

    private fun opens(line: String, word: String) = line.startsWith(word) && (line.length == word.length || line[word.length] == ' ' || line[word.length] == '\t')

    /** Reads one cue's timing line and text; returns the line after its text. */
    private fun cue(timing: String, lines: SubtitleLines, out: Drafts): String? {
        val arrow = timing.indexOf(Times.ARROW)
        val left = timing.substring(0, arrow).trim()
        val start = Times.read(left, 0, ".", fractionRequired = false)?.takeIf { it.end == left.length }?.ms
        val end = Times.read(timing, SrtFile.skipSpaces(timing, arrow + 3), ".", fractionRequired = false)
            ?.takeIf { it.end == timing.length || timing[it.end] == ' ' || timing[it.end] == '\t' }
        val readable = start != null && end != null
        val text = CueText()
        val markup = Markup(subRip = false)
        var line = lines.next()
        while (line != null && line.isNotBlank()) {
            if (readable && !text.full) {
                if (!text.isEmpty) text.newLine()
                markup.line(line, text)
            }
            line = lines.next()
        }
        if (start != null && end != null) out.add(start, end.ms, 0, text.build()?.let { cueOf(it, Settings(timing, end.end)) })
        return line
    }

    /** `line`, `position` and `align` from the rest of a timing line, in one pass. */
    private class Settings(s: String, from: Int) {
        var line: Float? = null
        var lineIsFraction = true
        var lineAnchor = Cue.ANCHOR_TYPE_START
        var position: Float? = null
        var positionAnchor: Int? = null
        var align: String? = null

        init {
            var i = from
            while (i < s.length) {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                val start = i
                while (i < s.length && s[i] != ' ' && s[i] != '\t') i++
                if (i - start in 1..SubtitleFiles.MAX_TOKEN) setting(s.substring(start, i))
            }
        }

        private fun setting(token: String) {
            val colon = token.indexOf(':')
            if (colon <= 0) return
            val value = token.substring(colon + 1).substringBefore(',')
            val anchor = token.substring(colon + 1).substringAfter(',', "")
            when (token.substring(0, colon)) {
                "line" -> {
                    if (value.endsWith('%')) percent(value)?.let { line = it; lineIsFraction = true }
                    else value.toIntOrNull()?.takeIf { it in -1_000..1_000 }?.let { line = it.toFloat(); lineIsFraction = false }
                    when (anchor) { "start" -> lineAnchor = Cue.ANCHOR_TYPE_START; "center", "middle" -> lineAnchor = Cue.ANCHOR_TYPE_MIDDLE; "end" -> lineAnchor = Cue.ANCHOR_TYPE_END }
                }
                "position" -> {
                    percent(value)?.let { position = it }
                    when (anchor) {
                        "line-left", "start" -> positionAnchor = Cue.ANCHOR_TYPE_START
                        "center", "middle" -> positionAnchor = Cue.ANCHOR_TYPE_MIDDLE
                        "line-right", "end" -> positionAnchor = Cue.ANCHOR_TYPE_END
                    }
                }
                "align" -> if (value in ALIGNS) align = value
            }
        }

        private fun percent(v: String): Float? = v.takeIf { it.endsWith('%') }?.dropLast(1)?.toFloatOrNull()?.takeIf { it in 0f..100f }?.div(100f)

        companion object {
            val ALIGNS = setOf("start", "left", "center", "middle", "end", "right")
        }
    }

    /** As Media3's WebVTT parser placed them: the alignment also gives the position and its anchor when they are not set. */
    private fun cueOf(text: CharSequence, s: Settings): Cue {
        val cue = Cue.Builder().setText(text)
        s.align?.let { a ->
            cue.setTextAlignment(
                when (a) { "start", "left" -> Layout.Alignment.ALIGN_NORMAL; "end", "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER },
            )
        }
        val fromAlign = when (s.align) { "start", "left" -> Cue.ANCHOR_TYPE_START; "end", "right" -> Cue.ANCHOR_TYPE_END; else -> Cue.ANCHOR_TYPE_MIDDLE }
        val position = s.position ?: s.align?.let { when (fromAlign) { Cue.ANCHOR_TYPE_START -> 0f; Cue.ANCHOR_TYPE_END -> 1f; else -> 0.5f } }
        if (position != null) cue.setPosition(position).setPositionAnchor(s.positionAnchor ?: fromAlign)
        s.line?.let { cue.setLine(it, if (s.lineIsFraction) Cue.LINE_TYPE_FRACTION else Cue.LINE_TYPE_NUMBER).setLineAnchor(s.lineAnchor) }
        return cue.build()
    }
}
