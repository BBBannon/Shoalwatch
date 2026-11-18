package app.shoalwatch.ui.downloads

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.StorageView
import app.shoalwatch.ui.components.DismissOnSessionChange
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.present.StorageText
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.rememberStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import app.shoalwatch.ui.components.LocalCovered

data class StorageActions(
    val onBack: () -> Unit,
    val onDeleteWatched: () -> Unit,
    val onDeleteSeries: (String) -> Unit,
    val onDeleteFilm: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onTryAgain: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * "Storage": each volume's free space and what downloads use on it, a line when next episodes could not be downloaded for want
 * of space, "Delete watched", then the profile's own view of its Series and Films (largest first) and everyone else's downloads
 * as one size. One list that scrolls, so it works at font scale 2.0, the empty state included.
 */
@Composable
fun StorageScreen(state: StorageUiState, actions: StorageActions, messages: Flow<StorageMessage> = emptyFlow(), snackbar: SnackbarHostState? = null) {
    val strings = rememberStrings()
    if (snackbar != null) {
        val current = rememberUpdatedState(state.session)
        DismissOnSessionChange(snackbar, state.session)
        LaunchedEffect(messages, snackbar) {
            messages.collect { m -> if (m.session == current.value) snackbar.showSnackbar(storageMessageText(strings, m)) }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        }
        val view = state.view
        LazyColumn(Modifier.fillMaxSize()) {
            when {
                state.session == null && !state.loading -> item { SignedOutNotice(actions.onOpenServers) }
                state.localFailure -> item {
                    EmptyState(stringResource(R.string.dl_failed_locally), null, actionLabel = stringResource(R.string.action_retry), onAction = actions.onTryAgain)
                }
                view == null -> Unit
                else -> content(view, actions)
            }
            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }
    state.confirm?.let { ConfirmDialog(it, actions) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.content(view: StorageView, actions: StorageActions) {
    items(view.volumes.size) { VolumeRow(view.volumes[it]) }
    if (view.autoNextBlocked) item { Note(stringResource(R.string.storage_auto_next_blocked)) }
    if (view.series.isEmpty() && view.films.isEmpty()) {
        item { EmptyState(stringResource(R.string.storage_empty), null) }
    } else if (view.series.any { it.watched > 0 } || view.films.any { it.watched }) {
        item {
            OutlinedButton(onClick = actions.onDeleteWatched, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.storage_delete_watched))
            }
        }
    }
    if (view.series.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.storage_series), Modifier.padding(vertical = 8.dp)) }
        items(view.series, key = { "s" + it.series.item.id }) { SeriesUseRow(it, actions.onDeleteSeries) }
    }
    if (view.films.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.storage_films), Modifier.padding(vertical = 8.dp)) }
        items(view.films, key = { "f" + it.film.item.id }) { FilmUseRow(it, actions.onDeleteFilm) }
    }
    if (view.otherProfilesBytes > 0) item { Note(StorageText.others(rememberStrings(), view.otherProfilesBytes)) }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Each confirm with Delete and Cancel (48dp); the text scrolls inside at large font sizes. */
@Composable
private fun ConfirmDialog(confirm: StorageConfirm, actions: StorageActions) {
    val strings = rememberStrings()
    val (title, body) = when (confirm) {
        is StorageConfirm.Watched -> StorageText.watchedTitle(strings, confirm.summary) to StorageText.watchedBody(strings, confirm.summary)
        is StorageConfirm.Series -> StorageText.confirmSeriesTitle(strings, confirm.name) to StorageText.confirmSeriesBody(strings, confirm.bytes)
        is StorageConfirm.Film -> null to StorageText.confirmFilm(strings, confirm.bytes)
    }
    if (LocalCovered.current) return // under the lock cover (review M1)
    AlertDialog(
        onDismissRequest = actions.onCancel,
        title = title?.let { { Text(it) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body) } },
        confirmButton = { TextButton(onClick = actions.onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_delete_action)) } },
        dismissButton = { TextButton(onClick = actions.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}

internal fun storageMessageText(strings: Strings, m: StorageMessage): String = when (m.kind) {
    StorageMessage.Kind.DELETED -> StorageText.deleted(strings, m.count)
    StorageMessage.Kind.KEPT_FOR_OTHERS -> StorageText.keptForOthers(strings)
    StorageMessage.Kind.FAILED -> StorageText.failed(strings)
}
