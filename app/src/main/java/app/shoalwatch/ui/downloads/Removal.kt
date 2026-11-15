package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.rememberStrings

/** What a Delete is for: one download (ready, or still in the queue), or the profile's own episodes of a season or a series. */
enum class RemoveKind { ONE, SEASON, SERIES }

/**
 * A Delete waiting for a yes: [itemIds] (each still removed through the store's owner check) and the bytes it frees, or null
 * when that is not known (a download not yet complete: only part of it is on the phone, so no size is claimed).
 */
data class RemoveRequest(val kind: RemoveKind, val itemIds: List<String>, val bytes: Long?) {
    override fun toString() = "RemoveRequest(<redacted>)"
}

/** One download: its size only once it is complete. */
internal fun removeOne(itemId: String, entry: OfflineEntry?): RemoveRequest =
    RemoveRequest(RemoveKind.ONE, listOf(itemId), entry?.download?.takeIf { it.state == DownloadState.COMPLETE }?.totalBytes)

/** The episodes among [entries] this profile owns; null when it owns none (a non-owner gets no Delete for a season or series). */
internal fun removeOwned(kind: RemoveKind, entries: List<OfflineEntry>): RemoveRequest? {
    val mine = entries.filter { it.download.ownedByMe }
    if (mine.isEmpty()) return null
    val sizes = mine.map { e -> e.download.totalBytes.takeIf { e.download.state == DownloadState.COMPLETE } }
    return RemoveRequest(kind, mine.map { it.download.itemId }, if (sizes.any { it == null }) null else sizes.sumOf { it ?: 0L })
}

/** Removes each of [r]'s items for [s]; the one snackbar line for them all (null: everything was deleted). */
internal suspend fun removeEach(downloads: Downloads, s: Session, r: RemoveRequest): Int? {
    val outcomes = r.itemIds.map { downloads.remove(s, it) }
    return when {
        outcomes.any { it == RemoveOutcome.KeptForOthers } -> R.string.dl_kept_for_others
        outcomes.isNotEmpty() && outcomes.all { it == RemoveOutcome.NotYours } -> R.string.dl_not_yours
        else -> null
    }
}

/** "Delete this download? This frees 1.8 GB on this phone." (or "Delete your 5 downloaded episodes? …") with Delete and Cancel. */
@Composable
internal fun RemoveDialog(request: RemoveRequest, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val strings = rememberStrings()
    val size = request.bytes?.let { DownloadText.size(strings, it) }
    val n = request.itemIds.size
    val text = when {
        request.kind == RemoveKind.ONE -> size?.let { stringResource(R.string.dl_delete_confirm, it) } ?: stringResource(R.string.dl_delete_size_unknown)
        size != null -> strings.plural(R.plurals.dl_delete_episodes_confirm, n, size)
        else -> strings.plural(R.plurals.dl_delete_episodes_size_unknown, n)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_delete_action)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}
