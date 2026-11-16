package app.shoalwatch.ui.admin

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.Tone
import java.time.Instant

class DashboardActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    /** Opens one of the other [AdminRoutes]. */
    val onOpen: (route: String) -> Unit,
    val onOpenDashboard: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onMessage: (sessionId: String) -> Unit = {},
    val onPause: (sessionId: String) -> Unit = {},
    val onStop: (sessionId: String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

private class SectionLink(val route: String, @StringRes val label: Int, @StringRes val detail: Int)

private val Sections = listOf(
    SectionLink(AdminRoutes.LIBRARIES, R.string.admin_link_libraries, R.string.admin_link_libraries_detail),
    SectionLink(AdminRoutes.TASKS, R.string.admin_link_tasks, R.string.admin_link_tasks_detail),
    SectionLink(AdminRoutes.USERS, R.string.admin_link_users, R.string.admin_link_users_detail),
    SectionLink(AdminRoutes.ACTIVITY, R.string.admin_link_activity, R.string.admin_link_activity_detail),
    SectionLink(AdminRoutes.DEVICES, R.string.admin_link_devices, R.string.admin_link_devices_detail),
    SectionLink(AdminRoutes.POWER, R.string.admin_link_power, R.string.admin_link_power_detail),
)

/**
 * The Server admin dashboard: the server card (name, version, "Restart needed", shutting down), who is watching (with Message,
 * Pause and Stop), the "Manage" links to every admin screen, and the browser dashboard for everything else. [messageText] is the
 * ViewModel's message being written (never saved).
 */
@Composable
fun DashboardScreen(state: DashboardUiState, actions: DashboardActions, messageText: TextFieldState = remember { TextFieldState() }) {
    val text = rememberAdminText()
    val now = state.frame.updatedAt ?: Instant.now()
    AdminScaffold(
        stringResource(R.string.admin_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        state.status?.let { status -> item(key = "server") { ServerCard(status) { actions.onOpen(AdminRoutes.POWER) } } }
        watching(state, actions, text, now)
        item(key = "manage") { SectionHeading(stringResource(R.string.admin_manage_heading)) }
        Sections.forEach { link ->
            item(key = link.route) { AdminSectionLink(stringResource(link.label), stringResource(link.detail)) { actions.onOpen(link.route) } }
        }
        item(key = "browser") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.admin_open_dashboard), actions.onOpenDashboard, enabled = state.frame.canAct)
                Text(stringResource(R.string.admin_open_dashboard_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.frame.gate is AdminGate.Open) SessionDialogs(state, actions, messageText, text)
}

/** "Who is watching": a card per playing session, then "Connected, not playing" rows. Shown once the first answer is in. */
private fun LazyListScope.watching(state: DashboardUiState, actions: DashboardActions, text: AdminText, now: Instant) {
    if (state.frame.updatedAt == null && state.playing.isEmpty() && state.idle.isEmpty()) return
    item(key = "watching") { SectionHeading(stringResource(R.string.admin_watching_heading)) }
    if (state.playing.isEmpty()) {
        item(key = "watching-none") { Text(stringResource(R.string.admin_watching_none), style = MaterialTheme.typography.bodyMedium) }
    }
    items(state.playing, key = { "playing-${it.id}" }) { s ->
        PlayingSessionCard(s, text, now, state.frame.canAct, { actions.onMessage(s.id) }, { actions.onPause(s.id) }, { actions.onStop(s.id) })
    }
    if (state.idle.isNotEmpty()) {
        item(key = "idle") { SectionHeading(stringResource(R.string.admin_watching_idle_heading)) }
        items(state.idle, key = { "idle-${it.id}" }) { s -> IdleSessionRow(s, text, now, state.frame.canAct) { actions.onMessage(s.id) } }
    }
}

@Composable
private fun SectionHeading(label: String) {
    Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/** The open Pause/Stop confirmation or message dialog, if any. */
@Composable
private fun SessionDialogs(state: DashboardUiState, actions: DashboardActions, messageText: TextFieldState, text: AdminText) {
    state.confirm?.let { c ->
        val (rawUser, rawDevice, rawTitle) = c.who
        val user = text.name(rawUser, R.string.admin_unnamed_user)
        val device = text.name(rawDevice, R.string.admin_unnamed_device)
        val title = text.name(rawTitle, R.string.admin_untitled)
        when (c) {
            is SessionConfirm.Pause -> AdminConfirmDialog(
                stringResource(R.string.admin_confirm_pause_title, user),
                listOf(stringResource(R.string.admin_confirm_pause_body, title, device)),
                stringResource(R.string.admin_confirm_pause_action),
                state.frame.canAct, actions.onConfirm, actions.onDismissConfirm,
            )
            is SessionConfirm.Stop -> AdminConfirmDialog(
                stringResource(R.string.admin_confirm_stop_title, user),
                listOf(stringResource(R.string.admin_confirm_stop_body, title, device)),
                stringResource(R.string.admin_confirm_stop_action),
                state.frame.canAct, actions.onConfirm, actions.onDismissConfirm,
            )
        }
    }
    state.message?.let { m ->
        MessageDialog(
            stringResource(R.string.admin_message_title, text.name(m.userName, R.string.admin_unnamed_user)),
            stringResource(R.string.admin_message_body, text.name(m.deviceName, R.string.admin_unnamed_device)),
            messageText, state.frame.canAct, actions.onSend, actions.onDismissMessage,
        )
    }
}

@Composable
private fun ServerCard(status: ServerStatus, onPower: () -> Unit) {
    AdminCard {
        if (status.name.isNotEmpty()) Text(status.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (status.version.isNotEmpty()) {
            Text(stringResource(R.string.admin_version, status.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status.shuttingDown) Text(stringResource(R.string.admin_shutting_down), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (status.pendingRestart) {
            NoticeCard(stringResource(R.string.admin_pending_restart_title), stringResource(R.string.admin_pending_restart_body), tone = Tone.Caution) {
                TextButton(onClick = onPower) { Text(stringResource(R.string.admin_link_power)) }
            }
        }
    }
}
