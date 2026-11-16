package app.shoalwatch.ui.admin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.junit.Test
import org.robolectric.annotation.Config

abstract class PowerRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = AdminSamples.now)
    private val status = ServerStatus("Test Jellyfin", "12.4.0", pendingRestart = true, shuttingDown = false)
    private val asked = mutableListOf<String>()
    private val actions = PowerActions(onBack = {}, onRefresh = {}, onAsk = { asked += it.name }, onConfirm = { asked += "confirm" }, onDismiss = {})

    private fun power(state: PowerUiState, typed: TextFieldState = TextFieldState()) = show { PowerScreen(state, actions, typed) }

    private fun button(label: Int) = rule.onNode(hasText(text(label)) and hasClickAction())
    private fun dialogButton(label: Int) = rule.onNode(hasText(text(label)) and hasClickAction() and hasAnyAncestor(isDialog()))

    @Test fun bothButtons() {
        power(PowerUiState(open, status))
        assertShown("Test Jellyfin")
        assertShown(text(R.string.admin_pending_restart_title))
        scrollTo(hasText(text(R.string.admin_shutdown)))
        for ((label, action) in listOf(R.string.admin_restart to PowerAction.RESTART, R.string.admin_shutdown to PowerAction.SHUT_DOWN)) {
            button(label).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
            assertEquals(action.name, asked.last())
        }
    }

    @Test fun buttonsAreOffOfflineBeforeTheServerIsKnownAndAfterARequest() {
        power(PowerUiState(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)), status))
        scrollTo(hasText(text(R.string.admin_shutdown)))
        button(R.string.admin_restart).assertIsNotEnabled()
        button(R.string.admin_shutdown).assertIsNotEnabled()
    }

    @Test fun noStatusYet() {
        power(PowerUiState(open, null))
        button(R.string.admin_restart).assertIsNotEnabled()
    }

    @Test fun afterARequestTheNoticeShowsAndButtonsStayOff() {
        power(PowerUiState(open.copy(notice = AdminNotice(R.string.admin_restart_requested)), status, requested = PowerAction.RESTART))
        assertShown(text(R.string.admin_restart_requested))
        scrollTo(hasText(text(R.string.admin_shutdown)))
        button(R.string.admin_restart).assertIsNotEnabled()
        button(R.string.admin_shutdown).assertIsNotEnabled()
    }

    @Test fun theShutDownNotice() {
        power(PowerUiState(open.copy(notice = AdminNotice(R.string.admin_shutdown_requested)), status, requested = PowerAction.SHUT_DOWN))
        assertShown(text(R.string.admin_shutdown_requested))
    }

    @Test fun theRestartDialogNeedsTheWord() {
        val typed = TextFieldState()
        power(PowerUiState(open, status, confirm = PowerAction.RESTART), typed)
        assertShown(text(R.string.admin_confirm_restart_title, "Test Jellyfin"))
        assertShown(text(R.string.admin_confirm_restart_body))
        assertShown(text(R.string.admin_type_to_confirm, text(R.string.admin_word_restart)))
        dialogButton(R.string.admin_restart).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Restart")
        dialogButton(R.string.admin_restart).assertIsEnabled().performClick()
        assertEquals("confirm", asked.last())
    }

    @Test fun theShutDownDialogNeedsItsOwnWord() {
        val typed = TextFieldState()
        power(PowerUiState(open, status, confirm = PowerAction.SHUT_DOWN), typed)
        assertShown(text(R.string.admin_confirm_shutdown_title, "Test Jellyfin"))
        assertShown(text(R.string.admin_confirm_shutdown_body))
        assertShown(text(R.string.admin_type_to_confirm, text(R.string.admin_word_shutdown)))
        rule.onNode(hasSetTextAction()).performTextInput("restart")
        dialogButton(R.string.admin_shutdown).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput(" shut down")
        dialogButton(R.string.admin_shutdown).assertIsNotEnabled()
    }
}

@Config(qualifiers = PHONE) class PowerPhoneRenderTest : PowerRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class PowerPhoneLargeTextRenderTest : PowerRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class PowerTabletRenderTest : PowerRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class PowerTabletLargeTextRenderTest : PowerRenderBase() { override val fontScale = 2f }
