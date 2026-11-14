package app.shoalwatch.ui

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.PrimaryButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.net.fingerprintForDisplay
import app.shoalwatch.ui.home.HomePlaceholderScreen
import app.shoalwatch.ui.home.HomeUiState
import app.shoalwatch.ui.onboarding.AddServerActions
import app.shoalwatch.ui.onboarding.AddServerScreen
import app.shoalwatch.ui.onboarding.AddServerStage
import app.shoalwatch.ui.onboarding.AddServerUiState
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.onboarding.FoundServer
import app.shoalwatch.ui.onboarding.QuickConnectUi
import app.shoalwatch.ui.onboarding.SignInActions
import app.shoalwatch.ui.onboarding.SignInMode
import app.shoalwatch.ui.onboarding.SignInScreen
import app.shoalwatch.ui.onboarding.SignInUiState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * Renders the onboarding and home screens on the JVM (Robolectric) to prove each state composes, shows its
 * key text and survives a 200% font scale. It cannot judge looks: that is for a device.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenRenderTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            ShoalwatchTheme(content = content)
        }
    }

    private fun shown(label: String) = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private val noAddActions = AddServerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private val noSignInActions = SignInActions({}, {}, {}, {}, {}, {})

    // --- Add server ---------------------------------------------------------------------------------------------

    @Test fun firstRunShowsWelcomeAndTheAddressField() {
        show { AddServerScreen(AddServerUiState(), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.add_title_first)))
        assertTrue(shown(text(R.string.add_address_label)))
        assertTrue(shown(text(R.string.action_continue)))
    }

    @Test fun anErrorAppearsNextToTheField() {
        val message = AppError.of(AppError.Kind.SERVER_NOT_FOUND).userMessage
        show { AddServerScreen(AddServerUiState(address = "x", check = Check.Failed(message, offerHttp = true)), noAddActions, onLeave = {}) }
        assertTrue(shown(message))
        assertTrue(shown(text(R.string.add_try_http)))
    }

    @Test fun theHttpWarningOffersCancelAndUseAnyway() {
        show { AddServerScreen(AddServerUiState(check = Check.NeedsHttpConsent), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.http_body)))
        assertTrue(shown(text(R.string.http_use)))
        assertTrue(shown(text(R.string.action_cancel)))
    }

    @Test fun theCertificateStepShowsTheFingerprintInGroups() {
        val summary = certificate().toSummary()
        show { AddServerScreen(AddServerUiState(check = Check.NeedsCertificateTrust(summary)), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.cert_body)))
        val firstLine = certificate().fingerprintForDisplay().split(':').take(4).joinToString(":")
        assertTrue("fingerprint line $firstLine", shown(firstLine))
        assertTrue(shown(text(R.string.cert_trust)))
        assertTrue(shown("CN=den.example"))
    }

    @Test fun anUnsupportedVersionShowsTheBannerAndContinueAnyway() {
        val found = FoundServer("srv-1", "Den", "10.8.0", ServerInfo.Compatibility.TOO_OLD, firstIsHome = true)
        show { AddServerScreen(AddServerUiState(stage = AddServerStage.Found, found = found), noAddActions, onLeave = null) }
        assertTrue(shown("Den"))
        assertTrue(shown(text(R.string.version_old, "10.8.0")))
        assertTrue(shown(text(R.string.version_continue)))
    }

    @Test fun theSecondAddressStepExplainsHomeAndAway() {
        val found = FoundServer("srv-1", "Den", "10.10.3", ServerInfo.Compatibility.SUPPORTED, firstIsHome = true)
        show { AddServerScreen(AddServerUiState(stage = AddServerStage.SecondAddress, found = found), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.second_home_title)))
        assertTrue(shown(text(R.string.second_away_title)))
        assertTrue(shown(text(R.string.second_for_away)))
        assertTrue(shown(text(R.string.action_skip)))
    }

    @Test @Config(qualifiers = "w320dp-h480dp")
    fun onASmallScreenAtTwiceTheFontSizeTheContinueButtonIsReachableByScrolling() {
        show(fontScale = 2f) { AddServerScreen(AddServerUiState(address = "den.example"), noAddActions, onLeave = null) }
        rule.onNode(hasText(text(R.string.action_continue)) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h480dp")
    fun theSecondAddressActionsAreReachableOnASmallScreenAtTwiceTheFontSize() {
        val found = FoundServer("srv-1", "Den", "10.10.3", ServerInfo.Compatibility.SUPPORTED, firstIsHome = true)
        show(fontScale = 2f) { AddServerScreen(AddServerUiState(stage = AddServerStage.SecondAddress, found = found, secondAddress = "x"), noAddActions, onLeave = null) }
        rule.onNode(hasText(text(R.string.second_add)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(text(R.string.action_skip)) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test fun anAddressCheckInProgressShowsOnTheButtonWithACancelNotAFullScreenSpinner() {
        show { AddServerScreen(AddServerUiState(address = "den.example", check = Check.Checking), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.add_address_label)))
        assertTrue(shown(text(R.string.action_cancel)))
        assertTrue(shown(text(R.string.add_address_example)))
    }

    @Test fun anUnsupportedVersionUsesTheWarningTitleNotAGenericOne() {
        val found = FoundServer("srv-1", "Den", "10.8.0", ServerInfo.Compatibility.TOO_OLD, firstIsHome = true, alreadySaved = true)
        show { AddServerScreen(AddServerUiState(stage = AddServerStage.Found, found = found), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.version_warning_title)))
        assertTrue(shown(text(R.string.found_already_saved)))
        assertFalse("the notes would contradict the settings staying as they are", shown(text(R.string.found_home_note)))
    }

    @Test fun aNewServerStillShowsWhereItWillBeSaved() {
        val found = FoundServer("srv-1", "Den", "10.10.3", ServerInfo.Compatibility.SUPPORTED, firstIsHome = true)
        show { AddServerScreen(AddServerUiState(stage = AddServerStage.Found, found = found), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.found_home_note)))
    }

    @Composable
    private fun TallScreen() = AppScreen(title = "t", onBack = null, bottomBar = { PrimaryButton("Act", {}) }) { Spacer(Modifier.height(2000.dp)) }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun withTheKeyboardOpenOnAnOrdinaryPhoneTheActionStaysPinnedAndVisible() {
        // 891dp window minus a 300dp keyboard leaves 591dp of visible height.
        show { Box(Modifier.height(591.dp)) { TallScreen() } }
        rule.onNode(hasText("Act") and hasClickAction()).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h480dp")
    fun onASmallScreenAtTwiceTheFontSizeTheActionIsInTheScrollContentAndReachable() {
        show(fontScale = 2f) { TallScreen() }
        rule.onNode(hasText("Act") and hasClickAction()).assertIsNotDisplayed()
        rule.onNode(hasText("Act") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test fun theHttpWarningSaysItAppliesToThisServerOnly() {
        show { AddServerScreen(AddServerUiState(check = Check.NeedsHttpConsent), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.http_scope)))
    }

    @Test fun theHttpWarningNamesTheAddressRoleAndTheSecurityCheckTitleIsShortened() {
        show { AddServerScreen(AddServerUiState(check = Check.NeedsHttpConsent, addressRole = app.shoalwatch.ui.AddressRole.Away), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.http_for_away)))
        assertTrue(shown(text(R.string.security_check_title)))
    }

    @Test fun addServerSurvivesTwiceTheFontSize() {
        show(fontScale = 2f) { AddServerScreen(AddServerUiState(), noAddActions, onLeave = null) }
        assertTrue(shown(text(R.string.add_title_first)))
    }

    // --- Sign in ------------------------------------------------------------------------------------------------

    @Test fun anUnencryptedSignInShowsAPersistentNoticeInBothModes() {
        show { SignInScreen(SignInUiState(serverName = "Den", unencrypted = true), TextFieldState(), noSignInActions) }
        assertTrue(shown(text(R.string.signin_unencrypted_title)))
        assertTrue(shown(text(R.string.signin_unencrypted_body)))
    }

    @Test fun anUnencryptedPasswordSignInShowsTheNoticeToo() {
        show { SignInScreen(SignInUiState(serverName = "Den", mode = SignInMode.Password, unencrypted = true), TextFieldState(), noSignInActions) }
        assertTrue(shown(text(R.string.signin_unencrypted_title)))
    }

    @Test fun anEncryptedSignInShowsNoNotice() {
        show { SignInScreen(SignInUiState(serverName = "Den"), TextFieldState(), noSignInActions) }
        assertFalse(shown(text(R.string.signin_unencrypted_title)))
    }

    @Test fun quickConnectShowsTheGroupedCodeAndTheInstruction() {
        val state = SignInUiState(serverName = "Den", quickConnect = QuickConnectUi.Code("ABC123", 272))
        show { SignInScreen(state, TextFieldState(), noSignInActions) }
        assertTrue(shown("ABC 123"))
        assertTrue(shown("1. " + text(R.string.qc_step_1)))
        assertTrue(shown("3. " + text(R.string.qc_step_3)))
        assertTrue(shown(text(R.string.qc_expires, "4:32")))
        assertTrue(shown(text(R.string.qc_use_password)))
        assertEquals(1, rule.onAllNodesWithContentDescription(text(R.string.qc_code_cd, "A B C 1 2 3")).fetchSemanticsNodes().size)
    }

    @Test fun thePasswordPanelHasBothFieldsAndAShowButton() {
        val state = SignInUiState(serverName = "Den", mode = SignInMode.Password)
        show { SignInScreen(state, TextFieldState(), noSignInActions) }
        assertTrue(shown(text(R.string.username_label)))
        assertTrue(shown(text(R.string.password_label)))
        assertTrue(shown(text(R.string.action_show)))
        assertTrue(shown(text(R.string.signin_action)))
        assertTrue(shown(text(R.string.qc_use_qc)))
    }

    @Test fun aPasswordErrorIsShown() {
        val message = AppError.of(AppError.Kind.UNAUTHORISED).userMessage
        show { SignInScreen(SignInUiState(serverName = "Den", mode = SignInMode.Password, error = message), TextFieldState(), noSignInActions) }
        assertTrue(shown(message))
    }

    @Test fun signInSurvivesTwiceTheFontSize() {
        val state = SignInUiState(serverName = "Den", quickConnect = QuickConnectUi.Code("ABC123", 300))
        show(fontScale = 2f) { SignInScreen(state, TextFieldState(), noSignInActions) }
        assertTrue(shown(text(R.string.qc_use_password)))
    }

    // --- Home ---------------------------------------------------------------------------------------------------

    private fun home(connection: Connection, name: String = "ben") =
        HomeUiState(loading = false, serverId = "srv-1", serverName = "Den", userName = name, connection = connection.toView())

    private fun homeChip(connection: Connection, label: String) {
        show { HomePlaceholderScreen(home(connection), {}, {}, {}) }
        assertTrue("label $label", shown(label))
    }

    @Test fun homeChipSaysHomeNetwork() = homeChip(Connection.Online(address("http://192.168.1.10:8096"), true), text(R.string.conn_home))
    @Test fun homeChipSaysAway() = homeChip(Connection.Online(address("https://away.example"), false), text(R.string.conn_away))
    @Test fun homeChipSaysChecking() = homeChip(Connection.Checking(), text(R.string.conn_checking))
    @Test fun homeChipSaysWhatItWasWhileChecking() =
        homeChip(Connection.Checking(Connection.Online(address("https://away.example"), false)), text(R.string.conn_checking_was, text(R.string.conn_away)))
    @Test fun homeChipSaysOffline() = homeChip(Connection.Offline(), text(R.string.conn_offline))

    @Test fun anOfflineReasonIsInPlainEnglishWithARetry() {
        show { HomePlaceholderScreen(home(Connection.Offline(AppError.Kind.CLIENT_CERT_REQUIRED)), {}, {}, {}) }
        assertTrue(shown(AppError.of(AppError.Kind.CLIENT_CERT_REQUIRED).userMessage))
        assertTrue(shown(text(R.string.action_retry)))
        assertTrue(shown(text(R.string.home_signed_in_as, "ben")))
    }

    @Test fun aChangedCertificateGetsTheProminentCardWithReview() {
        show { HomePlaceholderScreen(home(Connection.Offline(AppError.Kind.TLS_PIN_CHANGED)), {}, {}, {}) }
        assertTrue(shown(text(R.string.cert_changed_title)))
        assertTrue(shown(text(R.string.cert_review)))
    }

    @Test fun homeWithNobodySignedInOffersTheServersList() {
        show { HomePlaceholderScreen(HomeUiState(loading = false, signedOut = true), {}, {}, {}) }
        assertTrue(shown(text(R.string.home_signed_out_title)))
        assertTrue(shown(text(R.string.home_manage)))
    }
}
