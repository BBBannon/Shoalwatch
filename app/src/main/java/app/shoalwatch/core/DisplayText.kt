package app.shoalwatch.core

/**
 * Makes text from a server (or a certificate) safe to show: control characters and line breaks become spaces,
 * Unicode direction overrides, isolates and marks and zero-width characters are removed (so a hostile server
 * cannot reorder or hide what the user reads), runs of white space collapse to one, and the result is trimmed
 * and capped at [maxLength] characters (whole code points, so a pair is never split). May return an empty string.
 */
fun displayText(raw: String?, maxLength: Int): String {
    if (raw.isNullOrEmpty()) return ""
    val out = StringBuilder()
    var count = 0
    var pendingSpace = false
    var i = 0
    while (i < raw.length && count < maxLength) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp in INVISIBLE -> Unit
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.CONTROL.toInt() ->
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
    return out.toString()
}

/** Bidi marks, embeddings, overrides and isolates; zero-width space, joiners and the byte-order mark. */
private val INVISIBLE: Set<Int> = buildSet {
    add(0x061C); add(0x200E); add(0x200F)
    addAll(0x202A..0x202E)
    addAll(0x2066..0x2069)
    addAll(0x200B..0x200D)
    add(0x2060); add(0xFEFF)
}
