package app.shoalwatch.data.playback.engine

import android.graphics.Color
import android.text.Layout
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * ASS and SSA, read by the app. One pass over the lines; sections by their `[heading]` (ignoring case), and `Format:`,
 * `Style:` and `Dialogue:` matched case-sensitively at the start of a line, as Media3 matched them.
 *
 * - `[Script Info]`: `PlayResX` and `PlayResY` only.
 * - `[V4+ Styles]` and `[V4 Styles]` (legacy alignments mapped): through its `Format:`, Name, Bold, Italic, Underline,
 *   PrimaryColour and Alignment; at most [SubtitleFiles.MAX_STYLES] styles, the first of a name wins, a name over
 *   [SubtitleFiles.MAX_TOKEN] characters is ignored.
 * - `[Events]`: through its latest `Format:`, which must have Start, End and Text, with Text last (as the format defines it:
 *   the text is the rest of the line), or the file is refused; so every other field, Style among them, holds no comma.
 *   `Dialogue:` lines only, each counted towards [SubtitleFiles.MAX_CUES]; times `[H:]MM:SS.cc` (1 to 3 fraction digits, `.`
 *   or `:` before them); a line that cannot be read is dropped.
 * - Text, in one pass: `\N` and `\n` break the line, `\h` is a hard space. An override block `{...}` (only when a `}` follows
 *   on the line; an unclosed `{` is literal) is skipped to its `}`, and its tags read on the way: from the FIRST block only,
 *   `\pos(x,y)`, `\anN`, `\aN`, `\i`, `\b`, `\u` and `\c`/`\1c` apply to the whole cue (simple, and what Media3 did for
 *   `\pos` and `\an`); in any block, `\p1`... starts a drawing (its text is dropped) and `\p0` ends it; every other tag is
 *   removed, and an animated or complex one ([AssEffects]) sets [Drafts.effects].
 */
@OptIn(UnstableApi::class)
internal object AssFile {
    private class Style(val flags: Int, val colour: Int?, val alignment: Int)

    private class Reader(val out: Drafts) {
        var section = ""
        var playResX = 0f
        var playResY = 0f
        var styleFields: Map<String, Int>? = null
        var styleCount = 0
        val styles = HashMap<String, Style>()
        var events: IntArray? = null // layer, start, end, style, field count; Text is the last field
    }

    fun read(lines: SubtitleLines, out: Drafts) {
        val r = Reader(out)
        var line = lines.next()
        while (line != null) {
            val heading = line.trim()
            if (heading.startsWith('[') && heading.endsWith(']')) {
                r.section = SECTIONS.firstOrNull { it.equals(heading, ignoreCase = true) }.orEmpty()
                r.styleFields = null
            } else {
                // A header line is split or interpreted: bounded first (subtitle-parsers review m-1). A Dialogue line is text.
                val header = r.section.isNotEmpty() && (r.section != EVENTS || line.startsWith("Format:"))
                if (header && line.length > SubtitleFiles.MAX_HEADER_LINE) throw SubtitleFiles.unreadable()
                when (r.section) {
                    INFO -> info(r, line)
                    STYLES, LEGACY_STYLES -> if (line.startsWith("Format:")) r.styleFields = fields(line) else if (line.startsWith("Style:")) style(r, line)
                    EVENTS -> if (line.startsWith("Format:")) r.events = eventFormat(line) else if (line.startsWith("Dialogue:")) { out.count(); dialogue(r, line) }
                }
            }
            line = lines.next()
        }
    }

