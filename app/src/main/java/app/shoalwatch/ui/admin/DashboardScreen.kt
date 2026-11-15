package app.shoalwatch.ui.admin

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

class DashboardActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    /** Opens one of the other [AdminRoutes]. */
    val onOpen: (route: String) -> Unit,
    val onOpenDashboard: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
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
 * The Server admin dashboard: the server card (name, version, "Restart needed", shutting down), who is watching (Task 7), the
 * "Manage" links to every admin screen, and the browser dashboard for everything else.
 */
@Composable
fun DashboardScreen(state: DashboardUiState, actions: DashboardActions) {
    AdminScaffold(
        stringResource(R.string.admin_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        state.status?.let { status -> item(key = "server") { ServerCard(status) { actions.onOpen(AdminRoutes.POWER) } } }
        item(key = "manage") {
            Text(
                stringResource(R.string.admin_manage_heading),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
        }
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
