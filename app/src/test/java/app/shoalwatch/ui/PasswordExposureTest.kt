package app.shoalwatch.ui

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.ui.onboarding.PasswordPanel
import app.shoalwatch.ui.onboarding.SignInActions
import app.shoalwatch.ui.onboarding.SignInMode
import app.shoalwatch.ui.onboarding.SignInUiState
import app.shoalwatch.ui.servers.EditPane
import app.shoalwatch.ui.servers.HeaderDialog
import app.shoalwatch.ui.servers.PaneActions
import app.shoalwatch.ui.theme.ShoalwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Compose text fields draw a masked value but, by default, keep the raw text in the semantics node as
 * `EditableText` (and `InputText`). Accessibility services read the semantics tree, so while a value is hidden
 * its `EditableText` is bullets (on the same node that carries the Password flag); once the user chooses
 * "Show" it is the real text.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordExposureTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secret = "hunter2-secret"
    private val bullets = "•".repeat(secret.length)

    private fun editableTexts(): List<Pair<SemanticsNode, String>> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes()
            .map { it to it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() }

    private fun showPassword() = rule.setContent {
        ShoalwatchTheme {
            PasswordPanel(SignInUiState(mode = SignInMode.Password), TextFieldState(secret), SignInActions({}, {}, {}, {}, {}, {}))
        }
    }

    @Test fun whileHiddenThePasswordFieldExposesOnlyBulletsOnTheNodeThatIsFlaggedAsAPassword() {
        showPassword()
        val flagged = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes()
        assertEquals("one node is flagged as a password", 1, flagged.size)
        val config = flagged.single().config
        assertEquals("the flag and the masked text are on the same node", bullets, config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertFalse(config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text })
        assertFalse(config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { secret in it })
        assertTrue(editableTexts().none { secret in it.second })
    }

    @Test fun afterShowPasswordTheAccessibilityTextIsTheRealPassword() {
        showPassword()
        rule.onNode(hasContentDescription(context.getString(R.string.password_show_cd))).performClick()
        assertTrue("revealed on request", editableTexts().any { it.second == secret })
        rule.onNode(hasContentDescription(context.getString(R.string.password_hide_cd))).performClick()
        assertTrue("masked again", editableTexts().none { secret in it.second })
    }

    @Test fun theHeaderValueIsMaskedWhileHiddenAndRealWhenShown() {
        val actions = PaneActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        rule.setContent {
            ShoalwatchTheme { HeaderDialog(EditPane.Header(null, "X-A", secret, null), actions) }
        }
        assertTrue("masked", editableTexts().any { it.second == bullets })
        assertTrue(editableTexts().none { secret in it.second })
        rule.onNode(hasContentDescription(context.getString(R.string.header_reveal_cd, "X-A"))).performClick()
        assertTrue("revealed on request", editableTexts().any { it.second == secret })
        rule.onNode(hasContentDescription(context.getString(R.string.header_hide_cd, "X-A"))).performClick()
        assertTrue(editableTexts().none { secret in it.second })
    }
}
