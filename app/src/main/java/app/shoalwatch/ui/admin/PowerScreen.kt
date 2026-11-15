package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.Tone

class PowerActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onAsk: (PowerAction) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * The server card (name, version, "Restart needed"), then Restart and Shut down. Each opens a dialog naming the server whose
 * confirm button works only once the word is typed into [typed] (the ViewModel's, never saved).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PowerScreen(state: PowerUiState, actions: PowerActions, typed: TextFieldState = remember { TextFieldState() }) {
    val text = rememberAdminText()
    AdminScaffold(
        stringResource(R.string.admin_power_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        state.status?.let { status ->
            item(key = "server") {
                AdminCard {
                    if (status.name.isNotEmpty()) Text(status.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (status.version.isNotEmpty()) {
                        Text(stringResource(R.string.admin_version, status.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (status.pendingRestart) {
                        NoticeCard(stringResource(R.string.admin_pending_restart_title), stringResource(R.string.admin_pending_restart_body), tone = Tone.Caution)
                    }
                }
            }
        }
        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val restart = stringResource(R.string.admin_restart)
                val shutDown = stringResource(R.string.admin_shutdown)
                AdminAction(restart, restart, state.canAsk, caution = true) { actions.onAsk(PowerAction.RESTART) }
                AdminAction(shutDown, shutDown, state.canAsk, caution = true) { actions.onAsk(PowerAction.SHUT_DOWN) }
            }
        }
    }
    val confirm = state.confirm
    val status = state.status
    if (state.frame.gate is AdminGate.Open && confirm != null && status != null) {
        val server = text.name(status.name, R.string.admin_untitled)
        val restart = confirm == PowerAction.RESTART
        TypedConfirmDialog(
            title = stringResource(if (restart) R.string.admin_confirm_restart_title else R.string.admin_confirm_shutdown_title, server),
            body = stringResource(if (restart) R.string.admin_confirm_restart_body else R.string.admin_confirm_shutdown_body),
            word = stringResource(confirm.word),
            typed = typed,
            confirmLabel = stringResource(if (restart) R.string.admin_restart else R.string.admin_shutdown),
            enabled = state.frame.canAct,
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismiss,
        )
    }
}
