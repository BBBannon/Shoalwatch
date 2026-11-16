package app.shoalwatch.ui

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import android.view.MotionEvent
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.shoalwatch.ui.servers.EditPane
import app.shoalwatch.ui.servers.HeaderDialog
import app.shoalwatch.ui.servers.PaneActions
import org.junit.Assert.assertEquals
import app.shoalwatch.ui.components.CertificateScreen
import app.shoalwatch.ui.components.ConfirmDialog
import app.shoalwatch.ui.components.HttpWarningScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tapjacking: the choices that weaken security ("Use anyway", "Trust this certificate") and the destructive
 * confirmations ignore touches while another app's window covers them (`filterTouchesWhenObscured` on the
 * window that hosts them, so Android drops events flagged FLAG_WINDOW_IS_OBSCURED before Compose sees them).
 */
@RunWith(RobolectricTestRunner::class)
class ObscuredTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var root: View? = null

    /** Records the root view of the window it is composed in. */
    @Composable private fun Probe() {
        root = LocalView.current.rootView
    }

    private val certificate = certificate().toSummary()

    @Test fun theHttpWarningIgnoresObscuredTouches() {
        rule.setContent { HttpWarningScreen(onCancel = {}, onUseAnyway = {}); Probe() }
        rule.waitForIdle()
        assertTrue(root!!.filterTouchesWhenObscured)
    }

    @Test fun theCertificateTrustScreenIgnoresObscuredTouches() {
        rule.setContent { CertificateScreen(certificate, loading = false, error = null, changed = true, onTrust = {}, onCancel = {}); Probe() }
        rule.waitForIdle()
        assertTrue(root!!.filterTouchesWhenObscured)
    }

    @Test fun aDestructiveConfirmationIgnoresObscuredTouchesInItsOwnWindow() {
        rule.setContent {
            ConfirmDialog(title = "Remove?", confirmLabel = "Remove", onConfirm = {}, onDismiss = {}) { Text("body"); Probe() }
        }
        rule.waitForIdle()
        assertTrue("the dialog's own window", root!!.filterTouchesWhenObscured)
    }

    @Test fun theProtectionIsLiftedWhenTheScreenLeaves() {
        val showing = mutableStateOf(true)
        rule.setContent {
            Probe()
            if (showing.value) HttpWarningScreen(onCancel = {}, onUseAnyway = {})
        }
        rule.waitForIdle()
        assertTrue(root!!.filterTouchesWhenObscured)
        showing.value = false
        rule.waitForIdle()
        assertFalse(root!!.filterTouchesWhenObscured)
    }

    // ---- the user is told why a button does nothing (review F11)

    private val hint = "Another app is drawing over the screen, so this button is switched off. Close screen filters or bubbles and try again."

    /** Counts what reaches the window's real callback, so a dropped touch is visible as "never arrived". */
    private class Counting(private val original: Window.Callback) : Window.Callback by original {
        var touches = 0
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { touches++; return original.dispatchTouchEvent(event) }
    }

    private fun touch(action: Int, flags: Int, downTime: Long = 0L): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 5f; y = 5f })
        return MotionEvent.obtain(downTime, downTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    private val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED
    private val partlyObscured = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

    @Test fun anObscuredTouchOnAProtectedScreenIsDroppedAndTheUserIsToldWhy() {
        val counting = Counting(rule.activity.window.callback)
        rule.activity.window.callback = counting
        rule.setContent { HttpWarningScreen(onCancel = {}, onUseAnyway = {}); Probe() }
        rule.waitForIdle()
        rule.onNodeWithText(hint).assertDoesNotExist()

        for (flag in listOf(obscured, partlyObscured)) {
            rule.runOnUiThread { assertTrue(rule.activity.window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, flag))) }
            rule.waitForIdle()
            assertEquals("a touch with flag $flag never reached the screen", 0, counting.touches)
            rule.onNodeWithText(hint).assertExists()
            rule.runOnUiThread { rule.activity.window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 0)) }
            rule.waitForIdle()
            rule.onNodeWithText(hint).assertDoesNotExist() // a clean touch clears it
            rule.runOnUiThread { rule.activity.window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, 0)) }
            counting.touches = 0
        }
    }

    @Test fun anUnobscuredTouchReachesTheScreenAndShowsNoHint() {
        val counting = Counting(rule.activity.window.callback)
        rule.activity.window.callback = counting
        rule.setContent { HttpWarningScreen(onCancel = {}, onUseAnyway = {}) }
        rule.waitForIdle()
        rule.runOnUiThread {
            rule.activity.window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 0))
            rule.activity.window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_UP, 0))
        }
        assertEquals(2, counting.touches)
        rule.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test fun theHeaderEditorDialogDropsObscuredTouchesAndExplainsWhy() {
        rule.setContent {
            HeaderDialog(EditPane.Header(null, "X-Proxy", "secret", null), PaneActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}))
        }
        rule.waitForIdle()
        // The dialog is a window of its own, so the guard has to be on that window.
        val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        assertTrue(window.decorView.filterTouchesWhenObscured)
        rule.onNodeWithText(hint).assertDoesNotExist()
        rule.runOnUiThread { assertTrue("dropped", window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, obscured))) }
        rule.waitForIdle()
        rule.onNodeWithText(hint).assertExists()
        rule.runOnUiThread { window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, 0)) }
        rule.waitForIdle()
        rule.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test fun aConfirmationDialogDropsObscuredTouchesAndExplainsWhy() {
        rule.setContent { ConfirmDialog(title = "Remove?", confirmLabel = "Remove", onConfirm = {}, onDismiss = {}) { Text("body") } }
        rule.waitForIdle()
        val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        rule.runOnUiThread { assertTrue(window.callback.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN, obscured))) }
        rule.waitForIdle()
        rule.onNodeWithText(hint).assertExists()
    }
}
