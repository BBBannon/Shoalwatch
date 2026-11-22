package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.browse.BulkAction
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.DeleteAsk

/** What a screen hands the bar: the actions it offers (already cut to what this profile may do) and what each does. */
class SelectionControls(
    val count: Int,
    val actions: List<BulkAction>,
    val enabled: Boolean,
    val onAction: (BulkAction) -> Unit,
    val onClose: () -> Unit,
)

/**
 * The top bar while items are selected (Milestone 10): Close, "3 selected" (announced politely as it changes), then Mark as
 * watched, Favourite and Download (or Delete download) as buttons, and the rest under "More options". Each control is 48dp and
 * names what it does.
 */
@Composable
fun SelectionBar(controls: SelectionControls, modifier: Modifier = Modifier) {
    val shown = listOf(BulkAction.MARK_WATCHED, BulkAction.FAVOURITE, BulkAction.DOWNLOAD, BulkAction.REMOVE_DOWNLOAD).filter { it in controls.actions }
    val more = controls.actions.filter { it !in shown }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controls.onClose, modifier = Modifier.size(TouchTarget)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.select_close))
            }
            Text(
                pluralStringResource(R.plurals.select_count, controls.count, controls.count),
                Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            for (action in shown) {
                IconButton(onClick = { controls.onAction(action) }, enabled = controls.enabled, modifier = Modifier.size(TouchTarget)) {
                    Icon(action.icon(), contentDescription = stringResource(action.label()))
                }
            }
            if (more.isNotEmpty()) OverflowMenu(more, controls)
        }
    }
}

@Composable
private fun OverflowMenu(more: List<BulkAction>, controls: SelectionControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = controls.enabled, modifier = Modifier.size(TouchTarget)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.select_more_options))
        }
        if (!LocalCovered.current) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (action in more) {
                DropdownMenuItem(
                    text = { Text(stringResource(action.label())) },
                    onClick = { open = false; controls.onAction(action) },
                    modifier = Modifier.heightIn(min = TouchTarget),
                )
            }
        }
    }
}

private fun BulkAction.icon(): ImageVector = when (this) {
    BulkAction.MARK_WATCHED -> Icons.Filled.Check
    BulkAction.FAVOURITE -> Icons.Filled.Favorite
    BulkAction.DOWNLOAD -> DownloadIcon
    BulkAction.REMOVE_DOWNLOAD -> Icons.Filled.Delete
    else -> Icons.Filled.MoreVert
}

/** The words for each action, in the bar and its menu. */
fun BulkAction.label(): Int = when (this) {
    BulkAction.MARK_WATCHED -> R.string.select_mark_watched
    BulkAction.MARK_UNWATCHED -> R.string.select_mark_unwatched
    BulkAction.FAVOURITE -> R.string.select_favourite
    BulkAction.UNFAVOURITE -> R.string.select_unfavourite
    BulkAction.DOWNLOAD -> R.string.select_download
    BulkAction.REMOVE_DOWNLOAD -> R.string.select_remove_download
    BulkAction.ADD_TO_PLAYLIST -> R.string.select_add_to_playlist
    BulkAction.ADD_TO_COLLECTION -> R.string.select_add_to_collection
    BulkAction.DELETE_FROM_SERVER -> R.string.select_delete_server
    BulkAction.MANAGE -> R.string.select_manage
    BulkAction.MORE -> R.string.select_more
}

/**
 * "Delete "The Silent Harbour" from the server?" (several: the first three names and "and 2 more"). Says it cannot be undone and
 * that it goes for everyone; with downloads among them (a series' or season's episodes too), "Keep the copies on this phone" (off by
 * default). [busy]: another action is still running, so Delete is off (and says why) until it ends; Cancel still works.
 */
@Composable
fun DeleteFromServerDialog(ask: DeleteAsk, onKeepCopy: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, busy: Boolean = false) {
    val title = deleteTitle(ask.items)
    ConfirmDialog(title, stringResource(R.string.delete_server_confirm), onConfirm, onDismiss, confirmEnabled = !busy) {
        Column {
            Text(stringResource(R.string.delete_server_body), style = MaterialTheme.typography.bodyMedium)
            if (busy) Text(stringResource(R.string.delete_server_wait), style = MaterialTheme.typography.bodyMedium)
            if (ask.savedCount > 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                        .toggleable(value = ask.keepCopy, role = Role.Checkbox, onValueChange = onKeepCopy),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ask.keepCopy, onCheckedChange = null)
                    Text(
                        pluralStringResource(R.plurals.delete_server_keep_copy, ask.savedCount),
                        Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun deleteTitle(items: List<BulkItem>): String {
    val unnamed = stringResource(R.string.delete_server_unnamed)
    val names = items.take(3).map { "“${it.title.ifEmpty { unnamed }}”" }
    return when {
        items.size == 1 -> stringResource(R.string.delete_server_title_one, names.single())
        items.size <= 3 -> stringResource(R.string.delete_server_title_few, names.joinToString(", "))
        else -> pluralStringResource(R.plurals.delete_server_title_many, items.size - 3, names.joinToString(", "), items.size - 3)
    }
}

/** Shows each bulk result once in [snackbar], in plain words with its count. */
@Composable
fun BulkNoticesEffect(notices: kotlinx.coroutines.flow.Flow<app.shoalwatch.ui.browse.BulkNotice>, snackbar: androidx.compose.material3.SnackbarHostState) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    androidx.compose.runtime.LaunchedEffect(notices, snackbar) {
        notices.collect { n ->
            val text = if (n.plural != 0) resources.getQuantityString(n.plural, n.count, n.count) else resources.getString(n.text)
            snackbar.showSnackbar(text)
        }
    }
}
