package app.shoalwatch.ui.present

import androidx.annotation.StringRes
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.QueueStatus
import java.math.BigDecimal
import java.math.RoundingMode

/** The download words, pure apart from [Strings]: states, failures, qualities, sizes (decimal units, digits 0-9 in every locale). */
object DownloadText {
    /** "Waiting", "Downloading · 45%", "Checking it plays", "Paused", "Ready to watch", or the failure line; a conversion adds " · High (1080p)". */
    fun status(strings: Strings, d: DownloadInfo): String = withQuality(strings, d, base(strings, d, spoken = false))

    /** What TalkBack says: as [status], with "Downloading, 45 percent". */
    fun spokenStatus(strings: Strings, d: DownloadInfo): String = withQuality(strings, d, base(strings, d, spoken = true))

    private fun base(strings: Strings, d: DownloadInfo, spoken: Boolean): String = when (d.state) {
        DownloadState.QUEUED -> strings.get(R.string.dl_state_queued)
        DownloadState.RUNNING -> percent(d.progress)?.let { strings.get(if (spoken) R.string.dl_state_running_spoken else R.string.dl_state_running_percent, it) }
            ?: strings.get(R.string.dl_state_running)
        DownloadState.VERIFYING -> strings.get(R.string.dl_state_verifying)
        DownloadState.PAUSED -> strings.get(R.string.dl_state_paused)
        DownloadState.COMPLETE -> strings.get(R.string.dl_state_complete)
        DownloadState.FAILED -> failure(strings, d.failure ?: DownloadFailure.UNKNOWN)
    }

    private fun withQuality(strings: Strings, d: DownloadInfo, line: String): String =
        if (d.quality == DownloadQuality.ORIGINAL || d.state == DownloadState.FAILED) line else strings.get(R.string.dl_with_quality, line, quality(strings, d.quality))

    /** "45": the whole percent, digits 0-9; null when not known. */
    fun percent(progress: Float?): String? = progress?.let { (it.coerceIn(0f, 1f) * 100).toInt().toString() }

    fun failure(strings: Strings, f: DownloadFailure): String = strings.get(failureText(f))

    @StringRes private fun failureText(f: DownloadFailure): Int = when (f) {
        DownloadFailure.NOT_ALLOWED -> R.string.dl_failure_not_allowed
        DownloadFailure.NOT_PLAYABLE -> R.string.dl_failure_not_playable
        DownloadFailure.NO_SPACE -> R.string.dl_failure_no_space
        DownloadFailure.STORAGE_MISSING -> R.string.dl_failure_storage_missing
        DownloadFailure.CHANGED_ON_SERVER -> R.string.dl_failure_changed
        DownloadFailure.SERVER_ERROR -> R.string.dl_failure_server_error
        DownloadFailure.SERVER_AUTH_REFUSED -> R.string.dl_failure_auth_refused
        DownloadFailure.SIGN_IN_NEEDED -> R.string.dl_failure_sign_in
        DownloadFailure.CHECK_FAILED -> R.string.dl_failure_check_failed
        DownloadFailure.DAMAGED -> R.string.dl_failure_damaged
        DownloadFailure.GONE_FROM_SERVER -> R.string.dl_failure_gone
        DownloadFailure.UNKNOWN -> R.string.dl_failure_unknown
    }

    /** What a failure offers: Retry, except for what a retry cannot fix (no permission, gone) and a sign-in (its own button). */
    fun canRetry(f: DownloadFailure?): Boolean = f != DownloadFailure.NOT_ALLOWED && f != DownloadFailure.GONE_FROM_SERVER && f != DownloadFailure.SIGN_IN_NEEDED

    fun quality(strings: Strings, q: DownloadQuality): String = strings.get(
        when (q) {
            DownloadQuality.ORIGINAL -> R.string.dl_quality_original
            DownloadQuality.HIGH -> R.string.dl_quality_high
            DownloadQuality.MEDIUM -> R.string.dl_quality_medium
            DownloadQuality.LOW -> R.string.dl_quality_low
        },
    )

    /** The queue's own line: Wi-Fi, a connection, or Android's six-hour pause; null while it runs or is idle. */
    fun queueLine(strings: Strings, q: QueueStatus): String? = when (q) {
        QueueStatus.WaitingForWifi -> strings.get(R.string.dl_waiting_wifi)
        QueueStatus.WaitingForConnection -> strings.get(R.string.dl_waiting_connection)
        QueueStatus.PausedByTimeLimit -> strings.get(R.string.dl_paused_by_android)
        QueueStatus.Idle, is QueueStatus.Running -> null
    }

    /**
     * Decimal units with plain digits: "1.8 GB", "41 GB", "640 MB", "12 KB". One decimal under 10 of a unit, whole numbers above;
     * a trailing ".0" is dropped. Never uses the locale's digits or separators.
     */
    fun size(strings: Strings, bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        val (unit, res) = when {
            b >= GB -> GB to R.string.dl_size_gb
            b >= MB -> MB to R.string.dl_size_mb
            else -> KB to R.string.dl_size_kb
        }
        val value = BigDecimal(b).divide(BigDecimal(unit))
        val shown = (if (value < BigDecimal.TEN) value.setScale(1, RoundingMode.HALF_UP) else value.setScale(0, RoundingMode.HALF_UP)).stripTrailingZeros()
        return strings.get(res, shown.toPlainString())
    }

    /** "12 episodes · 9.8 GB". */
    fun seriesSummary(strings: Strings, episodes: Int, bytes: Long): String = strings.plural(R.plurals.dl_series_summary, episodes, size(strings, bytes))

    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L
}
