package app.shoalwatch.data.subtitles

import app.shoalwatch.data.servers.Session
import java.util.Locale

/**
 * Subtitles from the internet, fetched **by the user's own server** (Milestone 11, decision D7: the phone never talks to a subtitle
 * provider). A token-free [Session] in, bounded text models out. Live only. Bad input (an item id, a language, a subtitle id) is an
 * `AppError` of kind `UNKNOWN` (`SERVER_NOT_FOUND` for an item id) with nothing sent. A refusal by the server's SubtitleManagement
 * policy (403, or a 401 that `GET /Users/Me` shows is not the sign-in ending) is a typed [SubtitleSearch.NotAllowed] /
 * [SubtitleDownload.NotAllowed] and the sign-in is kept; the screen words it "This profile isn't allowed to fetch subtitles."
 */
interface RemoteSubtitles {
    /** Candidates for [itemId] in [language] (three lower-case letters), at most [SubtitleLimits.RESULTS]. */
    suspend fun search(s: Session, itemId: String, language: String): SubtitleSearch

    /** Asks the server to fetch and attach one candidate. Sent once, never retried. */
    suspend fun download(s: Session, itemId: String, subtitleId: String): SubtitleDownload

    /** How many subtitle tracks the item has now. */
    suspend fun subtitleCount(s: Session, itemId: String): Int

    /**
     * After a download: reads the count after 2, 4, 8 and 16 seconds (cumulative from the call) and answers [Arrival.Arrived] as soon
     * as it is above [countBefore], else [Arrival.NotYet] after the last read. A read that fails counts as "not yet". Cancellable.
     */
    suspend fun awaitArrival(s: Session, itemId: String, countBefore: Int): Arrival

    /** The language to offer first: the profile's own subtitle language, else the phone's, else English (see [chooseLanguage]). */
    suspend fun preferredLanguage(s: Session): String
}

/** One candidate. Text only: no address is kept. [id] is only ever used to ask the server to download it. */
data class SubtitleCandidate(
    val id: String,
    val provider: String,
    val name: String,
    val format: String?,
    val rating: Float?,
    val downloads: Int?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
)

sealed interface SubtitleSearch {
    data class Found(val candidates: List<SubtitleCandidate>) : SubtitleSearch

    /** The server's policy refuses subtitle management for this profile. */
    data object NotAllowed : SubtitleSearch
}

sealed interface SubtitleDownload {
    data object Requested : SubtitleDownload
    data object NotAllowed : SubtitleDownload
}

enum class Arrival { Arrived, NotYet }

object SubtitleLimits {
    const val RESULTS = 50
    const val TEXT = 120
    const val FORMAT = 12
    const val ID = 128

    /** Cumulative waits from the download, in milliseconds. */
    val CHECK_AT_MS = longArrayOf(2_000, 4_000, 8_000, 16_000)
}

/** A language code the server accepts in the search path: exactly three lower-case ASCII letters. */
fun isLanguageCode(raw: String?): Boolean = raw != null && raw.length == 3 && raw.all { it in 'a'..'z' }

/**
 * A subtitle id is safe as one path segment: 1 to [SubtitleLimits.ID] characters of letters, digits and `. _ ~ = + -` (real provider ids such as
 * `srt-eng-1234567`, and base64-shaped ones), with no `..` and not made only of dots. Anything else (slash, backslash, `%`, `?`, spaces, controls) is refused.
 */
fun isSubtitleId(raw: String?): Boolean =
    raw != null && raw.length in 1..SubtitleLimits.ID && !raw.contains("..") && !raw.all { it == '.' } &&
        raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~=+-" }

/** The fallback chain, pure: the profile's choice if valid, else the phone's ISO 639-2 code if valid, else `eng`. */
fun chooseLanguage(profile: String?, phone: String?): String {
    val p = profile?.trim()?.lowercase(Locale.ROOT)
    if (isLanguageCode(p)) return p!!
    val d = phone?.trim()?.lowercase(Locale.ROOT)
    if (isLanguageCode(d)) return d!!
    return "eng"
}

/** The phone's language as ISO 639-2 (`Locale.getISO3Language`), or null when it has none. */
fun phoneLanguage(locale: Locale = Locale.getDefault()): String? = try {
    locale.isO3Language.takeIf { it.isNotEmpty() }
} catch (_: java.util.MissingResourceException) {
    null
}
