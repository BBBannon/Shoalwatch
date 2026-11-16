package app.shoalwatch.data.playback

import java.util.Locale
import java.util.MissingResourceException

/**
 * Language codes compared by meaning (pure). Jellyfin names tracks in ISO 639-2 (`eng`, `fre` or `fra`, `ger` or `deu`),
 * Media3 and Android's caption settings in BCP 47 (`en`, `en-GB`, `fr`): each is reduced to one three-letter code first.
 */
internal object Languages {
    /** ISO 639-2 bibliographic codes (what Jellyfin often sends) to the terminology codes `Locale` uses. */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld", "fre" to "fra",
        "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas",
        "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )
    private val UNKNOWN = setOf("und", "unk", "mul", "zxx", "mis")

    /** The three-letter code for [tag], or null when it names no language. */
    fun key(tag: String?): String? {
        val primary = tag?.substringBefore('-')?.substringBefore('_')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
            ?: return null
        val three = if (primary.length == 2) {
            try {
                Locale(primary).isO3Language
            } catch (_: MissingResourceException) {
                primary
            }
        } else primary
        return (BIBLIOGRAPHIC[three] ?: three).takeIf { it !in UNKNOWN }
    }

    /** Both name the same language. */
    fun same(a: String?, b: String?): Boolean {
        val ka = key(a) ?: return false
        return ka == key(b)
    }

    /** Nothing says they differ: equal, or either one unknown. */
    fun agree(a: String?, b: String?): Boolean {
        val ka = key(a) ?: return true
        val kb = key(b) ?: return true
        return ka == kb
    }
}
