package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.library.Item
import app.shoalwatch.ui.components.DownloadIcon
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.components.LocalCovered

/** What a browsing screen's download controls do (the screen's DownloadActions and navigation). */
data class DownloadControls(
    val statuses: Map<String, DownloadInfo>,
    val onDownload: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onOpenDownloads: () -> Unit,
    val onSeason: (String) -> Unit = {},
    val onSeries: () -> Unit = {},
    val onUnwatched: () -> Unit = {},
)

/**
 * The detail screen's button, next to Play: "Download", then "Downloading 45%" or "Downloaded" (both open Downloads), or the
 * failure line with Retry when a retry can help. Offline it stays: the sheet then says why (it never silently does nothing).
 */
@Composable
fun DetailDownloadButton(itemId: String, controls: DownloadControls) {
    val strings = rememberStrings()
    val d = controls.statuses[itemId]
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when (d?.state) {
        null -> OutlinedButton(onClick = { controls.onDownload(itemId) }, modifier = modifier) {
            Icon(DownloadIcon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dl_download))
        }
        DownloadState.COMPLETE -> OutlinedButton(onClick = controls.onOpenDownloads, modifier = modifier) {
            Icon(Icons.Filled.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dl_downloaded))
        }
        DownloadState.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DownloadText.status(strings, d), style = MaterialTheme.typography.bodyMedium)
            if (DownloadText.canRetry(d.failure)) OutlinedButton(onClick = { controls.onRetry(itemId) }, modifier = modifier) { Text(stringResource(R.string.dl_retry)) }
            else OutlinedButton(onClick = controls.onOpenDownloads, modifier = modifier) { Text(stringResource(R.string.downloads_title)) }
        }
        else -> OutlinedButton(onClick = controls.onOpenDownloads, modifier = modifier) {
            val percent = if (d.state == DownloadState.RUNNING) DownloadText.percent(d.progress) else null
            Text(percent?.let { stringResource(R.string.dl_downloading_percent, it) } ?: stringResource(R.string.dl_downloading))
        }
    }
}

/** The series screen's "Download" with its three actions (each 56dp): this season, the whole series, every unwatched episode. */
@Composable
fun SeriesDownloadMenu(seasonId: String?, seasonLabel: String?, controls: DownloadControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(DownloadIcon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dl_download))
        }
        if (!LocalCovered.current) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = Modifier.heightIn(min = 56.dp)
            if (seasonId != null && seasonLabel != null) {
                // Specials and a season with no number get their own words (Tasks 13-16 review M-6).
                val label = when (seasonLabel) {
                    "0" -> stringResource(R.string.dl_download_specials)
                    "" -> stringResource(R.string.dl_download_this_season)
                    else -> stringResource(R.string.dl_download_season, seasonLabel)
                }
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; controls.onSeason(seasonId) }, modifier = item)
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.dl_download_series)) }, onClick = { open = false; controls.onSeries() }, modifier = item)
            DropdownMenuItem(text = { Text(stringResource(R.string.dl_download_unwatched)) }, onClick = { open = false; controls.onUnwatched() }, modifier = item)
        }
    }
}

/** An episode row's 48dp control: its label and icon follow its state ("Download Season 2, episode 5", "Downloading, 45 percent", "Downloaded"). */
@Composable
fun EpisodeDownloadControl(episode: Item, controls: DownloadControls) {
    val d = controls.statuses[episode.id]
    val season = (episode.parentIndexNumber ?: 0).toString()
    val number = (episode.indexNumber ?: 0).toString()
    // A special or an episode with no numbers is named by its title instead (Tasks 13-16 review M-6).
    val numbered = episode.parentIndexNumber != null && episode.parentIndexNumber != 0 && episode.indexNumber != null
    val title = episode.name?.ifBlank { null } ?: stringResource(R.string.untitled)
    val target = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    when (d?.state) {
        null -> IconButton(onClick = { controls.onDownload(episode.id) }, modifier = target) {
            val label = if (numbered) stringResource(R.string.dl_episode_download, season, number) else stringResource(R.string.dl_download_title, title)
            Icon(DownloadIcon, contentDescription = label)
        }
        DownloadState.COMPLETE -> IconButton(onClick = controls.onOpenDownloads, modifier = target) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dl_downloaded))
        }
        DownloadState.FAILED -> IconButton(onClick = { if (DownloadText.canRetry(d.failure)) controls.onRetry(episode.id) else controls.onOpenDownloads() }, modifier = target) {
            Icon(Icons.Filled.Refresh, contentDescription = if (numbered) stringResource(R.string.dl_episode_retry, season, number) else stringResource(R.string.dl_retry_cd, title))
        }
        else -> IconButton(onClick = controls.onOpenDownloads, modifier = target) {
            val percent = DownloadText.percent(d.progress.takeIf { d.state == DownloadState.RUNNING }) ?: "0"
            val label = stringResource(R.string.dl_episode_downloading, percent)
            CircularProgressIndicator(progress = { d.progress ?: 0f }, modifier = Modifier.size(24.dp).semantics { contentDescription = label })
        }
    }
}
