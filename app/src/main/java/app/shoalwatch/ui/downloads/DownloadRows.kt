package app.shoalwatch.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.SeriesSummary
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.WatchedBadge
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.episodeCode
import app.shoalwatch.ui.present.formatDuration
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.remainingLabel
import app.shoalwatch.ui.present.wideArtwork
import app.shoalwatch.ui.components.LocalCovered

/** The lines under a ready copy: "Insert the SD card…" when its card is out, "Downloaded by another profile" for a shared one. */
internal fun noteLines(strings: Strings, e: OfflineEntry): List<String> = listOfNotNull(
    strings.get(R.string.dl_unavailable).takeIf { !e.download.available },
    strings.get(R.string.dl_shared).takeIf { !e.download.ownedByMe },
)

/** A 48dp text button whose label for TalkBack names its item ("Pause Film X"). */
@Composable
internal fun RowButton(text: String, spoken: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) { Text(text) }
}

/**
 * One download in the queue (owners only): artwork, title and status as ONE element with its whole meaning (and the progress as
 * a range with words), then its own 48dp buttons, each with its own label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadingRow(e: OfflineEntry, session: Session, actions: DownloadsActions) {
    val strings = rememberStrings()
    val d = e.download
    val title = e.item.item.displayName(strings)
    val status = DownloadText.status(strings, d)
    val spoken = listOf(title, DownloadText.spokenStatus(strings, d)).joinToString(", ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = spoken
                if (d.state == DownloadState.RUNNING) progressBarRangeInfo = ProgressBarRangeInfo(d.progress ?: 0f, 0f..1f)
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(wideArtwork(e.item.item, session), null, aspect = ArtworkAspect.WIDE, title = title, modifier = Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.state == DownloadState.RUNNING) {
                    val p = d.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                d.state == DownloadState.PAUSED -> RowButton(stringResource(R.string.dl_resume), stringResource(R.string.dl_resume_cd, title)) { actions.onResume(d.itemId) }
                d.state == DownloadState.FAILED && d.failure == DownloadFailure.SIGN_IN_NEEDED ->
                    RowButton(stringResource(R.string.action_sign_in), stringResource(R.string.dl_sign_in_cd, title)) { actions.onSignIn(session.serverId) }
                d.state == DownloadState.FAILED -> if (DownloadText.canRetry(d.failure)) {
                    RowButton(stringResource(R.string.dl_retry), stringResource(R.string.dl_retry_cd, title)) { actions.onRetry(d.itemId) }
                }
                else -> RowButton(stringResource(R.string.dl_pause), stringResource(R.string.dl_pause_cd, title)) { actions.onPause(d.itemId) }
            }
            RowButton(stringResource(R.string.dl_cancel_download), stringResource(R.string.dl_cancel_cd, title)) { actions.onAskRemove(d.itemId) }
        }
    }
}

/**
 * The 48dp "more" button of a ready copy, a season or a series: "Delete download" ([label]), for its owners only (a non-owner
 * gets no button at all).
 */
@Composable
internal fun DeleteMenu(title: String, onDelete: () -> Unit, modifier: Modifier = Modifier, label: Int = R.string.dl_delete) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions, title))
        }
        if (!LocalCovered.current) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onDelete() }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

/** A downloaded film in the grid: one element (poster, title, its lines) that opens the detail screen, and the owner's menu. */
@Composable
internal fun FilmCard(e: OfflineEntry, session: Session, actions: DownloadsActions, modifier: Modifier = Modifier) {
    val strings = rememberStrings()
    val title = e.item.item.displayName(strings)
    val lines = listOf(DownloadText.status(strings, e.download)) + noteLines(strings, e)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button }.clickable { actions.onOpenItem(e.item.item.id) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Artwork(e.item.item.primaryArtwork(session), null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.fillMaxWidth())
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (e.download.ownedByMe) DeleteMenu(title, { actions.onAskRemove(e.item.item.id) }, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * One card per downloaded series: "12 episodes · 9.8 GB"; opens the offline series screen. "Delete downloaded series" (the
 * profile's own episodes of it) only for a profile that owns any of it.
 */
@Composable
internal fun SeriesCard(s: SeriesSummary, session: Session, actions: DownloadsActions, modifier: Modifier = Modifier) {
    val strings = rememberStrings()
    val title = s.series.item.displayName(strings)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button }.clickable { actions.onOpenSeries(s.series.item.id) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Artwork(s.series.item.primaryArtwork(session), null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.fillMaxWidth())
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            // Every episode watched and deleted: the show stays listed, as its series screen still shows them.
            val summary = if (s.episodeCount == 0) stringResource(R.string.offline_released) else DownloadText.seriesSummary(strings, s.episodeCount, s.bytes)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.ownedCount > 0) DeleteMenu(title, { actions.onAskRemoveSeries(s.series.item.id) }, Modifier.align(Alignment.TopEnd), R.string.dl_delete_series)
    }
}

/**
 * A watched-and-deleted episode on the offline series screen: "S1 E3 · Title", the watched tick when this profile watched it,
 * and "Deleted from this phone. Download it again when you're online.", as one element that opens the detail screen. No Play.
 */
@Composable
internal fun ReleasedEpisodeRow(e: ItemWithUserData, session: Session, onOpen: (String) -> Unit) {
    val strings = rememberStrings()
    val item = e.item
    val name = item.displayName(strings)
    val title = listOfNotNull(episodeCode(strings, item), name).joinToString(" · ")
    val watched = e.userData?.played == true
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }.clickable { onOpen(item.id) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(112.dp)) {
            Artwork(wideArtwork(item, session), null, aspect = ArtworkAspect.WIDE, title = name, modifier = Modifier.fillMaxWidth())
            if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (watched) Text(strings.get(R.string.state_watched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.offline_released_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A downloaded episode on the offline series screen: "S1 E3 · Title", runtime, a watched tick or "23 min left", and the
 * availability lines, as one element that opens the detail screen; then its own 48dp Play ("Play Season 1, episode 3, Title")
 * and, for its owners only, the "more" button with "Delete download" (Tasks 13-16 review I-2).
 */
@Composable
internal fun OfflineEpisodeRow(e: OfflineEntry, session: Session, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onAskRemove: (String) -> Unit = {}) {
    val strings = rememberStrings()
    val item = e.item.item
    val name = item.displayName(strings)
    val code = episodeCode(strings, item)
    val title = listOfNotNull(code, name).joinToString(" · ")
    val watched = e.item.userData?.played == true
    val left = if (watched) null else remainingLabel(strings, item.runtimeMs, e.item.userData?.positionMs ?: 0)
    val meta = listOfNotNull(item.runtimeMs?.let { formatDuration(strings, it) }, left, strings.get(R.string.state_watched).takeIf { watched })
    val lines = noteLines(strings, e)
    val playLabel = if (item.parentIndexNumber != null && item.parentIndexNumber != 0 && item.indexNumber != null) {
        stringResource(R.string.dl_play_episode, item.parentIndexNumber.toString(), item.indexNumber.toString(), name)
    } else stringResource(R.string.dl_play, name)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { role = Role.Button }.clickable { onOpen(item.id) },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(112.dp)) {
                Artwork(wideArtwork(item, session), null, aspect = ArtworkAspect.WIDE, title = name, modifier = Modifier.fillMaxWidth())
                if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        IconButton(onClick = { onPlay(item.id) }, enabled = e.download.available, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = playLabel)
        }
        if (e.download.ownedByMe) DeleteMenu(title, { onAskRemove(item.id) })
    }
}
