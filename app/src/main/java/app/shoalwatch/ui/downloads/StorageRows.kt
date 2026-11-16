package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.FilmUse
import app.shoalwatch.data.downloads.SeriesUse
import app.shoalwatch.data.downloads.VolumeUse
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.StorageText
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.rememberStrings

/** One volume: "This phone: 12 GB free" and "Downloads use 4 GB", read as one element. */
@Composable
internal fun VolumeRow(v: VolumeUse) {
    val strings = rememberStrings()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Text(StorageText.volume(strings, v), style = MaterialTheme.typography.bodyLarge)
        StorageText.volumeUsed(strings, v)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A series: its name, "3 episodes · 1.8 GB", "2 watched", and for one it owns none of "Downloaded by another profile" (no delete). */
@Composable
internal fun SeriesUseRow(u: SeriesUse, onDelete: (String) -> Unit) {
    val strings = rememberStrings()
    val name = u.series.item.displayName(strings)
    UseRow(
        name, listOfNotNull(
            StorageText.seriesRow(strings, u.episodes, u.bytes),
            StorageText.watched(strings, u.watched).takeIf { u.watched > 0 },
            strings.get(R.string.storage_not_owned).takeIf { u.ownedEpisodes == 0 },
        ),
        deleteLabel = StorageText.deleteSeries(strings, name).takeIf { u.ownedEpisodes > 0 },
    ) { onDelete(u.series.item.id) }
}

/** A film: its name, its size, "Watched", and for one it does not own "Downloaded by another profile" (no delete). */
@Composable
internal fun FilmUseRow(u: FilmUse, onDelete: (String) -> Unit) {
    val strings = rememberStrings()
    val name = u.film.item.displayName(strings)
    UseRow(
        name, listOfNotNull(
            DownloadText.size(strings, u.bytes),
            strings.get(R.string.state_watched).takeIf { u.watched },
            strings.get(R.string.storage_not_owned).takeIf { !u.ownedByMe },
        ),
        deleteLabel = StorageText.deleteFilm(strings, name).takeIf { u.ownedByMe },
    ) { onDelete(u.film.item.id) }
}

/** The text is one element with its whole meaning; the delete, when there is one, is its own 48dp button with its own label. */
@Composable
private fun UseRow(title: String, lines: List<String>, deleteLabel: String?, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (deleteLabel != null) {
            IconButton(onClick = onDelete, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = deleteLabel)
            }
        }
    }
}
