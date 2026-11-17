package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.home.HomeActions
import app.shoalwatch.ui.home.HomePhase
import app.shoalwatch.ui.home.HomeScreen
import app.shoalwatch.ui.home.HomeUiState
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.servers.AccountItem
import app.shoalwatch.ui.servers.ServerItem
import app.shoalwatch.ui.servers.ServersActions
import app.shoalwatch.ui.servers.ServersScreen
import app.shoalwatch.ui.servers.ServersUiState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/** The grown-up dialog and the "Protected" marks (Milestone 7 Task 6), on a phone and a tablet at font scale 1.0 and 2.0. */
abstract class GrownUpDialogRenderTest : RenderBase() {
    private val anna = AccountSummary("s1", "u1", "Anna")
    private val ben = AccountSummary("s1", "u2", "Ben")
    private val cat = AccountSummary("s2", "u3", "Cat")

    private fun dialog(ask: GrownUpAsk) = show { GrownUpDialog(ask, TextFieldState(), {}, {}, {}) }

    private fun selected(name: String): Boolean? = rule.onAllNodes(SemanticsMatcher("row $name") { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name } || n.children.any { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name } }
    }).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Selected) }

    private fun assertTargets() {
        val min = 48.dp.value * context.resources.displayMetrics.density - 0.5f
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { assertTrue("under 48dp: ${it.config}", it.size.height >= min) }
    }

    @Test fun oneGrownUpIsChosenAlreadyWithNoChoiceShown() {
        dialog(GrownUpAsk(listOf(anna), anna))
        assertShown(text(R.string.grownup_title))
        assertShown(text(R.string.grownup_body))
        assertShown(text(R.string.pin_label))
        assertShown(text(R.string.grownup_confirm))
        assertShown(text(R.string.action_cancel))
        assertNotShown("Anna")
        assertTargets()
    }

    @Test fun threeGrownUpsAreRadioRowsThatSayWhichIsSelected() {
        dialog(GrownUpAsk(listOf(anna, ben, cat), ben))
        listOf("Anna", "Ben", "Cat").forEach { assertShown(it) }
        assertEquals(true, selected("Ben"))
        assertEquals(false, selected("Anna"))
        assertTargets()
    }

    @Test fun everyAnswerLine() {
        val strings = Strings(context.resources)
        val answers = listOf(PinCheck.Wrong(2), PinCheck.Wrong(0), PinCheck.Wait(45), PinCheck.Unreadable)
        answers.forEachIndexed { i, a ->
            if (i == 0) dialog(GrownUpAsk(listOf(anna), anna, answer = a))
        }
        assertShown(pinAnswerText(strings, answers[0], false)!!)
        answers.drop(1).forEach { assertTrue(pinAnswerText(strings, it, false)!!.isNotBlank()) }
    }

    @Test fun aTryThatCouldNotBeSavedIsSaidPlainly() {
        dialog(GrownUpAsk(listOf(anna), anna, failed = true))
        assertShown(text(R.string.pin_not_saved))
    }

    @Test fun signingOutAnotherProtectedProfileAsksForItsOwnPin() {
        dialog(GrownUpAsk(listOf(anna), anna, signOut = true))
        assertShown(text(R.string.signout_pin_title, "Anna"))
        assertShown(text(R.string.signout_pin_body, "Anna"))
        assertShown(text(R.string.sign_out))
    }

    @Test fun theDialogsArePinDialogsInASecureWindow() {
        dialog(GrownUpAsk(listOf(anna), anna))
        assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
        // Every dialog in ui/lock is a SecureDialog window (FLAG_SECURE): a source check over the package.
        File("src/main/java/app/shoalwatch/ui/lock").listFiles()!!.filter { it.readText().contains("AlertDialog(") }.forEach {
            val text = it.readText()
            assertEquals("${it.name}: every AlertDialog is secure", Regex("""AlertDialog\(""").findAll(text).count(), Regex("""properties = SecureDialog""").findAll(text).count())
            assertTrue("${it.name}: behind the obscured-touch filter", text.contains("FilterObscuredTouches()"))
        }
    }

    @Test fun serversRowsMarkProtectedProfilesAndSaySo() {
        val state = ServersUiState(
            loading = false,
            servers = listOf(ServerItem("s1", "Den", "10.10.3", false, listOf(AccountItem("u1", "ben", true), AccountItem("u2", "amy", false, isProtected = true)))),
        )
        show { ServersScreen(state, ServersActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})) }
        assertShown(text(R.string.lock_protected))
        assertDescribed(text(R.string.account_use_cd, text(R.string.profile_protected_cd, "amy")))
    }

    @Test fun homesMenuMarksProtectedProfilesAndSaysSo() {
        val state = HomeUiState(
            phase = HomePhase.READY, session = BEN, serverId = SERVER_ID, serverName = "Den", userName = "ben",
            accounts = listOf(AccountSummary(SERVER_ID, BEN.userId, "ben"), AccountSummary(SERVER_ID, AMY.userId, "amy")),
            protectedUsers = setOf(AMY.userId),
        )
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })
        show { HomeScreen(state, actions, emptyFlow(), SnackbarHostState()) }
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.lock_protected))
        assertDescribed(text(R.string.profile_protected_cd, "amy"))
    }
}

@Config(qualifiers = PHONE) class GrownUpDialogPhoneRenderTest : GrownUpDialogRenderTest() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class GrownUpDialogPhoneLargeTextRenderTest : GrownUpDialogRenderTest() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class GrownUpDialogTabletRenderTest : GrownUpDialogRenderTest() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class GrownUpDialogTabletLargeTextRenderTest : GrownUpDialogRenderTest() { override val fontScale = 2f }
