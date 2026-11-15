package app.shoalwatch.ui.admin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminSamples.cleanCache
import app.shoalwatch.ui.admin.AdminSamples.failedTask
import app.shoalwatch.ui.admin.AdminSamples.films
import app.shoalwatch.ui.admin.AdminSamples.fullScanIdle
import app.shoalwatch.ui.admin.AdminSamples.tv
import app.shoalwatch.ui.admin.AdminSamples.uncategorised
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

abstract class LibrariesTasksRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = AdminSamples.now)
    private val asked = mutableListOf<String>()
    private val libraryActions = LibrariesActions(onBack = {}, onRefresh = {}, onScanAll = { asked += "all" }, onScan = { asked += "scan:$it" })
    private val taskActions = TasksActions(
        onBack = {}, onRefresh = {}, onRun = { asked += "run:$it" }, onStop = { asked += "stop:$it" }, onConfirmStop = { asked += "confirm" }, onDismissConfirm = {},
    )
    private val running = fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 40)

    private fun libraries(state: LibrariesUiState) = show { LibrariesScreen(state, libraryActions) }
    private fun tasks(state: TasksUiState) = show { TasksScreen(state, taskActions) }

    private fun bringIntoView(description: String) = scrollTo(hasContentDescription(description))
    private fun bringTextIntoView(label: String) = scrollTo(hasText(label))

    private fun assertAction(description: String, enabled: Boolean) {
        bringIntoView(description)
        val node = rule.onNodeWithContentDescription(description)
        node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
    }

    private fun progressWithLabel(label: String) = rule.onAllNodes(SemanticsMatcher("progress $label") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }).fetchSemanticsNodes().isNotEmpty()

    @Test fun librariesAtRest() {
        libraries(LibrariesUiState(open, listOf(films, tv), fullScanIdle))
        rule.onNode(hasText(text(R.string.admin_scan_all)) and hasClickAction()).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("all", asked.last())
        assertShown("Films")
        assertShown(text(R.string.admin_kind_tv))
        assertShown(text(R.string.admin_library_idle))
        assertAction(text(R.string.admin_scan_cd, "Films"), enabled = true)
        rule.onNodeWithContentDescription(text(R.string.admin_scan_cd, "Films")).performClick()
        assertEquals("scan:films", asked.last())
        assertAction(text(R.string.admin_scan_cd, "TV"), enabled = true)
    }

    @Test fun scanningCarriesItsPercentage() {
        val state = LibrariesUiState(open, listOf(films.copy(scanning = true, progressPercent = 30), tv.copy(scanning = true)), fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 45))
        libraries(state)
        assertTrue(tree(), progressWithLabel(text(R.string.admin_scan_all_running, "45")))
        rule.onNode(hasText(text(R.string.admin_scan_all)) and hasClickAction()).assertIsNotEnabled()
        bringTextIntoView(text(R.string.admin_library_scanning, "30"))
        assertTrue(tree(), progressWithLabel(text(R.string.admin_library_scanning, "30")))
        assertAction(text(R.string.admin_scan_cd, "Films"), enabled = false)
        bringTextIntoView(text(R.string.admin_library_scanning_unknown))
        assertShown(text(R.string.admin_library_scanning_unknown))
    }

    @Test fun librariesOfflineAndEmpty() {
        libraries(LibrariesUiState(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)), emptyList(), null))
        rule.onNode(hasText(text(R.string.admin_scan_all)) and hasClickAction()).assertIsNotEnabled()
        bringTextIntoView(text(R.string.admin_libraries_empty))
        assertShown(text(R.string.admin_libraries_empty))
    }

    @Test fun tasksByCategory() {
        tasks(TasksUiState(open, listOf(TaskGroup("Library", listOf(running, failedTask)), TaskGroup("Maintenance", listOf(cleanCache)), TaskGroup("", listOf(uncategorised)))))
        assertShown("Library")
        assertShown("Scan Media Library")
        assertTrue(tree(), progressWithLabel(text(R.string.admin_task_running, "40")))
        assertAction(text(R.string.admin_task_stop_cd, "Scan Media Library"), enabled = true)
        rule.onNodeWithContentDescription(text(R.string.admin_task_stop_cd, "Scan Media Library")).performClick()
        assertEquals("stop:${running.id}", asked.last())
        val failedLine = text(R.string.admin_task_last, text(R.string.admin_time_today, localTime("2026-11-15T03:00:00Z")), text(R.string.admin_outcome_failed))
        bringTextIntoView(failedLine)
        assertShown(failedLine)
        bringTextIntoView(text(R.string.admin_task_error, "ffmpeg exited with code 1"))
        assertShown(text(R.string.admin_task_error, "ffmpeg exited with code 1"))
        assertAction(text(R.string.admin_task_run_cd, "Clean Cache Directory"), enabled = true)
        rule.onNodeWithContentDescription(text(R.string.admin_task_run_cd, "Clean Cache Directory")).performClick()
        assertEquals("run:${cleanCache.id}", asked.last())
        bringTextIntoView(text(R.string.admin_task_never))
        assertShown(text(R.string.admin_task_never))
        bringTextIntoView(text(R.string.admin_task_category_other))
        assertShown(text(R.string.admin_task_category_other))
        bringTextIntoView("Plugin Task")
        assertShown(text(R.string.admin_task_idle))
    }

    @Test fun tasksOfflineAndEmpty() {
        tasks(TasksUiState(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)), listOf(TaskGroup("Maintenance", listOf(cleanCache)))))
        assertAction(text(R.string.admin_task_run_cd, "Clean Cache Directory"), enabled = false)
    }

    @Test fun noTasks() {
        tasks(TasksUiState(open, emptyList()))
        assertShown(text(R.string.admin_tasks_empty))
    }

    @Test fun theStopDialog() {
        tasks(TasksUiState(open, listOf(TaskGroup("Library", listOf(running))), StopTaskConfirm(running.id, "Scan Media Library")))
        rule.onNode(isDialog()).assertExists()
        assertShown(text(R.string.admin_confirm_task_stop_title, "Scan Media Library"))
        assertShown(text(R.string.admin_confirm_task_stop_body))
        rule.onNode(hasText(text(R.string.admin_confirm_task_stop_action)) and hasClickAction()).assertIsEnabled().performClick()
        assertEquals("confirm", asked.last())
    }

    private fun localTime(at: String): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.parse(at).atZone(java.time.ZoneId.systemDefault()))
}

@Config(qualifiers = PHONE) class LibrariesTasksPhoneRenderTest : LibrariesTasksRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class LibrariesTasksPhoneLargeTextRenderTest : LibrariesTasksRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class LibrariesTasksTabletRenderTest : LibrariesTasksRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class LibrariesTasksTabletLargeTextRenderTest : LibrariesTasksRenderBase() { override val fontScale = 2f }
