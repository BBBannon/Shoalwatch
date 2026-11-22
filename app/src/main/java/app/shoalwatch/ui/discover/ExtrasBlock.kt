package app.shoalwatch.ui.discover

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.discover.DiscoverLimits
import app.shoalwatch.data.discover.OnlineTrailer
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.CardRow
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.destination

/** What the extras block can ask for. The route wires these to the ViewModel and to navigation. */
class ExtrasActions(
    val onOpen: (Destination) -> Unit,
    val onOpenPerson: (personId: String) -> Unit,
    /** A trailer kept on the server is an item: it plays in the app's player. */
    val onPlayTrailer: (itemId: String) -> Unit,
    /** An online trailer is handed to Android (see [trailerIntent]); the app never fetches it. */
    val onWatchOnline: (OnlineTrailer) -> Unit,
    val onChooseVersion: (versionId: String) -> Unit,
    val onRetry: () -> Unit,
)

/** The extras block's state with what it can ask for, passed to the detail and series screens. */
class ExtrasControls(val state: ExtrasUiState, val actions: ExtrasActions)

/**
 * The intent that opens an online trailer: `ACTION_VIEW` on the validated address and nothing else (no extras, no flags, no
 * grants), so no token or header can ride along and the other app gets no access to this one. Null for anything that is not
 * a printable `http` or `https` address of at most [DiscoverLimits.URL] characters.
 */
fun trailerIntent(url: String): Intent? {
    if (url.isEmpty() || url.length > DiscoverLimits.URL) return null
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return null
    if (uri.host.isNullOrEmpty()) return null
    return Intent(Intent.ACTION_VIEW, uri)
}

/**
 * "Version": one radio choice for each file of a film that has more than one. The pick is for the next play only. Nothing shows
 * for a title with a single version, while loading, or when the extras could not be read.
 */
@Composable
fun ExtrasVersions(controls: ExtrasControls, modifier: Modifier = Modifier) {
    val versions = controls.state.extras.versions
    if (versions.size < 2) return
    val shown = controls.state.shownVersionId
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.extras_version_heading), style = MaterialTheme.typography.titleMedium)
        versions.forEachIndexed { index, version ->
            val label = version.name.ifEmpty { stringResource(R.string.extras_version_n, index + 1) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = version.id == shown, role = Role.RadioButton, onClick = { controls.actions.onChooseVersion(version.id) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = version.id == shown, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/**
 * Cast and crew, Trailers, Extras, More like this and Part of this collection, each its own block that hides when it has nothing
 * (or could not be read). When the server can't be reached at all it says "You're offline" with Try again instead.
 */
@Composable
fun ExtrasBlock(controls: ExtrasControls, session: Session, modifier: Modifier = Modifier) {
    val state = controls.state
    val actions = controls.actions
    when (state.phase) {
        ExtrasPhase.LOADING -> return
        ExtrasPhase.OFFLINE -> {
            Column(modifier.fillMaxWidth().padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lists_offline_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.extras_offline_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton(stringResource(R.string.action_retry), actions.onRetry, Modifier.fillMaxWidth())
            }
            return
        }
        ExtrasPhase.READY -> Unit
    }
    val extras = state.extras
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (extras.cast.isNotEmpty()) {
            SectionHeader(stringResource(R.string.extras_cast))
            CardRow(extras.cast.withIndex().toList(), key = { it.index }) { (_, person) -> PersonCard(person, onOpen = { actions.onOpenPerson(person.id) }) }
        }
        if (extras.localTrailers.isNotEmpty() || extras.onlineTrailers.isNotEmpty()) {
            SectionHeader(stringResource(R.string.extras_trailers))
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extras.localTrailers.forEach { t ->
                    val label = if (t.item.name.isEmpty()) stringResource(R.string.extras_play_trailer_plain) else stringResource(R.string.extras_play_trailer, t.item.name)
                    SecondaryButton(label, { actions.onPlayTrailer(t.item.id) }, Modifier.fillMaxWidth())
                }
                extras.onlineTrailers.forEach { t ->
                    SecondaryButton(stringResource(R.string.extras_watch_trailer, t.host), { actions.onWatchOnline(t) }, Modifier.fillMaxWidth())
                }
            }
        }
        if (extras.specialFeatures.isNotEmpty()) {
            SectionHeader(stringResource(R.string.extras_extras))
            CardRow(extras.specialFeatures, key = { it.item.id }) { e -> ItemTile(e, session, onOpen = { actions.onOpen(e.destination()) }, width = TileWidth) }
        }
        if (extras.similar.isNotEmpty()) {
            SectionHeader(stringResource(R.string.extras_similar))
            CardRow(extras.similar, key = { it.item.id }) { e -> ItemTile(e, session, onOpen = { actions.onOpen(e.destination()) }, width = TileWidth) }
        }
        if (state.collections.isNotEmpty()) {
            SectionHeader(stringResource(R.string.extras_collections))
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.collections.forEach { c -> SecondaryButton(c.name, { actions.onOpen(Destination.Library(c.id)) }, Modifier.fillMaxWidth()) }
            }
        }
    }
}

private val TileWidth = 120.dp
