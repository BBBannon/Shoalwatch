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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.ui.admin.AdminSamples.benHere
import app.shoalwatch.ui.admin.AdminSamples.guestPaused
import app.shoalwatch.ui.admin.AdminSamples.kidIdle
import app.shoalwatch.ui.admin.AdminSamples.kidOnTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.Instant

abstract class DashboardRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = Instant.parse("2026-11-15T14:02:05Z"))
    private val opened = mutableListOf<String>()
    private var browser = 0
    private val actions = DashboardActions(onBack = {}, onRefresh = {}, onOpen = { opened += it }, onOpenDashboard = { browser++ })

    private val asked = mutableListOf<String>()
    private val sessionActions = DashboardActions(
        onBack = {}, onRefresh = {}, onOpen = {}, onOpenDashboard = {},
        onMessage = { asked += "message:$it" }, onPause = { asked += "pause:$it" }, onStop = { asked += "stop:$it" },
        onConfirm = { asked += "confirm" },
    )

    private fun dashboard(pendingRestart: Boolean = false, shuttingDown: Boolean = false) =
        show { DashboardScreen(DashboardUiState(open, ServerStatus("Den", "12.4.0", pendingRestart, shuttingDown)), actions) }

    private fun watching(
        playing: List<WatchSession>,
        idle: List<WatchSession> = emptyList(),
        online: Boolean = true,
        confirm: SessionConfirm? = null,
    ) = show {
        val frame = open.copy(gate = AdminGate.Open(Session(S1, U1), online = online))
        DashboardScreen(DashboardUiState(frame, ServerStatus("Den", "12.4.0", false, false), playing, idle, confirm), sessionActions)
    }

    private fun scrollToDescribed(label: String) = rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription(label))

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

    @Test fun aPlayingCard() {
        watching(listOf(kidOnTv))
        assertTrue(tree(), headingNamed(text(R.string.admin_watching_heading)))
        assertTrue(tree(), headingNamed("kid"))
        assertShown(text(R.string.admin_session_episode, "Harbour Lights", "2", "3", "Title"))
        assertTrue(tree(), described(text(R.string.admin_session_episode_spoken, "Harbour Lights", "2", "3", "Title")))
        assertShown(text(R.string.admin_session_device, "Living room TV", "Jellyfin Android TV", "0.17.0"))
        scrollToText(text(R.string.admin_session_progress, "12:30", "45:00"))
        assertShown(text(R.string.admin_session_progress, "12:30", "45:00"))
        val bar = rule.onAllNodes(SemanticsMatcher("progress of 12:30 in 45:00") { node ->
            val info = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            info != null && info.range == 0f..1f && kotlin.math.abs(info.current - 750f / 2700f) < 0.001f
        }).fetchSemanticsNodes()
        assertTrue(tree(), bar.isNotEmpty())
        assertShown(text(R.string.admin_convert_sentence, text(R.string.admin_convert_video), text(R.string.admin_reason_video_format)))
        assertNotShown(text(R.string.admin_session_paused))
        assertNotShown(text(R.string.admin_watching_none))
    }

    @Test fun theCardsButtonsNameWhoAndWhere() {
        watching(listOf(kidOnTv))
        val stop = text(R.string.admin_session_stop_cd, "kid", "Living room TV")
        val message = text(R.string.admin_session_message_cd, "kid", "Living room TV")
        val pause = text(R.string.admin_session_pause_cd, "kid", "Living room TV")
        for ((label, expected) in listOf(stop to "stop:${kidOnTv.id}", message to "message:${kidOnTv.id}", pause to "pause:${kidOnTv.id}")) {
            scrollToDescribed(label)
            val node = rule.onNodeWithContentDescription(label)
            node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled()
            node.performClick()
            assertEquals(expected, asked.last())
        }
    }

    @Test fun aPausedFilmHasNoPauseButton() {
        watching(listOf(guestPaused))
        scrollToText(text(R.string.admin_session_paused))
        assertShown(text(R.string.admin_session_paused))
        assertShown("The Long Tide")
        assertFalse(described(text(R.string.admin_session_pause_cd, "guest", "Laptop")))
        scrollToDescribed(text(R.string.admin_session_stop_cd, "guest", "Laptop"))
        assertDescribed(text(R.string.admin_session_stop_cd, "guest", "Laptop"))
    }

    @Test fun thisPhoneHasABadgeAndNoButtons() {
        watching(listOf(benHere))
        assertShown(text(R.string.admin_this_phone))
        assertFalse(describedStarting(text(R.string.admin_session_stop_cd, "ben", "Pixel")))
        assertFalse(described(text(R.string.admin_session_message_cd, "ben", "Pixel")))
        assertFalse(described(text(R.string.admin_session_pause_cd, "ben", "Pixel")))
    }

    @Test fun idleRowsAndNobodyWatching() {
        watching(emptyList(), idle = listOf(kidIdle))
        assertShown(text(R.string.admin_watching_none))
        scrollToText(text(R.string.admin_watching_idle_heading))
        assertTrue(tree(), headingNamed(text(R.string.admin_watching_idle_heading)))
        val line = listOf("kid", "Kid's tablet", text(R.string.admin_last_active, "12 Nov 2026")).joinToString(" · ")
        scrollToText(line)
        assertShown(line)
        scrollToDescribed(text(R.string.admin_session_message_cd, "kid", "Kid's tablet"))
        assertDescribed(text(R.string.admin_session_message_cd, "kid", "Kid's tablet"))
    }

    @Test fun offlineSwitchesEveryActionOff() {
        watching(listOf(kidOnTv), idle = listOf(kidIdle), online = false)
        for (label in listOf(
            text(R.string.admin_session_stop_cd, "kid", "Living room TV"),
            text(R.string.admin_session_pause_cd, "kid", "Living room TV"),
            text(R.string.admin_session_message_cd, "kid", "Living room TV"),
            text(R.string.admin_session_message_cd, "kid", "Kid's tablet"),
        )) {
            scrollToDescribed(label)
            rule.onNodeWithContentDescription(label).assertIsNotEnabled()
        }
    }

    @Test fun theStopDialogNamesWhoAndWhat() {
        watching(listOf(kidOnTv), confirm = SessionConfirm.Stop(kidOnTv.id, "kid", "Living room TV", "Title"))
        val dialog = rule.onNode(isDialog())
        dialog.assertExists()
        assertShown(text(R.string.admin_confirm_stop_title, "kid"))
        assertShown(text(R.string.admin_confirm_stop_body, "Title", "Living room TV"))
        rule.onNode(hasText(text(R.string.admin_confirm_stop_action)) and hasClickAction()).assertIsEnabled().performClick()
        assertEquals("confirm", asked.last())
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
