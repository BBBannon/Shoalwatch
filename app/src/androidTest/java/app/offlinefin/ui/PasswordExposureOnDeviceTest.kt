package app.offlinefin.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.offlinefin.ui.onboarding.SignInActions
import app.offlinefin.ui.onboarding.SignInMode
import app.offlinefin.ui.onboarding.SignInScreen
import app.offlinefin.ui.onboarding.SignInUiState
import app.offlinefin.ui.theme.OfflineFinTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Types a password into the sign-in screen and checks what accessibility services can read: neither the
 * Compose semantics tree nor the platform `AccessibilityNodeInfo` tree (what `uiautomator dump` shows) may
 * contain the password while the field is masked, and the node must be flagged as a password field.
 */
@RunWith(AndroidJUnit4::class)
class PasswordExposureOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val secret = "hunter2-secret"

    @Test fun thePasswordIsNotReadableByAccessibilityServices() {
        val password = TextFieldState()
        rule.setContent {
            OfflineFinTheme {
                SignInScreen(SignInUiState(serverName = "Den", mode = SignInMode.Password), password, SignInActions({}, {}, {}, {}, {}, {}))
            }
        }
        val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        rule.onNode(isPassword).performTextInput(secret)
        rule.waitForIdle()

        // Compose semantics tree.
        val config = rule.onNode(isPassword).fetchSemanticsNode().config
        assertFalse(secret in config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty())
        assertFalse(config.getOrNull(SemanticsProperties.Text).orEmpty().any { secret in it.text })

        // Platform accessibility tree, as uiautomator sees it.
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            assertFalse("text leaks the password", secret in node.text?.toString().orEmpty())
            assertFalse("description leaks the password", secret in node.contentDescription?.toString().orEmpty())
            if (node.isPassword) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        assertTrue("a node is flagged as a password field", found.isNotEmpty())
    }
}
