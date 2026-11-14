package app.offlinefin.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.components.CautionButton
import app.offlinefin.ui.components.ConnectionChip
import app.offlinefin.ui.components.FingerprintBlock
import app.offlinefin.ui.components.IconTextButton
import app.offlinefin.ui.components.NoticeCard
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.RevealToggle
import app.offlinefin.ui.components.SecondaryButton
import app.offlinefin.ui.components.Tone

@Composable
internal fun ColumnScope.SettingsSections(state: ServerEditUiState, actions: ServerEditActions) {
    if (state.certificateNeedsReview) {
        NoticeCard(
            title = stringResource(if (state.certificateChanged) R.string.cert_changed_title else R.string.cert_untrusted_alert_title),
            body = stringResource(if (state.certificateChanged) R.string.cert_changed_body else R.string.cert_untrusted_alert_body),
            tone = Tone.Danger,
        ) { PrimaryButton(stringResource(R.string.cert_review), actions.onReviewCertificate) }
    }
    state.connection?.let { connection ->
        Section(stringResource(R.string.edit_connection)) {
            ConnectionChip(connection)
            SecondaryButton(stringResource(R.string.action_retry), actions.onRetry)
        }
    }
    Section(stringResource(R.string.edit_addresses)) {
        AddressRow(stringResource(R.string.edit_home), state.homeAddress) { actions.onEditAddress(AddressSlot.Home) }
        AddressRow(stringResource(R.string.edit_away), state.awayAddress) { actions.onEditAddress(AddressSlot.Away) }
    }
    Section(stringResource(R.string.edit_security)) {
        SwitchRow(stringResource(R.string.edit_http_toggle), stringResource(R.string.edit_http_hint), state.allowCleartext, actions.onSetCleartext)
        CertificateRow(state, actions)
        ClientCertificateRow(state, actions)
    }
    Section(stringResource(R.string.edit_headers)) {
        HeadersSection(state, actions)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun AddressRow(label: String, value: String?, onEdit: () -> Unit) {
    val editLabel = stringResource(R.string.edit_address_edit_cd, label)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: stringResource(R.string.edit_not_set), style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) {
            Text(stringResource(R.string.action_edit))
        }
    }
}

/** The whole row toggles; turning on goes through the warning (the ViewModel opens it). */
@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CertificateRow(state: ServerEditUiState, actions: ServerEditActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.edit_cert), style = MaterialTheme.typography.labelLarge)
        if (state.pinFingerprint != null) {
            FingerprintBlock(state.pinFingerprint)
            CautionButton(stringResource(R.string.edit_forget_cert), actions.onForgetCertificate)
        } else {
            Text(stringResource(R.string.edit_cert_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClientCertificateRow(state: ServerEditUiState, actions: ServerEditActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.edit_client_cert), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.edit_client_cert_hint), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (state.hasClientCertificate) R.string.edit_client_cert_set else R.string.edit_client_cert_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = actions.onChooseClientCertificate, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_choose)) }
            if (state.hasClientCertificate) {
                TextButton(onClick = actions.onClearClientCertificate, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_client_cert_remove)) }
            }
        }
    }
}

@Composable
private fun HeadersSection(state: ServerEditUiState, actions: ServerEditActions) {
    Text(stringResource(R.string.edit_headers_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.headers.isEmpty()) Text(stringResource(R.string.edit_headers_none), style = MaterialTheme.typography.bodyMedium)
    state.headers.forEach { HeaderRow(it, actions) }
    IconTextButton(stringResource(R.string.edit_header_add), Icons.Filled.Add, actions.onAddHeader)
}

/**
 * A header whose value stays masked until revealed. The name and value are on the first line; the actions wrap
 * onto the next line(s), so nothing is squeezed at a large font. Reveal state is not saved across restarts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderRow(header: HeaderItem, actions: ServerEditActions) {
    var revealed by remember(header.name) { mutableStateOf(false) }
    val hidden = stringResource(R.string.header_hidden)
    Column(Modifier.fillMaxWidth()) {
        Text(header.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (revealed) header.value else stringResource(R.string.header_masked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { if (!revealed) contentDescription = hidden },
        )
        FlowRow(verticalArrangement = Arrangement.Center) {
            RevealToggle(
                revealed, { revealed = !revealed },
                stringResource(if (revealed) R.string.header_hide_cd else R.string.header_reveal_cd, header.name),
            )
            IconButton(onClick = { actions.onEditHeader(header.name) }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.header_edit_cd, header.name))
            }
            IconButton(onClick = { actions.onRemoveHeader(header.name) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.header_remove_cd, header.name), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
