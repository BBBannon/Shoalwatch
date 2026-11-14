package app.offlinefin.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import app.offlinefin.ui.onboarding.PasswordPanel
import app.offlinefin.ui.onboarding.SignInActions
import app.offlinefin.ui.onboarding.SignInMode
import app.offlinefin.ui.onboarding.SignInUiState
import app.offlinefin.ui.theme.OfflineFinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Compose secure text field draws a masked value but, by default, keeps the raw text in the semantics
 * node as `EditableText` (and in `InputText`). Accessibility services read the semantics tree, so the sign-in
 * screen replaces `EditableText` with bullets. This pins that, and that the node stays flagged as a password.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordExposureTest {
    @get:Rule val rule = createComposeRule()

    private val secret = "hunter2-secret"

    @Test fun noAccessibilityTextOfThePasswordFieldContainsThePassword() {
        rule.setContent {
            OfflineFinTheme() {
                PasswordPanel(SignInUiState(mode = SignInMode.Password), TextFieldState(secret), SignInActions({}, {}, {}, {}, {}, {}))
            }
        }
        val passwordNodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes()
        assertEquals("the field is flagged as a password", 1, passwordNodes.size)
        val config = passwordNodes.single().config
        assertEquals(secret.length, config.getOrNull(SemanticsProperties.EditableText)?.text?.length)
        assertFalse(secret in config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty())
        assertFalse(config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text })
        assertFalse(config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { secret in it })
        assertTrue(config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().all { it == '•' })
    }
}
