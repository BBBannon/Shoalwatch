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
    return out.toString().ifEmpty { fallback }
}