    private fun info(r: Reader, line: String) {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon > SubtitleFiles.MAX_TOKEN) return
        val value = token(line.substring(colon + 1))?.toFloatOrNull()?.takeIf { it > 0f } ?: return
        when (line.substring(0, colon).trim().lowercase()) {
            "playresx" -> r.playResX = value
            "playresy" -> r.playResY = value
        }
    }

    /** The trimmed [s], or null over [SubtitleFiles.MAX_TOKEN] characters. */
    private fun token(s: String): String? = s.trim().takeIf { it.length <= SubtitleFiles.MAX_TOKEN }

    /** A `Format:` line's field names (trimmed, ASCII lower case; the last of a name wins) to their positions, and "" to the count. */
    private fun fields(line: String): Map<String, Int> {
        val names = line.substring("Format:".length).split(',')
        val map = HashMap<String, Int>()
        names.forEachIndexed { i, n -> token(n)?.let { map[it.lowercase()] = i } }
        map[""] = names.size
        return map
    }

    private fun style(r: Reader, line: String) {
        val f = r.styleFields ?: return
        if (r.styleCount >= SubtitleFiles.MAX_STYLES) return
        val count = f.getValue("")
        val v = line.substring("Style:".length).split(',', limit = count)
        if (v.size != count) return
        val name = token(v[f["name"] ?: return])?.takeIf { it.isNotEmpty() } ?: return
        r.styleCount++
        if (name in r.styles) return
        fun flag(key: String, bit: Int) = if (f[key]?.let { token(v[it]) }.let { it == "1" || it == "-1" }) bit else 0
        val alignment = f["alignment"]?.let { token(v[it])?.toIntOrNull() }?.let { if (r.section == LEGACY_STYLES) legacy(it) else it.takeIf { a -> a in 1..9 } } ?: 0
        r.styles[name] = Style(
            flag("bold", CueText.BOLD) or flag("italic", CueText.ITALIC) or flag("underline", CueText.UNDERLINE),
            f["primarycolour"]?.let { colourOf(v[it]) },
            alignment,
        )
    }

    private fun eventFormat(line: String): IntArray {
        val f = fields(line)
        val count = f.getValue("")
        val start = f["start"]
        val end = f["end"]
        if (start == null || end == null || f["text"] != count - 1) throw SubtitleFiles.unreadable()
        return intArrayOf(f["layer"] ?: -1, start, end, f["style"] ?: -1, count)
    }

    private fun dialogue(r: Reader, line: String) {
        val e = r.events ?: return
        val v = line.substring("Dialogue:".length).split(',', limit = e[4])
        if (v.size != e[4]) return
        val start = time(v[e[1]]) ?: return
        val end = time(v[e[2]]) ?: return
        val layer = if (e[0] < 0) 0 else token(v[e[0]])?.toIntOrNull() ?: 0
        val style = if (e[3] < 0) null else token(v[e[3]])?.let { r.styles[it] }
        r.out.add(start, end, layer, cueOf(r, v[e[4] - 1], style, layer))
    }

    private fun time(field: String): Long? {
        val s = field.trim()
        return Times.read(s, 0, ".:")?.takeIf { it.end == s.length }?.ms
    }

    private class Overrides {
        var on = 0
        var off = 0
        var colour: Int? = null
        var alignment = 0
        var x: Float? = null
        var y: Float? = null
        var drawing = false
    }

    private fun cueOf(r: Reader, field: String, style: Style?, layer: Int): Cue? {
        val text = CueText()
        val o = Overrides()
        val lastClose = field.lastIndexOf('}')
        var first = true
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '{' && i < lastClose) {
                val close = field.indexOf('}', i + 1)
                block(r, field, i + 1, close, first, o)
                first = false
                i = close + 1
                continue
            }
            if (c == '\\' && i + 1 < field.length && field[i + 1] in "Nnh") {
                if (!o.drawing) { if (field[i + 1] == 'h') text.append(' ') else text.newLine() }
                i += 2
                continue
            }
            if (!o.drawing) text.append(c)
            i++
        }
        val flags = ((style?.flags ?: 0) or o.on) and o.off.inv()
        val built = text.build(flags, o.colour ?: style?.colour) ?: return null
        val alignment = if (o.alignment != 0) o.alignment else style?.alignment ?: 0
        val cue = Cue.Builder().setText(built).setZIndex(layer)
        val positionAnchor = when (alignment) { 1, 4, 7 -> Cue.ANCHOR_TYPE_START; 2, 5, 8 -> Cue.ANCHOR_TYPE_MIDDLE; 3, 6, 9 -> Cue.ANCHOR_TYPE_END; else -> Cue.TYPE_UNSET }
        val lineAnchor = when (alignment) { 1, 2, 3 -> Cue.ANCHOR_TYPE_END; 4, 5, 6 -> Cue.ANCHOR_TYPE_MIDDLE; 7, 8, 9 -> Cue.ANCHOR_TYPE_START; else -> Cue.TYPE_UNSET }
        if (alignment != 0) {
            cue.setTextAlignment(when (positionAnchor) { Cue.ANCHOR_TYPE_START -> Layout.Alignment.ALIGN_NORMAL; Cue.ANCHOR_TYPE_END -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER })
                .setPositionAnchor(positionAnchor).setLineAnchor(lineAnchor)
        }
        val x = o.x
        val y = o.y
        if (x != null && y != null && r.playResX > 0f && r.playResY > 0f) {
            cue.setPosition(x / r.playResX).setLine(y / r.playResY, Cue.LINE_TYPE_FRACTION)
        } else if (alignment != 0) {
            cue.setPosition(edge(positionAnchor)).setLine(edge(lineAnchor), Cue.LINE_TYPE_FRACTION)
        }
        return cue.build()
    }

    /** As Media3's SSA parser placed an aligned cue with no `\pos`: 5% in from the side or edge, or centred. */
    private fun edge(anchor: Int) = when (anchor) { Cue.ANCHOR_TYPE_START -> 0.05f; Cue.ANCHOR_TYPE_END -> 0.95f; else -> 0.5f }

    /** The tags of one override block, `\` to `\`, each looked at once (at most [SubtitleFiles.MAX_TOKEN] characters of it). */
    private fun block(r: Reader, s: String, from: Int, to: Int, first: Boolean, o: Overrides) {
        var i = from
        while (i < to) {
            if (s[i] != '\\') { i++; continue }
            var j = i + 1
            while (j < to && s[j] != '\\') j++
            val long = j - i - 1 > SubtitleFiles.MAX_TOKEN
            val tag = s.substring(i + 1, minOf(j, i + 1 + SubtitleFiles.MAX_TOKEN))
            if (AssEffects.undrawable(tag)) r.out.effects = true
            if (!long) tag(tag, first, o)
            i = j
        }
    }

    private fun tag(tag: String, first: Boolean, o: Overrides) {
        number(tag, "p")?.let { o.drawing = it != 0L; return }
        if (!first) return
        when {
            tag.startsWith("pos(") -> {
                val xy = tag.substring(4).substringBefore(')').split(',')
                if (xy.size == 2) { o.x = xy[0].trim().toFloatOrNull(); o.y = xy[1].trim().toFloatOrNull() }
            }
            tag.startsWith("an") -> number(tag, "an")?.takeIf { it in 1L..9L }?.let { o.alignment = it.toInt() }
            tag.startsWith("a") -> number(tag, "a")?.let { o.alignment = legacy(it.toInt()) }
            tag.startsWith("1c&") || tag.startsWith("c&") -> colourOf(tag.substringAfter('c'))?.let { o.colour = it }
            else -> for ((name, bit) in BITS) number(tag, name)?.let { if (it == 1L || it >= 700L) o.on = o.on or bit else o.off = o.off or bit }
        }
    }

    /** The digits after [name] in [tag], when that is all of it (1 to 9 of them). */
    private fun number(tag: String, name: String): Long? {
        if (!tag.startsWith(name) || tag.length == name.length || tag.length - name.length > 9) return null
        for (k in name.length until tag.length) if (tag[k] !in '0'..'9') return null
        return tag.substring(name.length).toLong()
    }

    /** Legacy SSA alignment (1-3 bottom, 5-7 top, 9-11 middle) as the numeric keypad; 0 when it is not one. */
    private fun legacy(a: Int) = when (a) { 1, 2, 3 -> a; 5, 6, 7 -> a + 2; 9, 10, 11 -> a - 5; else -> 0 }

    /** `&HAABBGGRR`, `&HBBGGRR` (an optional `&` after) or a decimal number, as ARGB (ASS alpha 00 is opaque); null otherwise. */
    private fun colourOf(field: String): Int? {
        val s = token(field) ?: return null
        val abgr = if (s.startsWith("&H", ignoreCase = true)) {
            val hex = s.substring(2).trimEnd('&')
            if (hex.isEmpty() || hex.length > 8) return null
            hex.toLongOrNull(16)
        } else {
            s.takeIf { it.length <= 10 }?.toLongOrNull()?.takeIf { it in 0L..0xFFFFFFFFL }
        } ?: return null
        val v = abgr.toInt()
        return Color.argb(0xFF - (v ushr 24 and 0xFF), v and 0xFF, v ushr 8 and 0xFF, v ushr 16 and 0xFF)
    }

    private const val INFO = "[Script Info]"
    private const val STYLES = "[V4+ Styles]"
    private const val LEGACY_STYLES = "[V4 Styles]"
    private const val EVENTS = "[Events]"
    private val SECTIONS = listOf(INFO, STYLES, LEGACY_STYLES, EVENTS)
    private val BITS = listOf("b" to CueText.BOLD, "i" to CueText.ITALIC, "u" to CueText.UNDERLINE)
}
