package app.shoalwatch.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.AdminPlayMethod
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.LibraryKind
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.data.admin.TaskOutcome
import app.shoalwatch.data.admin.TaskRun
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.admin.ConversionReason
import app.shoalwatch.data.admin.NowPlaying
import app.shoalwatch.data.admin.PlayingKind
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.WatchSession
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

private val watching = WatchSession(
    "a1", "kid", "Living room TV", "Jellyfin Android TV", "0.17.0", Instant.parse("2026-11-15T14:01:00Z"),
    NowPlaying(PlayingKind.EPISODE, "Title", "Harbour Lights", 2, 3, 750_000, 2_700_000, false, AdminPlayMethod.TRANSCODE, false, true, listOf(ConversionReason.VIDEO_FORMAT)),
    canControl = true, canMessage = true, isThisDevice = false,
)
private val idle = watching.copy(id = "a2", deviceName = "Kid's tablet", nowPlaying = null, lastActivity = Instant.parse("2026-11-15T09:00:00Z"))

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Who is watching") @Composable
private fun WatchingPreview() =
    Dashboard(DashboardUiState(open, ServerStatus("Den", "12.4.0", false, false), playing = listOf(watching), idle = listOf(idle)))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Stop confirmation") @Composable
private fun StopConfirmPreview() = Dashboard(
    DashboardUiState(open, ServerStatus("Den", "12.4.0", false, false), playing = listOf(watching), confirm = SessionConfirm.Stop("a1", "kid", "Living room TV", "Title")),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Libraries, scanning") @Composable
private fun LibrariesPreview() = ShoalwatchTheme {
    LibrariesScreen(
        LibrariesUiState(open, listOf(LibraryFolder("f", "Films", LibraryKind.FILMS, true, 30), LibraryFolder("t", "TV", LibraryKind.TV, false, null))),
        LibrariesActions({}, {}, {}, {}),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Scheduled tasks") @Composable
private fun TasksPreview() = ShoalwatchTheme {
    val run = TaskRun(TaskOutcome.FAILED, Instant.parse("2026-11-15T03:00:00Z"), "ffmpeg exited with code 1")
    TasksScreen(
        TasksUiState(open, listOf(TaskGroup("Library", listOf(ServerTask("a", "RefreshLibrary", "Scan Media Library", "Scans for new files.", "Library", TaskState.RUNNING, 40, run))))),
        TasksActions({}, {}, {}, {}, {}, {}),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Users") @Composable
private fun UsersPreview() = ShoalwatchTheme {
    val kid = ServerUser("k", "kid", Instant.parse("2026-11-15T13:30:00Z"), true, false, false, false, false, listOf("t"), false)
    UsersScreen(UsersUiState(open, listOf(UserRow(kid, listOf("TV")))), UsersActions({}, {}, {}, {}, {}, {}))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Activity") @Composable
private fun ActivityPreview() = ShoalwatchTheme {
    val at = Instant.parse("2026-11-15T12:00:00Z")
    ActivityScreen(
        ActivityUiState(open, listOf(ActivityEntry(2, "kid failed to sign in", "IP address: 192.168.1.20", at, Severity.WARNING), ActivityEntry(1, "ben signed in", null, at, Severity.INFORMATION))),
        ActivityActions({}, {}, {}),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Devices") @Composable
private fun DevicesPreview() = ShoalwatchTheme {
    val tv = ServerDevice("tv", "Living room TV", listOf("kid"), "Jellyfin Android TV", "0.17.0", Instant.parse("2026-11-15T13:57:00Z"), false, true)
    DevicesScreen(DevicesUiState(open, listOf(tv, tv.copy(id = "p", name = "Pixel", isThisDevice = true))), DevicesActions({}, {}, {}, {}, {}))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Restart or shut down") @Composable
private fun PowerPreview() = ShoalwatchTheme {
    PowerScreen(PowerUiState(open, ServerStatus("Den", "12.4.0", pendingRestart = true, shuttingDown = false)), PowerActions({}, {}, {}, {}, {}))
}
