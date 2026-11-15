package app.shoalwatch.ui.present

/** Longest overview shown, in characters. The data layer already caps what it saves; this is the display backstop. */
const val OVERVIEW_MAX = 4_000

/**
 * An overview as plain text. Overviews arrive as text but may contain literal HTML (`<br>`, `<p>`, `<b>`...). Nothing is
 * interpreted as markup and nothing becomes a link: `<br>` and `</p>` become line breaks, every other tag is dropped, a tag
 * that never closes drops the rest (so half a `<script` never shows), and the entities `&amp; &lt; &gt; &quot; &#39; &nbsp;`
 * (plus numeric forms) are decoded exactly once, so `&lt;b&gt;` shows as the text `<b>` and is never read as a tag. Controls
 * and invisible formatting characters that an entity could smuggle in are dropped. The result is trimmed, has at most one
 * blank line in a row, and is cut at [maxLength] with an ellipsis.
 */
fun plainOverview(raw: String?, maxLength: Int = OVERVIEW_MAX): String {
    if (raw.isNullOrBlank()) return ""
    val out = StringBuilder()
    var i = 0
    while (i < raw.length && out.length <= maxLength * 2) {
        val c = raw[i]
        when {
            c == '<' && startsTag(raw, i) -> {
                val end = raw.indexOf('>', i)
                if (end < 0) break
                out.append(breakFor(raw.substring(i + 1, end)))
                i = end + 1
            }
            c == '&' -> {
                val semi = raw.indexOf(';', i)
                val decoded = if (semi in i + 2..i + 10) decodeEntity(raw.substring(i + 1, semi)) else null
                if (decoded == null) {
                    out.append(c)
                    i++
                } else {
                    out.append(decoded)
                    i = semi + 1
                }
            }
            c == '\r' -> {
                out.append('\n')
                i += if (i + 1 < raw.length && raw[i + 1] == '\n') 2 else 1
            }
            c == '\n' || c == '\t' || !c.isISOControl() && Character.getType(c) != Character.FORMAT.toInt() -> {
                out.append(if (c == '\t') ' ' else c)
                i++
            }
            else -> i++
        }
    }
    return tidy(out.toString()).cap(maxLength)
}

private fun startsTag(s: String, i: Int): Boolean =
    i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '/' || s[i + 1] == '!' || s[i + 1] == '?')

/** `<br>` is a line break, the end of a paragraph or block a blank line, any other tag nothing. */
private fun breakFor(body: String): String {
    val name = body.trim().trimEnd('/').trim().lowercase()
    return when {
        name == "br" || name.startsWith("br ") -> "\n"
        name == "/p" || name == "/div" -> "\n\n"
        else -> ""
    }
}

private fun decodeEntity(name: String): String? = when (name) {
    "amp" -> "&"
    "lt" -> "<"
    "gt" -> ">"
    "quot" -> "\""
    "apos" -> "'"
    "nbsp" -> " "
    else -> numeric(name)
}

private fun numeric(name: String): String? {
    if (!name.startsWith("#") || name.length < 2) return null
    val hex = name[1] == 'x' || name[1] == 'X'
    val code = name.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10) ?: return null
    if (code == 0xA0) return " "
    if (code !in 0x20..0x10FFFF || code in 0x7F..0x9F || code in 0xD800..0xDFFF) return ""
    return if (Character.getType(code) == Character.FORMAT.toInt()) "" else String(Character.toChars(code))
}

/** Trims each line's end, keeps at most one blank line in a row, and trims the whole. */
private fun tidy(text: String): String {
    val kept = ArrayList<String>()
    for (line in text.split('\n').map { it.trimEnd() }) {
        if (line.isNotEmpty() || (kept.isNotEmpty() && kept.last().isNotEmpty())) kept += line
    }
    return kept.joinToString("\n").trim()
}

private fun String.cap(max: Int): String {
    if (length <= max) return this
    var end = max.coerceAtLeast(1) - 1
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
    return substring(0, end).trimEnd() + "…"
}
