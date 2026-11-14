package app.shoalwatch.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.net.fingerprintForDisplay
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.servers.AccountItem
import app.shoalwatch.ui.servers.Destructive
import app.shoalwatch.ui.servers.Confirm
import app.shoalwatch.ui.servers.EditPane
import app.shoalwatch.ui.servers.HeaderItem
import app.shoalwatch.ui.servers.PaneActions
import app.shoalwatch.ui.servers.ServerEditActions
import app.shoalwatch.ui.servers.ServerEditScreen
import app.shoalwatch.ui.servers.ServerEditUiState
import app.shoalwatch.ui.servers.ServerItem
import app.shoalwatch.ui.servers.ServersActions
import app.shoalwatch.ui.servers.ServersScreen
import app.shoalwatch.ui.servers.ServersUiState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import app.shoalwatch.ui.servers.ServersNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/** Renders the servers list and the connection settings on the JVM: every state composes and shows its key text. */
@RunWith(RobolectricTestRunner::class)
class ManagementRenderTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            ShoalwatchTheme(content = content)
        }
    }

    private fun shown(label: String) = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private val serversActions = ServersActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})
    private val pane = PaneActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private val editActions = ServerEditActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, pane)

    private val servers = ServersUiState(
        loading = false,
        servers = listOf(
            ServerItem("s1", "Den", "10.10.3", certificateChanged = false, accounts = listOf(AccountItem("u1", "ben", true), AccountItem("u2", "amy", false))),
            ServerItem("s2", "Cabin", "10.9.0", certificateChanged = true, accounts = emptyList()),
        ),
    )

    // --- servers ------------------------------------------------------------------------------------------------

    @Test fun serversAndAccountsAreListedWithTheActiveOneMarked() {
        show { ServersScreen(servers, serversActions) }
        listOf("Den", "Cabin", "ben", "amy", text(R.string.account_active), text(R.string.cert_badge), text(R.string.no_accounts)).forEach {
            assertTrue(it, shown(it))
        }
        assertTrue(shown(text(R.string.server_add_account)))
        assertTrue(shown(text(R.string.server_settings)))
        assertTrue(shown(text(R.string.server_remove)))
    }

    @Test fun signOutAsksForConfirmation() {
        show { ServersScreen(servers.copy(confirm = Confirm.SignOut("s1", "u2", "amy", "Den")), serversActions) }
        assertTrue(shown(text(R.string.confirm_signout_title, "amy")))
        assertTrue(shown(text(R.string.confirm_signout_body, "Den")))
    }

    @Test fun removingAServerListsWhatWillGo() {
        show { ServersScreen(servers.copy(confirm = Confirm.RemoveServer("s1", "Den", listOf("ben", "amy"))), serversActions) }
        assertTrue(shown(text(R.string.confirm_remove_title, "Den")))
        assertTrue(shown(text(R.string.confirm_remove_account, "ben")))
        assertTrue(shown(text(R.string.confirm_remove_account, "amy")))
        assertTrue(shown(text(R.string.confirm_remove_settings)))
        assertTrue("the dialog says sign-ins are revoked too", shown(text(R.string.confirm_remove_revoke)))
    }

    @Test fun aSignOutOrRemovalThatCouldNotReachTheServerIsSaidPlainly() {
        show { ServersScreen(servers.copy(notice = ServersNotice.SignedOutHereOnly), serversActions) }
        assertTrue(shown(text(R.string.notice_signed_out_title)))
        assertTrue(shown(text(R.string.notice_not_revoked_body)))
    }

    @Test fun aRemovalThatCouldNotReachTheServerIsSaidPlainly() {
        show { ServersScreen(servers.copy(notice = ServersNotice.RemovedHereOnly), serversActions) }
        assertTrue(shown(text(R.string.notice_removed_title)))
        assertTrue(shown(text(R.string.notice_not_revoked_body)))
    }

    @Test fun noServersOffersToAddOne() {
        show { ServersScreen(ServersUiState(loading = false), serversActions) }
        assertTrue(shown(text(R.string.servers_empty_title)))
        assertTrue(shown(text(R.string.servers_add)))
    }

    @Test fun serversSurviveTwiceTheFontSize() {
        show(fontScale = 2f) { ServersScreen(servers, serversActions) }
        assertTrue(shown("Den"))
    }

    // --- connection settings ------------------------------------------------------------------------------------

    private val secret = "proxy-token-9f8e7d"
    private val edit = ServerEditUiState(
        loaded = true, name = "Den",
        homeAddress = "http://192.168.1.10:8096", awayAddress = null,
        allowCleartext = true, pinFingerprint = fingerprintForDisplay(PIN), hasClientCertificate = true,
        headers = listOf(HeaderItem("X-Proxy", secret)),
        connection = ConnectionView(ConnectionView.Kind.HOME),
    )

    @Test fun settingsShowEverySection() {
        show { ServerEditScreen(edit, editActions) }
        listOf(
            R.string.edit_connection, R.string.edit_addresses, R.string.edit_security, R.string.edit_headers,
            R.string.edit_home, R.string.edit_away, R.string.edit_not_set, R.string.edit_http_toggle,
            R.string.edit_forget_cert, R.string.edit_client_cert_set, R.string.edit_client_cert_remove, R.string.edit_header_add,
        ).forEach { assertTrue(context.getString(it), shown(text(it))) }
        assertTrue(shown("http://192.168.1.10:8096"))
        assertTrue(shown(fingerprintForDisplay(PIN).split(':').take(4).joinToString(":")))
    }

    @Test fun headerValuesAreMaskedUntilRevealed() {
        show { ServerEditScreen(edit, editActions) }
        assertTrue(shown("X-Proxy"))
        assertTrue(shown(text(R.string.header_masked)))
        assertFalse("value must not be on screen", shown(secret))
    }

    @Test fun aChangedCertificateShowsTheProminentCard() {
        show { ServerEditScreen(edit.copy(certificateChanged = true, certificateNeedsReview = true), editActions) }
        assertTrue(shown(text(R.string.cert_changed_title)))
        assertTrue(shown(text(R.string.cert_review)))
    }

    @Test fun reviewingShowsTheFingerprintAndAnExplicitTrustButton() {
        val summary = certificate().toSummary()
        show { ServerEditScreen(edit.copy(certificateChanged = true, pane = EditPane.CertificateReview(false, summary, null)), editActions) }
        assertTrue(shown(text(R.string.cert_trust_new)))
        assertTrue(shown(text(R.string.action_cancel)))
        assertTrue(shown("CN=den.example"))
    }

    @Test fun turningHttpOnShowsTheWarningPage() {
        show { ServerEditScreen(edit.copy(allowCleartext = false, pane = EditPane.HttpWarning), editActions) }
        assertTrue(shown(text(R.string.http_body)))
        assertTrue(shown(text(R.string.http_use)))
    }

    @Test fun editingAnAddressShowsTheFieldAndItsChecks() {
        show { ServerEditScreen(edit.copy(pane = EditPane.Address(app.shoalwatch.ui.servers.AddressSlot.Away, "")), editActions) }
        assertTrue(shown(text(R.string.edit_address_save)))
        assertTrue(shown(text(R.string.edit_address_hint)))
    }

    @Test fun aMismatchedAddressShowsItsErrorBesideTheField() {
        val message = AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage
        show {
            ServerEditScreen(
                edit.copy(pane = EditPane.Address(app.shoalwatch.ui.servers.AddressSlot.Away, "x"), addressCheck = Check.Failed(message)),
                editActions,
            )
        }
        assertTrue(shown(message))
    }

    @Test fun theHeaderEditorShowsTheDataLayersMessageButNeverTheValue() {
        val error = "Custom header 'X-Proxy' has an invalid value."
        show { ServerEditScreen(edit.copy(pane = EditPane.Header(null, "X-Proxy", secret, error)), editActions) }
        assertTrue(shown(error))
        assertTrue(shown(text(R.string.header_add_title)))
        // The value field is a password-style field, so it is drawn masked and screen readers do not read it out.
        val masked = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes()
        assertTrue("value field must be masked", masked.isNotEmpty())
    }

    @Test fun forgettingTheCertificateAsksFirst() {
        show { ServerEditScreen(edit.copy(pane = EditPane.ConfirmDestructive(Destructive.ForgetCertificate)), editActions) }
        assertTrue(shown(text(R.string.confirm_forget_cert_title)))
        assertTrue(shown(text(R.string.confirm_forget_cert_body)))
    }

    @Test fun deletingAHeaderAndRemovingTheClientCertificateAskFirst() {
        show { ServerEditScreen(edit.copy(pane = EditPane.ConfirmDestructive(Destructive.RemoveHeader("X-Proxy"))), editActions) }
        assertTrue(shown(text(R.string.confirm_header_title, "X-Proxy")))
        assertFalse(shown(secret))
    }

    @Test fun theMaskedValueIsAnnouncedAsHidden() {
        show { ServerEditScreen(edit, editActions) }
        assertTrue(rule.onAllNodesWithContentDescription(text(R.string.header_hidden)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test @Config(qualifiers = "w320dp-h480dp")
    fun headerActionsStayReachableOnASmallScreenAtTwiceTheFontSize() {
        show(fontScale = 2f) { ServerEditScreen(edit, editActions) }
        rule.onNode(hasText(text(R.string.edit_header_add)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription(text(R.string.header_remove_cd, "X-Proxy"))).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsSurviveTwiceTheFontSize() {
        show(fontScale = 2f) { ServerEditScreen(edit, editActions) }
        assertTrue(shown(text(R.string.edit_connection)))
    }
}
