package app.shoalwatch.ui.present

import app.shoalwatch.R
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.VolumeUse
import app.shoalwatch.data.downloads.WatchedSummary

/** The Storage screen's words, pure apart from [Strings]: sizes as [DownloadText.size], counts in digits 0-9 in every locale. */
object StorageText {
    /** "This phone: 12 GB free", "SD card: 30 GB free", or for a card that is out "SD card: not in the phone. 4 GB of downloads are saved on it." */
    fun volume(strings: Strings, v: VolumeUse): String = when {
        v.kind == StorageKind.INTERNAL -> strings.get(R.string.storage_phone_free, size(strings, v.freeBytes))
        v.available -> strings.get(R.string.storage_card_free, size(strings, v.freeBytes))
        else -> strings.get(R.string.storage_card_out, size(strings, v.usedByDownloads))
    }

    /** "Downloads use 4 GB" (only for a volume in the phone: a card that is out says it in [volume]). */
    fun volumeUsed(strings: Strings, v: VolumeUse): String? = if (v.kind == StorageKind.REMOVABLE && !v.available) null
    else strings.get(R.string.storage_downloads_use, size(strings, v.usedByDownloads))

    /** "3 episodes · 1.8 GB". */
    fun seriesRow(strings: Strings, episodes: Int, bytes: Long): String = strings.plural(R.plurals.storage_series_row, episodes, size(strings, bytes))

    /** "2 watched". */
    fun watched(strings: Strings, count: Int): String = strings.plural(R.plurals.storage_watched, count)

    fun others(strings: Strings, bytes: Long): String = strings.get(R.string.storage_others, size(strings, bytes))

    fun deleteSeries(strings: Strings, name: String): String = strings.get(R.string.storage_delete_series, name)

    fun deleteFilm(strings: Strings, name: String): String = strings.get(R.string.storage_delete_film, name)

    fun confirmSeriesTitle(strings: Strings, name: String): String = strings.get(R.string.storage_confirm_series_title, name)

    fun confirmSeriesBody(strings: Strings, bytes: Long): String = strings.get(R.string.storage_confirm_series_body, size(strings, bytes))

    /** Milestone 4's film confirm: it never promises to free the room (the copy may stay for another profile). */
    fun confirmFilm(strings: Strings, bytes: Long): String = strings.get(R.string.dl_delete_confirm, size(strings, bytes))

    fun watchedTitle(strings: Strings, summary: WatchedSummary): String = strings.plural(R.plurals.delete_watched_title, summary.count)

    /** The body, plus the shared line only when some go for every profile. */
    fun watchedBody(strings: Strings, summary: WatchedSummary): String {
        val body = strings.get(R.string.delete_watched_body, size(strings, summary.bytes))
        return if (summary.sharedForEveryone > 0) body + " " + strings.plural(R.plurals.delete_watched_shared, summary.sharedForEveryone) else body
    }

    /** "Deleted 2 downloads." (StorageMessage DELETED); KEPT_FOR_OTHERS is Milestone 4's line; FAILED its own. */
    fun deleted(strings: Strings, count: Int): String = strings.plural(R.plurals.deleted_count, count)

    fun keptForOthers(strings: Strings): String = strings.get(R.string.dl_kept_for_others)

    fun failed(strings: Strings): String = strings.get(R.string.storage_delete_failed)

    private fun size(strings: Strings, bytes: Long) = DownloadText.size(strings, bytes)
}
