package app.shoalwatch.ui.admin

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.Instant

abstract class DashboardRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = Instant.parse("2026-11-15T14:02:05Z"))
    private val opened = mutableListOf<String>()
    private var browser = 0
    private val actions = DashboardActions(onBack = {}, onRefresh = {}, onOpen = { opened += it }, onOpenDashboard = { browser++ })

    private fun dashboard(pendingRestart: Boolean = false, shuttingDown: Boolean = false) =
        show { DashboardScreen(DashboardUiState(open, ServerStatus("Den", "12.4.0", pendingRestart, shuttingDown)), actions) }

    private fun headingNamed(text: String) = rule.onAllNodes(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        .fetchSemanticsNodes().isNotEmpty()

    private val links = listOf(
        Triple(R.string.admin_link_libraries, R.string.admin_link_libraries_detail, AdminRoutes.LIBRARIES),
        Triple(R.string.admin_link_tasks, R.string.admin_link_tasks_detail, AdminRoutes.TASKS),
        Triple(R.string.admin_link_users, R.string.admin_link_users_detail, AdminRoutes.USERS),
        Triple(R.string.admin_link_activity, R.string.admin_link_activity_detail, AdminRoutes.ACTIVITY),
        Triple(R.string.admin_link_devices, R.string.admin_link_devices_detail, AdminRoutes.DEVICES),
        Triple(R.string.admin_link_power, R.string.admin_link_power_detail, AdminRoutes.POWER),
    )

    private fun scrollToText(text: String) = rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))

    @Test fun theServerCard() {
        dashboard()
        assertTrue(tree(), headingNamed(text(R.string.admin_title)))
        assertTrue(tree(), headingNamed("Den"))
        assertShown(text(R.string.admin_version, "12.4.0"))
        assertNotShown(text(R.string.admin_pending_restart_title))
        assertNotShown(text(R.string.admin_shutting_down))
    }

    @Test fun restartNeeded() {
        dashboard(pendingRestart = true, shuttingDown = true)
        assertShown(text(R.string.admin_pending_restart_title))
        assertShown(text(R.string.admin_pending_restart_body))
        assertShown(text(R.string.admin_shutting_down))
        // The notice's button and the Manage link share the words; the notice's comes first.
        rule.onAllNodesWithText(text(R.string.admin_link_power)).onFirst().performClick()
        assertEquals(listOf(AdminRoutes.POWER), opened)
    }

    @Test fun everyLinkIsOneTargetWithItsDetail() {
        dashboard()
        scrollToText(text(R.string.admin_manage_heading))
        assertTrue(tree(), headingNamed(text(R.string.admin_manage_heading)))
        for ((label, detail, route) in links) {
            scrollToText(text(detail))
            val node = rule.onNode(hasClickAction() and hasText(text(label)) and hasText(text(detail)))
            node.assertHeightIsAtLeast(48.dp)
            node.performClick()
            assertEquals(route, opened.last())
        }
        assertEquals(links.map { it.third }, opened)
    }

    @Test fun theBrowserDashboard() {
        dashboard()
        scrollToText(text(R.string.admin_open_dashboard_note))
        assertShown(text(R.string.admin_open_dashboard))
        assertShown(text(R.string.admin_open_dashboard_note))
        rule.onAllNodesWithText(text(R.string.admin_open_dashboard)).onFirst().performClick()
        assertEquals(1, browser)
    }

    @Test fun nothingIsTooSmall() {
        dashboard(pendingRestart = true)
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in nodes) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            for (r in results) assertTrue("${r.layoutInput.text}", r.layoutInput.style.fontSize.value >= 13f)
        }
    }
}

@Config(qualifiers = PHONE) class DashboardPhoneRenderTest : DashboardRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class DashboardPhoneLargeTextRenderTest : DashboardRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class DashboardTabletRenderTest : DashboardRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class DashboardTabletLargeTextRenderTest : DashboardRenderBase() { override val fontScale = 2f }
