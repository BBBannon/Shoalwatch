package app.shoalwatch.ui.present

import app.shoalwatch.R
import app.shoalwatch.data.sync.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/** The sync line: [main] always, [detail] (one or two lines, joined by a new line) when there is more to say. */
data class SyncWords(val main: String, val detail: String?)

/**
 * The words for one profile's [SyncStatus] (pure apart from [Strings]). Null when there is nothing to say (nothing waiting and
 * never synced). The order is: needs a sign-in, then changes the server keeps refusing, then changes waiting, then "all on your
 * server". Times `HH:mm` and dates `d MMMM`, in English (`Locale.UK`) with digits 0-9 whatever the phone's language.
 */
object SyncText {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withDecimalStyle(DecimalStyle.STANDARD)
    private val date = DateTimeFormatter.ofPattern("d MMMM", Locale.UK).withDecimalStyle(DecimalStyle.STANDARD)

    fun words(status: SyncStatus, nowMs: Long, zone: ZoneId, res: Strings): SyncWords? {
        val last = status.lastSyncedAt?.let { lastSynced(it, nowMs, zone, res) }
        val waiting = status.waiting
        val stuck = status.stuck.coerceIn(0, waiting)
        return when {
            waiting <= 0 -> last?.let { SyncWords(res.get(R.string.sync_all_sent), it) }
            status.needsSignIn -> SyncWords(res.plural(R.plurals.sync_sign_in, waiting), last)
            stuck > 0 -> SyncWords(
                res.plural(R.plurals.sync_stuck, stuck),
                lines((waiting - stuck).takeIf { it > 0 }?.let { res.plural(R.plurals.sync_waiting, it) }, last),
            )
            else -> SyncWords(res.plural(R.plurals.sync_waiting, waiting), lines(res.get(R.string.sync_waiting_detail), last))
        }
    }

    /** "Last synced at 14:05" when it was today in [zone], otherwise "Last synced on 12 November". */
    private fun lastSynced(at: Long, nowMs: Long, zone: ZoneId, res: Strings): String {
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (then.toLocalDate() == today) res.get(R.string.sync_last_at, time.format(then)) else res.get(R.string.sync_last_on, date.format(then))
    }

    private fun lines(vararg parts: String?): String? = parts.filterNotNull().joinToString("\n").ifEmpty { null }
}
