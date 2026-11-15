package app.shoalwatch.ui.admin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.Instant

abstract class AdminPartsRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = Instant.parse("2026-11-15T14:02:05Z"))

    private fun scaffold(frame: AdminFrame) = show {
        AdminScaffold("Server admin", frame, onBack = {}, onRefresh = {}) { item { Text("kid is watching Harbour Lights") } }
    }

    /** Every text drawn is at least 13sp (read from each node's laid-out text style). */
    protected fun assertNoSmallText() {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no text was found", nodes.isNotEmpty())
        for (node in nodes) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            for (r in results) {
                val size = r.layoutInput.style.fontSize
                assertTrue("text under 13sp: ${r.layoutInput.text}", size.isSpecified && size.value >= 13f)
            }
        }
    }

    @Test fun closedShowsNothingAtAll() {
        scaffold(AdminFrame(gate = AdminGate.Closed))
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(tree(), 0, texts.size)
        val described = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("no Back button, no labels", 0, described.size)
    }

    @Test fun checking() {
        scaffold(AdminFrame())
        assertShown(text(R.string.admin_checking))
        assertNotShown("kid is watching Harbour Lights")
        assertNoSmallText()
    }

    @Test fun openAndOffline() {
        scaffold(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)))
        assertShown(text(R.string.admin_offline_title))
        assertShown(text(R.string.admin_offline_body))
        assertShown("kid is watching Harbour Lights")
        assertNoSmallText()
    }

    @Test fun theProblemLineIsALiveRegionWithDismiss() {
        scaffold(open.copy(problem = AdminProblem.NETWORK))
        val live = rule.onAllNodes(
            hasText(text(R.string.admin_problem_network)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        ).fetchSemanticsNodes()
        assertTrue(tree(), live.isNotEmpty())
        assertShown(text(R.string.admin_dismiss))
    }

    @Test fun lastUpdatedAndRefresh() {
        scaffold(open.copy(notice = AdminNotice(R.string.admin_problem_gone)))
        assertTrue(tree(), rule.onAllNodes(hasText(text(R.string.admin_updated_at, ""), substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertShown(text(R.string.admin_problem_gone))
        rule.onNodeWithContentDescription(text(R.string.admin_refresh)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertNoSmallText()
    }

    @Test fun typedConfirmation() {
        val typed = TextFieldState()
        show { TypedConfirmDialog("Restart Test Jellyfin?", "Everyone watching will be stopped.", "restart", typed, "Restart server", true, {}, {}) }
        rule.onNodeWithText("Restart server").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Restart ")
        rule.onNodeWithText("Restart server").assertIsEnabled()
        assertShown(text(R.string.admin_type_to_confirm, "restart"))
        assertNoSmallText()
    }

    @Test fun messageDialog() {
        val message = TextFieldState()
        show { MessageDialog("Send a message to kid", "It shows on Living room TV for 10 seconds.", message, true, {}, {}) }
        val send = text(R.string.admin_message_send)
        rule.onNode(hasSetTextAction()).performTextInput("   ")
        rule.onNodeWithText(send).assertIsNotEnabled()
        rule.runOnIdle { message.edit { replace(0, length, "hi") } }
        rule.onNodeWithText(send).assertIsEnabled()
        assertShown(text(R.string.admin_message_counter, "2"))
        assertNoSmallText()
    }

    @Test fun anActionNamesItsTargetAndIsBigEnough() {
        show { AdminAction("Stop", "Stop kid's playback on Living room TV", enabled = true, caution = true) {} }
        assertTrue(rule.onAllNodes(hasContentDescription("Stop kid's playback on Living room TV")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Stop kid's playback on Living room TV").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val node = rule.onNodeWithContentDescription("Stop kid's playback on Living room TV").fetchSemanticsNode()
        assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription)!!.contains("Stop kid's playback on Living room TV"))
        assertNoSmallText()
    }

    @Test fun partsAtLargeText() {
        show {
            androidx.compose.foundation.layout.Column {
                AdminCard { Text("Living room TV") }
                AdminBadge("Administrator")
                AdminSectionLink("Libraries", "Scan for new files and see scan progress") {}
                AdminProgress(42, "Scanning: 42%")
                AdminProgress(null, "Scanning…")
            }
        }
        assertShown("Administrator")
        assertNoSmallText()
    }
}

@Config(qualifiers = PHONE) class AdminPartsPhoneRenderTest : AdminPartsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class AdminPartsPhoneLargeTextRenderTest : AdminPartsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class AdminPartsTabletRenderTest : AdminPartsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class AdminPartsTabletLargeTextRenderTest : AdminPartsRenderBase() { override val fontScale = 2f }
