package app.shoalwatch.ui.servers

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.AddressRole
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.ConfirmDialog
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.RevealToggle
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import app.shoalwatch.ui.components.rememberSubmit
import app.shoalwatch.ui.onboarding.AddressField
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.onboarding.CheckGate

/**
 * Editing one address. Saving runs the same checks as adding a server (including "is this the same
 * server?"), so a warning can appear here too; cancelling a warning returns to the field with the text kept.
 */
@Composable
internal fun AddressPane(pane: EditPane.Address, check: Check, role: AddressRole?, addressRequired: Boolean, actions: PaneActions) {
    CheckGate(
        check = check,
        role = role,
        onCancel = actions.onCancelAddressCheck,
        onAllowHttp = actions.onAllowHttp,
        onTrustCertificate = actions.onTrustAddressCertificate,
        onChooseClientCertificate = actions.onChooseAddressClientCertificate,
    ) { checking, failed -> AddressEditor(pane, checking, failed, addressRequired, actions) }
}

@Composable
private fun AddressEditor(pane: EditPane.Address, checking: Boolean, failed: Check.Failed?, addressRequired: Boolean, actions: PaneActions) {
    val title = stringResource(if (pane.slot == AddressSlot.Home) R.string.edit_home else R.string.edit_away)
    val submit = rememberSubmit(actions.onSaveAddress)
    AppScreen(
        title = title,
        onBack = actions.onDismiss,
        bottomBar = {
            PrimaryButton(stringResource(R.string.edit_address_save), submit, loading = checking)
            if (checking) {
                SecondaryButton(stringResource(R.string.action_cancel), actions.onCancelAddressCheck)
            } else {
                SecondaryButton(stringResource(R.string.action_cancel), actions.onDismiss)
            }
        },
    ) {
        AddressField(
            value = pane.text,
            onChange = actions.onAddressText,
            label = title,
            isError = failed != null,
            enabled = !checking,
            onGo = submit,
        )
        Text(stringResource(R.string.edit_address_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ErrorMessage(failed?.message ?: if (addressRequired) stringResource(R.string.edit_address_required) else null)
        if (failed?.offerHttp == true) SecondaryButton(stringResource(R.string.add_try_http), actions.onOfferHttp)
    }
}

/** Add or edit a header. The value is masked while typing unless revealed; errors never repeat it. The dialog window is secured too. */
@Composable
internal fun HeaderDialog(pane: EditPane.Header, actions: PaneActions) {
    var revealed by remember { mutableStateOf(false) }
    val reveal = if (pane.name.isBlank()) stringResource(R.string.header_reveal_generic) else stringResource(R.string.header_reveal_cd, pane.name)
    val hide = if (pane.name.isBlank()) stringResource(R.string.header_hide_generic) else stringResource(R.string.header_hide_cd, pane.name)
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        properties = SecureDialog,
        title = {
            Text(
                stringResource(if (pane.originalName == null) R.string.header_add_title else R.string.header_edit_title),
                Modifier.semantics { heading() },
            )
        },
        text = {
            val obscured = FilterObscuredTouches()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
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
                        if (!revealed) {
                            password()
                            editableText = AnnotatedString("•".repeat(pane.value.length))
                        }
                    },
                    label = { Text(stringResource(R.string.header_value_label)) },
                    singleLine = true,
                    isError = pane.error != null,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    trailingIcon = { RevealToggle(revealed, { revealed = !revealed }, if (revealed) hide else reveal) },
                )
                ErrorMessage(pane.error)
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = actions.onSaveHeader) { Text(stringResource(R.string.action_save)) } },
    )
}

/** Confirmation for forgetting the certificate, removing the client certificate or deleting a header. */
@Composable
internal fun DestructiveDialog(action: Destructive, actions: PaneActions) {
    val title: String
    val body: String
    val confirm: String
    when (action) {
        Destructive.ForgetCertificate -> {
            title = stringResource(R.string.confirm_forget_cert_title)
            body = stringResource(R.string.confirm_forget_cert_body)
            confirm = stringResource(R.string.confirm_forget_cert_action)
        }
        Destructive.RemoveClientCertificate -> {
            title = stringResource(R.string.confirm_client_cert_title)
            body = stringResource(R.string.confirm_client_cert_body)
            confirm = stringResource(R.string.action_remove)
        }
        is Destructive.RemoveHeader -> {
            title = stringResource(R.string.confirm_header_title, action.name)
            body = stringResource(R.string.confirm_header_body)
            confirm = stringResource(R.string.action_remove)
        }
    }
    ConfirmDialog(title = title, confirmLabel = confirm, onConfirm = actions.onConfirmDestructive, onDismiss = actions.onDismiss) { Text(body) }
}
