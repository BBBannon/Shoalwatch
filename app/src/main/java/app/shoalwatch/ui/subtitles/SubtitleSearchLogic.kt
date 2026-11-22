package app.shoalwatch.ui.subtitles

import androidx.annotation.StringRes
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.subtitles.Arrival
import app.shoalwatch.data.subtitles.RemoteSubtitles
import app.shoalwatch.data.subtitles.SubtitleCandidate
import app.shoalwatch.data.subtitles.SubtitleDownload
import app.shoalwatch.data.subtitles.SubtitleLimits
import app.shoalwatch.data.subtitles.SubtitleSearch
import app.shoalwatch.data.subtitles.isLanguageCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class SubtitleSearchPhase { SEARCHING, RESULTS, OFFLINE, FAILED, NOT_ALLOWED }

/**
 * The search window as it stands. [language] is a three-letter code; [picking] shows the language list. [sending]: a download is
 * going (and then being looked for), so every Download button waits. [message]: the last download's words (a string resource, 0 for none).
 */
data class SubtitleSearchUi(
    val open: Boolean = false,
    val language: String = "eng",
    val picking: Boolean = false,
    val phase: SubtitleSearchPhase = SubtitleSearchPhase.SEARCHING,
    val candidates: List<SubtitleCandidate> = emptyList(),
    val sending: Boolean = false,
    @StringRes val message: Int = 0,
) {
    override fun toString() = "SubtitleSearchUi(open=$open, phase=$phase)"
}

/** The search's entry points, wired by a route to its ViewModel. */
class SubtitleSearchActions(
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onTogglePicking: () -> Unit,
    val onLanguage: (String) -> Unit,
    val onRetry: () -> Unit,
    val onDownload: (SubtitleCandidate) -> Unit,
)

/** What a screen is given for the search: its state and entry points. */
class SubtitleSearchControls(val state: SubtitleSearchUi, val actions: SubtitleSearchActions)

/**
 * "Find subtitles online" (Milestone 11, decisions 15 and 16), shared by the player's subtitle sheet and the detail page. The search
 * opens at the profile's own language ([RemoteSubtitles.preferredLanguage]), can be changed from [SubtitleLanguages], and shows text only.
 * A download is sent **once** (nothing else is sent while it runs): the count of the item's subtitles is read BEFORE the request, then the
 * server is asked after 2, 4, 8 and 16 seconds; when the count has grown [onArrived] is told (the player asks the server again at the same
 * position; the detail page reads the item again), else "Downloaded. It can take a minute to show." A refusal is "This profile isn't
 * allowed to fetch subtitles." and never ends the sign-in. Everything is for one session and one item: another session's answer is dropped.
 */
