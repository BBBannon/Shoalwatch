package app.shoalwatch.core

/**
 * Makes text from a server (or a certificate) safe to show: control characters and line breaks become spaces, every
 * Unicode format character (category Cf: bidi marks, embeddings, overrides and isolates, zero-width characters, soft
 * hyphen, invisible operators, tag characters, the byte-order mark, ...) is removed (so a hostile server cannot reorder
 * or hide what the user reads), runs of white space collapse to one, and the result is trimmed and capped at
 * [maxLength] characters (whole code points, so a pair is never split). If nothing visible is left, [fallback] is
 * returned (empty unless the caller supplies a neutral name such as "Unnamed user").
 */
fun displayText(raw: String?, maxLength: Int, fallback: String = ""): String {
    if (raw.isNullOrEmpty()) return fallback
    val out = StringBuilder()
    var count = 0
    var pendingSpace = false
    var i = 0
    while (i < raw.length && count < maxLength) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
            Character.getType(cp) == Character.FORMAT.toInt() -> Unit
            cp in BLANK_RENDERING || Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.CONTROL.toInt() ->
                pendingSpace = out.isNotEmpty()
            else -> {
                if (pendingSpace) {
                    out.append(' ')
                    count++
                    pendingSpace = false
                    if (count >= maxLength) break
                }
                out.appendCodePoint(cp)
                count++
            }
        }
    }
    return out.toString().ifEmpty { fallback }
}

/** Not white space to Unicode, but drawn as nothing (Hangul fillers, braille blank): treated as a space so an empty-looking name falls back. */
private val BLANK_RENDERING = setOf(0x3164, 0x2800, 0xFFA0, 0x115F, 0x1160)

/**
 * Like [displayText] but for paragraphs (an overview): line breaks are kept. CRLF and a lone CR become a line break,
 * each line is made safe exactly as [displayText] does (control, bidi and zero-width characters removed, white space
 * collapsed), runs of blank lines collapse to one blank line, leading and trailing blank lines go, and the whole is
 * capped at [maxCodePoints] code points (a surrogate pair is never split). Nothing else is changed: HTML stays as text
 * for the UI to show as text. Empty when nothing visible is left.
 */
fun displayParagraphs(raw: String?, maxCodePoints: Int): String {
    if (raw.isNullOrBlank()) return ""
    val kept = ArrayList<String>()
    for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { displayText(it, maxCodePoints) }) {
        if (line.isNotEmpty() || (kept.isNotEmpty() && kept.last().isNotEmpty())) kept.add(line)
    }
    val text = kept.joinToString("\n").trim()
    return if (text.codePointCount(0, text.length) <= maxCodePoints) text else text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).trimEnd()
}
