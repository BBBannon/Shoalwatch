package app.offlinefin.core

/** Strips URLs and credentials from text before it is logged or shown. */
object Redact {
    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""")
    private val AUTHORIZATION = Regex("""(?i)(authorization\s*[:=]\s*).*""")
    private val QUOTED_TOKEN = Regex("""(?i)\b(token\s*=\s*)"[^"]*"""")
    private val KEY_VALUE = Regex("""(?i)\b(api_?key\s*=\s*)[^\s&",;]+""")

    fun url(s: String): String = URL.replace(s, "<url>")

    fun text(s: String): String {
        var out = AUTHORIZATION.replace(s) { it.groupValues[1] + "<redacted>" }
        out = URL.replace(out, "<url>")
        out = QUOTED_TOKEN.replace(out) { it.groupValues[1] + "\"<redacted>\"" }
        return KEY_VALUE.replace(out) { it.groupValues[1] + "<redacted>" }
    }
}
