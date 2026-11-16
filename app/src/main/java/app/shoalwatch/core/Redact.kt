package app.shoalwatch.core

/** Strips URLs, hostnames and credentials from text before it is logged or shown. */
object Redact {
    // double-quoted, single-quoted or bare value
    private const val VALUE = """["][^"]*["]|'[^']*'|[^\s&",;']+"""

    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""")
    private val AUTHORIZATION = Regex("""(?i)(authorization\s*[:=]\s*).*""")
    private val TOKEN_HEADER = Regex("""(?i)(x-(?:emby|mediabrowser)-token\s*[:=]\s*)\S+""")
    private val BEARER = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val TOKEN = Regex("""(?i)\b(token\s*=\s*)(""" + VALUE + ")")
    private val KEY_VALUE = Regex("""(?i)\b(api_?key\s*=\s*)(""" + VALUE + ")")
    // UnknownHostException: Unable to resolve host "name" (quotes may be backslash-escaped)
    private val HOST = Regex("""(?i)\b(host\s+)\\?["][^"\\]*\\?["]""")

    fun url(s: String): String = URL.replace(s, "<url>")

    fun text(s: String): String {
        var out = AUTHORIZATION.replace(s) { it.groupValues[1] + "<redacted>" }
        out = URL.replace(out, "<url>")
        out = TOKEN_HEADER.replace(out) { it.groupValues[1] + "<redacted>" }
        out = BEARER.replace(out) { it.groupValues[1] + "<redacted>" }
        out = TOKEN.replace(out) { it.groupValues[1] + "<redacted>" }
        out = KEY_VALUE.replace(out) { it.groupValues[1] + "<redacted>" }
        return HOST.replace(out) { it.groupValues[1] + "<host>" }
    }
}
