package app.shoalwatch.data.servers

/**
 * A server or user id as the server reported it, validated (S11). Jellyfin ids are GUIDs, sent with or without
 * dashes and in either letter case; the only way to get one is [parseOrNull], which accepts exactly 32 hex digits
 * (optionally in the 8-4-4-4-12 dashed layout) and normalises to lower case without dashes. That normalised
 * [value] is the key stored everywhere, so the same server can never become two rows and an id is safe to use
 * in a route or (from Milestone 4) a file name.
 */
@JvmInline
value class JellyfinId private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private val DASHES = intArrayOf(8, 13, 18, 23)

        /** Null for anything that is not a GUID. Never echoes the input anywhere. */
        fun parseOrNull(raw: String?): JellyfinId? {
            val text = raw?.trim() ?: return null
            val hex = when (text.length) {
                32 -> text
                36 -> if (DASHES.all { text[it] == '-' }) text.filterIndexed { i, _ -> i !in DASHES } else return null
                else -> return null
            }
            if (hex.length != 32 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return JellyfinId(hex.lowercase())
        }
    }
}
