package app.offlinefin.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.offlinefin.R
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.CertificateScreen
import app.offlinefin.ui.components.ErrorMessage
import app.offlinefin.ui.components.HttpWarningScreen
import app.offlinefin.ui.components.Loading
import app.offlinefin.ui.components.NoticeCard
import app.offlinefin.ui.components.SecureWindow
import app.offlinefin.ui.components.Tone

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
    val page = when (state.pane) {
        is EditPane.Address -> Page.Address
        EditPane.HttpWarning -> Page.HttpWarning
        is EditPane.CertificateReview -> Page.Certificate
        else -> Page.Settings
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "server-edit-page",
    ) { target ->
        when (target) {
            Page.Settings -> SettingsPage(state, actions)
            Page.Address -> (state.pane as? EditPane.Address)?.let { AddressPane(it, state.addressCheck, actions.pane) }
            Page.HttpWarning -> HttpWarningScreen(onCancel = actions.pane.onDismiss, onUseAnyway = actions.pane.onConfirmCleartext)
            Page.Certificate -> (state.pane as? EditPane.CertificateReview)?.let {
                CertificateScreen(
                    certificate = it.certificate, loading = it.loading, error = it.error, changed = state.certificateChanged,
                    onTrust = actions.pane.onTrustReviewed, onCancel = actions.pane.onDismiss,
                )
            }
        }
    }
    (state.pane as? EditPane.Header)?.let { HeaderDialog(it, actions.pane) }
}

@Composable
private fun SettingsPage(state: ServerEditUiState, actions: ServerEditActions) {
    AppScreen(title = stringResource(R.string.edit_title), onBack = actions.onBack) {
        when {
            !state.loaded -> Loading(stringResource(R.string.splash_loading))
            state.missing -> NoticeCard(title = stringResource(R.string.edit_missing), body = null, tone = Tone.Info)
            else -> {
                ErrorMessage(state.error)
                SettingsSections(state, actions)
            }
        }
    }
}
