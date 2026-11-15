package app.shoalwatch.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.theme.ShoalwatchTheme
import java.time.Instant

/* Previews of the Server admin screens in their main states. Made-up data only. */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")
private val open = AdminFrame(gate = AdminGate.Open(session, online = true), loading = false, updatedAt = Instant.parse("2026-11-15T14:02:05Z"))
private val dashboardActions = DashboardActions(onBack = {}, onRefresh = {}, onOpen = {}, onOpenDashboard = {})

@Composable
private fun Dashboard(state: DashboardUiState) = ShoalwatchTheme { DashboardScreen(state, dashboardActions) }

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Dashboard") @Composable
private fun DashboardPreview() = Dashboard(DashboardUiState(open, ServerStatus("Den", "12.4.0", pendingRestart = false, shuttingDown = false)))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Dashboard, restart needed") @Composable
private fun DashboardRestartPreview() = Dashboard(DashboardUiState(open, ServerStatus("Den", "12.4.0", pendingRestart = true, shuttingDown = false)))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Dashboard, offline") @Composable
private fun DashboardOfflinePreview() =
    Dashboard(DashboardUiState(open.copy(gate = AdminGate.Open(session, online = false)), ServerStatus("Den", "12.4.0", false, false)))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Checking") @Composable
private fun DashboardCheckingPreview() = Dashboard(DashboardUiState())

@Preview(showBackground = true, widthDp = 840, heightDp = 1200, name = "Dashboard, tablet") @Composable
private fun DashboardTabletPreview() = Dashboard(DashboardUiState(open, ServerStatus("Den", "12.4.0", pendingRestart = true, shuttingDown = false)))
