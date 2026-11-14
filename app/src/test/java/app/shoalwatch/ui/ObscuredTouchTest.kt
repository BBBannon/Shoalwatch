package app.shoalwatch.ui

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val rule = createComposeRule()

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
}
