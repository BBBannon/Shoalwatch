package app.shoalwatch.ui.manage

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.ui.admin.AdminAction
import app.shoalwatch.ui.admin.AdminCard
import app.shoalwatch.ui.admin.AdminFormDialog
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminScaffold
import app.shoalwatch.ui.admin.AdminSectionLink
import app.shoalwatch.ui.components.DeleteFromServerDialog

class ManageActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onIdentify: () -> Unit,
    val onImages: () -> Unit,
    val onEdit: () -> Unit,
    val onAskRefresh: () -> Unit,
    val onSetRefresh: (RefreshAsk) -> Unit,
    val onConfirmRefresh: () -> Unit,
    val onDismissRefresh: () -> Unit,
    val onAskDelete: () -> Unit,
    val onKeepCopy: (Boolean) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * The management hub for one item (administrators): the item's name as the title, then Identify (films and series), Pictures and
 * Edit details as links, Refresh details (its own choices) and Delete from server (named, confirmed). Secure, as every admin screen.
 */
@Composable
fun ManageScreen(state: ManageUiState, actions: ManageActions) {
    val shown = state.item
    val title = shown?.title?.ifEmpty { null } ?: stringResource(R.string.manage_title)
    AdminScaffold(title, state.frame, actions.onBack, actions.onReload, actions.onDismissProblem, actions.onDismissNotice) {
        if (shown == null) {
            if (!state.frame.loading) item(key = "none") { Text(stringResource(R.string.manage_not_loaded), style = MaterialTheme.typography.bodyMedium) }
            return@AdminScaffold
        }
        item(key = "links") {
            AdminCard {
                if (shown.kind == ItemKind.MOVIE || shown.kind == ItemKind.SERIES) {
                    AdminSectionLink(stringResource(R.string.manage_identify), stringResource(R.string.manage_identify_help), actions.onIdentify)
                }
                AdminSectionLink(stringResource(R.string.manage_images), stringResource(R.string.manage_images_help), actions.onImages)
                AdminSectionLink(stringResource(R.string.manage_edit), stringResource(R.string.manage_edit_help), actions.onEdit)
            }
        }
        item(key = "refresh") {
            AdminCard {
                Text(stringResource(R.string.manage_refresh_help), style = MaterialTheme.typography.bodyMedium)
                AdminAction(stringResource(R.string.manage_refresh), stringResource(R.string.manage_refresh), state.frame.canAct, onClick = actions.onAskRefresh)
            }
        }
        // Only for a profile whose policy allows deleting: being an administrator does not imply it.
        if (state.canDelete) item(key = "delete") {
            AdminCard {
                Text(stringResource(R.string.manage_delete_help), style = MaterialTheme.typography.bodyMedium)
                AdminAction(stringResource(R.string.select_delete_server), stringResource(R.string.select_delete_server), state.frame.canAct, caution = true, onClick = actions.onAskDelete)
            }
        }
    }
    if (state.frame.gate !is AdminGate.Open) return
    state.refreshAsk?.let { ask -> RefreshDialog(quoted(shown?.title.orEmpty()), ask, state.frame.canAct, actions) }
    state.deleteAsk?.let { ask -> DeleteFromServerDialog(ask, actions.onKeepCopy, actions.onConfirmDelete, actions.onDismissDelete, busy = !state.frame.canAct) }
}

@Composable
private fun RefreshDialog(name: String, ask: RefreshAsk, enabled: Boolean, actions: ManageActions) {
    AdminFormDialog(
        stringResource(R.string.manage_refresh_title, name), stringResource(R.string.manage_refresh_action), enabled,
        actions.onConfirmRefresh, actions.onDismissRefresh,
    ) {
        for (mode in RefreshMode.entries) {
            RadioRow(stringResource(mode.label()), ask.mode == mode) { actions.onSetRefresh(ask.copy(mode = mode)) }
        }
        CheckRow(
            stringResource(R.string.manage_refresh_images), ask.replaceImages && ask.mode != RefreshMode.SCAN,
            { actions.onSetRefresh(ask.copy(replaceImages = it)) }, enabled = ask.mode != RefreshMode.SCAN,
        )
    }
}