class SubtitleSearchLogic(
    private val scope: CoroutineScope,
    private val remote: RemoteSubtitles,
    private val session: () -> Session?,
    private val itemId: () -> String?,
    /** Told with the session and the item the download was for (the screen may have moved on to another item since). */
    private val onArrived: (Session, String) -> Unit,
    /** What is said when the track has arrived (the player points at its Subtitles list). */
    @StringRes private val arrivedText: Int = R.string.subsearch_arrived,
) {
    private val body = MutableStateFlow(SubtitleSearchUi())
    val state: StateFlow<SubtitleSearchUi> = body.asStateFlow()

    private var searchJob: Job? = null
    private var sendJob: Job? = null

    private val sendingNow get() = sendJob?.isActive == true

    /** The entry points of this search, for a route to hand its screen. */
    val actions = SubtitleSearchActions(::open, ::close, ::togglePicking, ::setLanguage, ::retry, ::download)

    fun open() {
        val s = session() ?: return
        val item = itemId() ?: return
        searchJob?.cancel()
        body.value = SubtitleSearchUi(open = true, sending = sendingNow)
        searchJob = scope.launch {
            val language = try {
                remote.preferredLanguage(s).takeIf(::isLanguageCode) ?: "eng"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "eng"
            }
            if (session() != s) return@launch
            body.update { it.copy(language = language) }
            run(s, item, language)
        }
    }

    fun close() {
        searchJob?.cancel()
        body.value = SubtitleSearchUi(sending = sendingNow)
    }

    fun togglePicking() = body.update { it.copy(picking = !it.picking) }

    fun setLanguage(code: String) {
        val s = session() ?: return
        val item = itemId() ?: return
        if (!isLanguageCode(code) || !body.value.open) return
        searchJob?.cancel()
        body.update { it.copy(language = code, picking = false, candidates = emptyList(), phase = SubtitleSearchPhase.SEARCHING, message = 0) }
        searchJob = scope.launch { run(s, item, code) }
    }

    fun retry() {
        val s = session() ?: return
        val item = itemId() ?: return
        if (!body.value.open) return
        searchJob?.cancel()
        val language = body.value.language
        body.update { it.copy(phase = SubtitleSearchPhase.SEARCHING, candidates = emptyList()) }
        searchJob = scope.launch { run(s, item, language) }
    }

    private suspend fun run(s: Session, item: String, language: String) {
        val next = try {
            when (val r = remote.search(s, item, language)) {
                is SubtitleSearch.Found -> SubtitleSearchPhase.RESULTS to r.candidates.take(SubtitleLimits.RESULTS)
                SubtitleSearch.NotAllowed -> SubtitleSearchPhase.NOT_ALLOWED to emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (if (isOffline(e)) SubtitleSearchPhase.OFFLINE else SubtitleSearchPhase.FAILED) to emptyList()
        }
        if (session() != s || itemId() != item) return
        body.update { if (it.open && it.language == language) it.copy(phase = next.first, candidates = next.second) else it }
    }

    /** Sends one candidate. Ignored while another is going. */
    fun download(candidate: SubtitleCandidate) {
        val s = session() ?: return
        val item = itemId() ?: return
        if (!body.value.open || body.value.sending || sendingNow) return
        body.update { it.copy(sending = true, message = 0) }
        sendJob = scope.launch {
            var words = R.string.subsearch_send_failed
            val before = try {
                remote.subtitleCount(s, item) // read before the request, as the arrival check needs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was sent: the words must not say a download failed.
                if (session() == s) body.update { it.copy(sending = false, message = if (it.open) (if (isOffline(e)) R.string.subsearch_send_offline else R.string.subsearch_unreachable) else 0) }
                return@launch
            }
            try {
                words = when (remote.download(s, item, candidate.id)) {
                    SubtitleDownload.NotAllowed -> R.string.subsearch_refused
                    SubtitleDownload.Requested -> when (remote.awaitArrival(s, item, before)) {
                        Arrival.Arrived -> { if (session() == s) onArrived(s, item); arrivedText }
                        Arrival.NotYet -> R.string.subsearch_later
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                words = if (isOffline(e)) R.string.subsearch_send_offline else R.string.subsearch_send_failed
            }
            if (session() == s) body.update { it.copy(sending = false, message = if (it.open) words else 0) }
        }
    }

    private fun isOffline(e: Exception) = when ((e as? AppError)?.kind) {
        AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> true
        else -> false
    }
}

/** The languages offered, as three-letter codes the server accepts, with names in the phone's language. */
object SubtitleLanguages {
    /** (code sent to the server, two-letter code Android knows the name by). The two ISO 639-2 lists differ for several languages. */
    private val common = listOf(
        "eng" to "en", "fre" to "fr", "ger" to "de", "spa" to "es", "ita" to "it", "por" to "pt", "dut" to "nl", "pol" to "pl",
        "rus" to "ru", "tur" to "tr", "ara" to "ar", "chi" to "zh", "jpn" to "ja", "kor" to "ko", "hin" to "hi", "swe" to "sv",
        "nor" to "no", "dan" to "da", "fin" to "fi", "gre" to "el", "heb" to "he", "cze" to "cs", "hun" to "hu", "rum" to "ro",
        "ukr" to "uk", "wel" to "cy",
    )

    /** The terminology code of each language whose two ISO 639-2 codes differ, by the bibliographic one in [common]. */
    private val terminology = mapOf(
        "fre" to "fra", "ger" to "deu", "chi" to "zho", "cze" to "ces", "dut" to "nld", "gre" to "ell", "per" to "fas", "rum" to "ron",
        "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "may" to "msa",
        "bur" to "mya", "wel" to "cym", "tib" to "bod",
    )
    private val bibliographic = terminology.entries.associate { it.value to it.key }

    /** The bibliographic form of [code] (`fra` becomes `fre`), so the two forms of a language are one. */
    private fun canonical(code: String) = bibliographic[code] ?: code

    /**
     * The codes in the list. A language is listed once whichever of its two codes [current] is, and then carries [current] (the profile's own,
     * the one sent); a [current] not in the list at all comes first.
     */
    fun codes(current: String): List<String> {
        val all = common.map { it.first }
        if (!isLanguageCode(current)) return all
        val here = canonical(current)
        return if (all.any { it == here }) all.map { if (it == here) current else it } else listOf(current) + all
    }

    /** A readable name ("English"), or the code in capitals when Android has no name. */
    fun name(code: String, locale: Locale = Locale.getDefault()): String {
        val known = common.firstOrNull { it.first == canonical(code) }?.second ?: code
        val shown = try {
            Locale(known).getDisplayLanguage(locale)
        } catch (_: Exception) {
            ""
        }
        return shown.takeIf { it.isNotBlank() && !it.equals(known, ignoreCase = true) }?.replaceFirstChar { it.titlecase(locale) } ?: code.uppercase(Locale.ROOT)
    }
}
