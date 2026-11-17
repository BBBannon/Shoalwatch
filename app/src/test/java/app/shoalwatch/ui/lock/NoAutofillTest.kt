package app.shoalwatch.ui.lock

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tasks 5-6 review I1: while the lock screen, the "Forgot PIN" panel or any PIN field shows, its window is kept out of autofill (a
 * password manager must not fill the server password or learn a PIN), counted so nested holders restore correctly; PIN and
 * password fields ask the keyboard not to learn what is typed. Whether a real password manager then offers nothing is a device check.
 */
@RunWith(RobolectricTestRunner::class)
class NoAutofillTest {
    @get:Rule val rule = createComposeRule()

    private val excluded = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    private val actions = LockActions({}, {}, {}, {}, {}, {}, {}, {})
    private val profile = LockUiState(lock = LockState.Profile(Session("s", "u"), "Ben"), lockedServerName = "Den")

    @Test fun theLockScreenWindowIsOutOfAutofillAndRestoredAfter() {
        var root: View? = null
        var showing by mutableStateOf(true)
        rule.setContent {
            root = LocalView.current.rootView
            ShoalwatchTheme { if (showing) LockScreen(profile, TextFieldState(), TextFieldState(), TextFieldState(), actions) }
        }
        rule.waitForIdle()
        assertEquals(excluded, root!!.importantForAutofill)
        showing = false
        rule.waitForIdle()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, root!!.importantForAutofill)
    }

    @Test fun theForgotPanelWindowIsOutOfAutofill() {
        var root: View? = null
        rule.setContent {
            root = LocalView.current.rootView
            ShoalwatchTheme { ForgotPinPanel(profile.copy(forgot = true), TextFieldState(), {}, {}) }
        }
        rule.waitForIdle()
        assertEquals(excluded, root!!.importantForAutofill)
    }

    @Test fun aPinFieldInADialogKeepsThatDialogsWindowOutOfAutofill() {
        var dialogRoot: View? = null
        rule.setContent {
            ShoalwatchTheme {
                Dialog(onDismissRequest = {}) {
                    dialogRoot = LocalView.current.rootView
                    PinField(TextFieldState(), "PIN", {})
                }
            }
        }
        rule.waitForIdle()
        assertEquals(excluded, dialogRoot!!.importantForAutofill)
    }

    @Test fun nestedHoldersRestoreOnlyWhenTheLastLeaves() {
        var root: View? = null
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        rule.setContent {
            root = LocalView.current.rootView
            ShoalwatchTheme {
                Column {
                    if (first) PinField(TextFieldState(), "PIN", {})
                    if (second) PinField(TextFieldState(), "PIN 2", {})
                }
            }
        }
        rule.waitForIdle()
        first = false
        rule.waitForIdle()
        assertEquals(excluded, root!!.importantForAutofill)
        second = false
        rule.waitForIdle()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, root!!.importantForAutofill)
    }

    private fun imeOptionsOfFocusedField(content: @androidx.compose.runtime.Composable () -> Unit): Int {
        var view: View? = null
        rule.setContent {
            view = LocalView.current
            ShoalwatchTheme { content() }
        }
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        var connection: Any? = null
        rule.runOnIdle { connection = view!!.onCreateInputConnection(info) }
        assertNotNull("a text input session started", connection)
        return info.imeOptions
    }

    @Test fun thePinFieldAsksTheKeyboardNotToLearn() {
        val options = imeOptionsOfFocusedField { PinField(TextFieldState(), "PIN", {}) }
        assertTrue("no personalised learning", options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test fun theForgotPasswordFieldAsksTheKeyboardNotToLearn() {
        val options = imeOptionsOfFocusedField { ForgotPinPanel(profile.copy(forgot = true), TextFieldState(), {}, {}) }
        assertTrue("no personalised learning", options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}
