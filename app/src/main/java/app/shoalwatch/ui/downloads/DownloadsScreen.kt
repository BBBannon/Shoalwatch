package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.DismissOnSessionChange
import app.shoalwatch.ui.components.DownloadIcon
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.rememberStrings
import kotlinx.coroutines.flow.Flow

/**
 * The Downloads tab: the queue (owners only), then Films (A to Z) and TV (one card per series), all of the active session's
 * offline library. Scrolls as one list, so it works at font scale 2.0, the empty state included. [onOpenSettings] null: the
 * settings action is not shown (nothing that does nothing).
 */
@Composable
fun DownloadsScreen(state: DownloadsUiState, actions: DownloadsActions, messages: Flow<SessionMessage>, snackbar: SnackbarHostState, onOpenSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val current = rememberUpdatedState(state.session)
    DismissOnSessionChange(snackbar, state.session)
    LaunchedEffect(messages, snackbar) {
        messages.collect { m -> if (m.session == current.value) snackbar.showSnackbar(m.text ?: context.getString(m.res)) }
    }
    val session = state.session
    // Drawn edge to edge: the title and the settings cog sit below the status bar, as on the other tabs (walkthrough W-2).
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.dl_settings_action))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            when {
                session == null || state.loading -> Unit
                state.localFailure -> item {
                    EmptyState(stringResource(R.string.dl_failed_locally), null, actionLabel = stringResource(R.string.action_retry), onAction = actions.onTryAgain)
                }
                state.empty -> item { EmptyState(stringResource(R.string.downloads_title), stringResource(R.string.dl_empty), icon = DownloadIcon) }
                else -> content(state, session, actions)
            }
            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }
    state.confirmRemove?.let { RemoveDialog(it, actions.onConfirmRemove, actions.onCancelRemove) }
}

private fun LazyListScope.content(state: DownloadsUiState, session: Session, actions: DownloadsActions) {
    val lib = state.library
    if (lib.inProgress.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.dl_section_downloading), Modifier.padding(vertical = 8.dp)) }
        item { QueueLine(state, lib.inProgress, actions) }
        items(lib.inProgress, key = { "q" + it.download.itemId }) { DownloadingRow(it, session, actions) }
    }
    if (lib.films.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.dl_section_films), Modifier.padding(vertical = 8.dp)) }
        item { Grid(lib.films.size) { i, m -> FilmCard(lib.films[i], session, actions, m) } }
    }
    if (lib.series.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.dl_section_tv), Modifier.padding(vertical = 8.dp)) }
        item { Grid(lib.series.size) { i, m -> SeriesCard(lib.series[i], session, actions, m) } }
    }
}

/** The queue's own line (Wi-Fi, a connection, Android's pause) and Pause all / Resume all. */
@Composable
private fun QueueLine(state: DownloadsUiState, queued: List<OfflineEntry>, actions: DownloadsActions) {
    val strings = rememberStrings()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DownloadText.queueLine(strings, state.queue)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val active = queued.any { it.download.state == DownloadState.QUEUED || it.download.state == DownloadState.RUNNING || it.download.state == DownloadState.VERIFYING }
        val paused = queued.any { it.download.state == DownloadState.PAUSED }
        when {
            active -> TextButton(onClick = actions.onPauseAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_pause_all)) }
            paused -> TextButton(onClick = actions.onResumeAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_resume_all)) }
        }
    }
}

/** A poster grid inside the list: as many columns as fit at 150dp (at least two). */
@Composable
private fun Grid(count: Int, cell: @Composable (Int, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val columns = (maxWidth / 150.dp).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (start in 0 until count step columns) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in start until start + columns) {
                        if (i < count) cell(i, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
