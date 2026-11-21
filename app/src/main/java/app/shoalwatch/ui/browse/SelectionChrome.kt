package app.shoalwatch.ui.browse

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.ui.components.BulkNoticesEffect
import app.shoalwatch.ui.components.CardSelect
import app.shoalwatch.ui.components.DeleteFromServerDialog
import app.shoalwatch.ui.components.SelectionControls
import kotlinx.coroutines.flow.Flow

/** What a browsing screen is given for multi-select (Milestone 10): the shared [BulkViewModel]'s state and entry points. */
class BulkHost(
    val state: BulkUiState,
    val notices: Flow<BulkNotice>,
    val onApply: (List<BulkItem>, BulkAction) -> Unit,
    val onKeepCopy: (Boolean) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    /** Opens the management screens for one item (administrators). */
    val onManage: (itemId: String) -> Unit,
    /** Whether this screen can download (false on Downloads, where it already is). */
    val canDownload: Boolean = true,
)

/** The card's selection: null when the screen has no multi-select. */
@Composable
fun rememberCardSelect(selection: Selection?, id: String, title: String): CardSelect? {
    if (selection == null) return null
    val selected = id in selection
    val label = stringResource(if (selected) R.string.select_deselect_item else R.string.select_item, title)
    val words = stringResource(if (selected) R.string.select_state_on else R.string.select_state_off)
    return CardSelect(selected, selection.active, { selection.toggle(id) }, label, words)
}

/**
 * What every screen with multi-select composes once: Back clears the selection, the bulk results go to [snackbar], the delete
 * confirmation shows, and anything picked that is no longer listed ([listed]) is dropped.
 */
@Composable
fun SelectionChrome(selection: Selection, host: BulkHost, listed: Map<String, BulkItem>, snackbar: SnackbarHostState) {
    SelectionBackHandler(selection)
    BulkNoticesEffect(host.notices, snackbar)
    LaunchedEffect(listed.keys) { selection.keepOnly(listed.keys) }
    host.state.deleteAsk?.let { ask ->
        DeleteFromServerDialog(ask, host.onKeepCopy, onConfirm = { host.onConfirmDelete(); selection.clear() }, onDismiss = host.onDismissDelete, busy = host.state.running)
    }
}

/**
 * The bar's controls for [selection] on a screen offering [offered]: Delete from server only when this profile may, Manage only
 * for an administrator and one item, More (the one-item sheet, [onMore]) only for one item and only where the screen has a sheet.
 * An action runs on the picked items as the screen lists them now; everything but Delete clears the selection at once (Delete
 * clears it when confirmed).
 */
fun selectionControls(
    selection: Selection,
    host: BulkHost,
    listed: Map<String, BulkItem>,
    offered: List<BulkAction>,
    onMore: ((itemId: String) -> Unit)?,
): SelectionControls {
    val one = selection.count == 1
    val actions = offered.filter { a ->
        when (a) {
            BulkAction.DELETE_FROM_SERVER -> host.state.canDelete
            BulkAction.MANAGE -> host.state.canManage && one
            BulkAction.MORE -> one && onMore != null
            BulkAction.DOWNLOAD -> host.canDownload
            else -> true
        }
    }
    return SelectionControls(selection.count, actions, enabled = !host.state.running, onAction = { action ->
        val items = selection.ids.mapNotNull { listed[it] }
        when (action) {
            BulkAction.MORE -> items.singleOrNull()?.let { onMore?.invoke(it.id) }.also { selection.clear() }
            BulkAction.MANAGE -> items.singleOrNull()?.let { host.onManage(it.id) }.also { selection.clear() }
            BulkAction.DELETE_FROM_SERVER -> host.onApply(items, action)
            else -> {
                host.onApply(items, action)
                selection.clear()
            }
        }
    }, onClose = selection::clear)
}

/** The selection of the screen being drawn, for list helpers deep inside it (a season's episodes). Null: no multi-select. */
val LocalSelection = androidx.compose.runtime.compositionLocalOf<Selection?> { null }
