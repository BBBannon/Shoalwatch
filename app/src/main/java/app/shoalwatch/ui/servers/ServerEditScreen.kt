package app.shoalwatch.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.CertificateScreen
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.HttpWarningScreen
import app.shoalwatch.ui.components.Loading
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.SecureWindow
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.components.Tone

class ServerEditActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onEditAddress: (AddressSlot) -> Unit,
    val onSetCleartext: (Boolean) -> Unit,
    val onForgetCertificate: () -> Unit,
    val onReviewCertificate: () -> Unit,
    val onChooseClientCertificate: () -> Unit,
    val onClearClientCertificate: () -> Unit,
    val onAddHeader: () -> Unit,
    val onEditHeader: (String) -> Unit,
    val onRemoveHeader: (String) -> Unit,
    val pane: PaneActions,
)

/** What the sub-pages (address, http warning, certificate review, header dialog) can do. */
class PaneActions(
    val onDismiss: () -> Unit,
    val onConfirmCleartext: () -> Unit,
    val onConfirmDestructive: () -> Unit,
    val onTrustReviewed: () -> Unit,
    val onAddressText: (String) -> Unit,
    val onSaveAddress: () -> Unit,
    val onCancelAddressCheck: () -> Unit,
    val onOfferHttp: () -> Unit,
    val onAllowHttp: () -> Unit,
    val onTrustAddressCertificate: () -> Unit,
    val onChooseAddressClientCertificate: () -> Unit,
    val onHeaderName: (String) -> Unit,
    val onHeaderValue: (String) -> Unit,
    val onSaveHeader: () -> Unit,
)

private enum class Page { Settings, Address, HttpWarning, Certificate }

/**
 * A server's connection settings. Marked secure for as long as it is visible (fingerprints, header values).
 * Sub-pages replace the list rather than stacking dialogs; the header editor is a dialog over the list.
 */
@Composable
fun ServerEditScreen(state: ServerEditUiState, actions: ServerEditActions) {
    SecureWindow()
    // System back does what the top-bar arrow does: close the open sub-page (or just a running check), not the screen.
    // A shown error is not "in progress": back then closes the editor, exactly like the top-bar arrow.
    val checkInProgress = state.addressCheck != Check.Idle && state.addressCheck !is Check.Failed
    BackHandler(enabled = state.pane.page() != Page.Settings) {
        if (state.pane.page() == Page.Address && checkInProgress) actions.pane.onCancelAddressCheck() else actions.pane.onDismiss()
    }
    AnimatedContent(
        targetState = state,
        contentKey = { it.pane.page() },
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "server-edit-page",
    ) { shown ->
        when (val pane = shown.pane) {
            is EditPane.Address -> AddressPane(pane, shown.addressCheck, shown.addressRole, shown.addressRequired, actions.pane)
            EditPane.HttpWarning -> HttpWarningScreen(onCancel = actions.pane.onDismiss, onUseAnyway = actions.pane.onConfirmCleartext)
            is EditPane.CertificateReview -> CertificateScreen(
                certificate = pane.certificate, loading = pane.loading, error = pane.error, changed = shown.certificateChanged,
                onTrust = actions.pane.onTrustReviewed, onCancel = actions.pane.onDismiss,
            )
            else -> SettingsPage(shown, actions)
        }
    }
    when (val pane = state.pane) {
        is EditPane.Header -> HeaderDialog(pane, actions.pane)
        is EditPane.ConfirmDestructive -> DestructiveDialog(pane.action, actions.pane)
        else -> Unit
    }
}

private fun EditPane?.page() = when (this) {
    is EditPane.Address -> Page.Address
    EditPane.HttpWarning -> Page.HttpWarning
    is EditPane.CertificateReview -> Page.Certificate
    else -> Page.Settings
}

@Composable
private fun SettingsPage(state: ServerEditUiState, actions: ServerEditActions) {
    AppScreen(title = stringResource(R.string.edit_title), onBack = actions.onBack) {
        when {
            !state.loaded -> Loading(stringResource(R.string.loading))
            state.missing -> NoticeCard(title = stringResource(R.string.edit_missing), body = null, tone = Tone.Info)
            else -> {
                ErrorMessage(state.error)
                SettingsSections(state, actions)
            }
        }
    }
}
