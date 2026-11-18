package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** As `PasswordExposureTest`: accessibility sees bullets on the password-flagged node, never the PIN or the password. */
@RunWith(RobolectricTestRunner::class)
class PinFieldExposureTest {
    @get:Rule val rule = createComposeRule()

    private fun editableTexts(): List<Pair<SemanticsNode, String>> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes()
            .map { it to it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() }

    private fun assertMasked(secret: String) {
        val flagged = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes()
        assertEquals("one node is flagged as a password", 1, flagged.size)
        val config = flagged.single().config
        assertEquals("•".repeat(secret.length), config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertTrue(editableTexts().none { secret in it.second })
        val all = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        assertFalse(all.any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text } })
        assertFalse(all.any { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { secret in it } })
    }

    @Test fun thePinFieldExposesOnlyBulletsOfTheRightLength() {
        rule.setContent { ShoalwatchTheme { PinField(TextFieldState("482615"), "PIN", {}) } }
        assertMasked("482615")
    }

    @Test fun thePinFieldTakesDigitsOnlyAndAtMostTwelve() {
        val state = TextFieldState()
        rule.setContent { ShoalwatchTheme { PinField(state, "PIN", {}) } }
        rule.onNode(hasSetTextAction()).performTextInput("12a4")
        assertEquals("", state.text.toString())
        rule.onNode(hasSetTextAction()).performTextInput("123456789012")
        rule.onNode(hasSetTextAction()).performTextInput("3")
        assertEquals("123456789012", state.text.toString())
    }

    @Test fun theForgotPanelsPasswordIsMaskedTheSameWay() {
        val state = LockUiState(lock = LockState.Profile(Session("s", "u"), "ann"), forgot = true, lockedServerName = "Den")
        rule.setContent { ShoalwatchTheme { ForgotPinPanel(state, TextFieldState("hunter2-secret"), {}, {}) } }
        assertMasked("hunter2-secret")
    }
}
