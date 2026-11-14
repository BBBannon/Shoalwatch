package app.offlinefin.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.AddressRole
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.CertificateScreen
import app.offlinefin.ui.components.CheckingScreen
import app.offlinefin.ui.components.ClientCertificateScreen
import app.offlinefin.ui.components.ErrorMessage
import app.offlinefin.ui.components.HttpWarningScreen
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.SecondaryButton
import app.offlinefin.ui.onboarding.AddressField
import app.offlinefin.ui.onboarding.Check

/**
 * Editing one address. Saving runs the same checks as adding a server (including "is this the same
 * server?"), so a warning can appear here too; cancelling a warning returns to the field with the text kept.
 */
@Composable
internal fun AddressPane(pane: EditPane.Address, check: Check, role: AddressRole?, addressRequired: Boolean, actions: PaneActions) {
    when (check) {
        Check.Checking -> CheckingScreen(
            title = stringResource(R.string.add_title),
            detail = stringResource(R.string.add_checking),
            cancelLabel = stringResource(R.string.action_cancel),
            onCancel = actions.onCancelAddressCheck,
        )
        Check.NeedsHttpConsent -> HttpWarningScreen(onCancel = actions.onCancelAddressCheck, onUseAnyway = actions.onAllowHttp, role = role)
        Check.LoadingCertificate, is Check.NeedsCertificateTrust -> CertificateScreen(
            certificate = (check as? Check.NeedsCertificateTrust)?.certificate,
            loading = check == Check.LoadingCertificate,
            error = null,
            changed = false,
            onTrust = actions.onTrustAddressCertificate,
            onCancel = actions.onCancelAddressCheck,
        )
        Check.NeedsClientCertificate -> ClientCertificateScreen(onChoose = actions.onChooseAddressClientCertificate, onCancel = actions.onCancelAddressCheck)
        else -> AddressEditor(pane, check as? Check.Failed, addressRequired, actions)
    }
}

@Composable
private fun AddressEditor(pane: EditPane.Address, failed: Check.Failed?, addressRequired: Boolean, actions: PaneActions) {
    val title = stringResource(if (pane.slot == AddressSlot.Home) R.string.edit_home else R.string.edit_away)
    AppScreen(
        title = title,
        onBack = actions.onDismiss,
        bottomBar = {
            PrimaryButton(stringResource(R.string.edit_address_save), actions.onSaveAddress)
            SecondaryButton(stringResource(R.string.action_cancel), actions.onDismiss)
        },
    ) {
        AddressField(
            value = pane.text,
            onChange = actions.onAddressText,
            label = title,
            hint = stringResource(R.string.add_address_hint),
            isError = failed != null,
            onGo = actions.onSaveAddress,
        )
        Text(stringResource(R.string.edit_address_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ErrorMessage(failed?.message ?: if (addressRequired) stringResource(R.string.edit_address_required) else null)
        if (failed?.offerHttp == true) SecondaryButton(stringResource(R.string.add_try_http), actions.onOfferHttp)
    }
}

/** Add or edit a header. The value is masked while typing unless revealed; errors never repeat it. */
@Composable
internal fun HeaderDialog(pane: EditPane.Header, actions: PaneActions) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = {
            Text(
                stringResource(if (pane.originalName == null) R.string.header_add_title else R.string.header_edit_title),
                Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pane.name,
                    onValueChange = actions.onHeaderName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.header_name_label)) },
                    singleLine = true,
                    isError = pane.error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = pane.value,
                    onValueChange = actions.onHeaderValue,
                    modifier = Modifier.fillMaxWidth().semantics {
                        // While masked, accessibility services must not read the raw value either.
                        if (!revealed) editableText = AnnotatedString("•".repeat(pane.value.length))
                    },
                    label = { Text(stringResource(R.string.header_value_label)) },
                    singleLine = true,
                    isError = pane.error != null,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(stringResource(if (revealed) R.string.action_hide else R.string.action_show))
                        }
                    },
                )
                ErrorMessage(pane.error)
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = actions.onSaveHeader) { Text(stringResource(R.string.action_save)) } },
    )
}
