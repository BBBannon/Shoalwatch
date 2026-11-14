package app.offlinefin.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.data.servers.ServerInfo
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.ErrorMessage
import app.offlinefin.ui.components.NoticeCard
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.SecondaryButton
import app.offlinefin.ui.components.Tone

/** The server answered: show what it is. An unsupported version is a banner, and "Continue anyway" the only way on. */
@Composable
internal fun FoundStep(state: AddServerUiState, actions: AddServerActions) {
    val found = state.found ?: return
    val supported = found.compatibility == ServerInfo.Compatibility.SUPPORTED
    AppScreen(
        title = stringResource(R.string.found_title),
        onBack = actions.onBackToAddress,
        bottomBar = {
            PrimaryButton(stringResource(if (supported) R.string.action_continue else R.string.version_continue), actions.onConfirmFound)
        },
    ) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(found.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.found_version, found.version), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (found.alreadySaved) NoticeCard(title = stringResource(R.string.found_already_saved), body = null, tone = Tone.Info)
        if (!supported) {
            NoticeCard(
                title = stringResource(R.string.version_warning_cd),
                body = stringResource(
                    when (found.compatibility) {
                        ServerInfo.Compatibility.TOO_OLD -> R.string.version_old
                        ServerInfo.Compatibility.NEWER_UNTESTED -> R.string.version_new
                        else -> R.string.version_unknown
                    },
                    found.version,
                ),
                tone = Tone.Caution,
            )
        }
        Text(
            stringResource(if (found.firstIsHome) R.string.found_home_note else R.string.found_away_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The optional other address: explains home against away and checks the new address is the same server before it is saved. */
@Composable
internal fun SecondAddressStep(state: AddServerUiState, actions: AddServerActions) {
    val failed = state.check as? Check.Failed
    val firstIsHome = state.found?.firstIsHome == true
    AppScreen(
        title = stringResource(R.string.second_title),
        onBack = actions.onBackToAddress,
        bottomBar = {
            PrimaryButton(
                text = stringResource(if (state.saving) R.string.second_saving else R.string.second_add),
                onClick = actions.onAddSecond,
                enabled = state.secondAddress.isNotBlank(),
                loading = state.saving,
            )
            SecondaryButton(stringResource(R.string.action_skip), actions.onSkipSecond, enabled = !state.saving)
        },
    ) {
        Text(stringResource(R.string.second_body), style = MaterialTheme.typography.bodyLarge)
        AddressKindRow(Icons.Filled.Home, stringResource(R.string.second_home_title), stringResource(R.string.second_home_body))
        AddressKindRow(Icons.Filled.Place, stringResource(R.string.second_away_title), stringResource(R.string.second_away_body))
        Text(
            stringResource(if (firstIsHome) R.string.second_for_away else R.string.second_for_home),
            style = MaterialTheme.typography.titleSmall,
        )
        AddressField(
            value = state.secondAddress,
            onChange = actions.onSecondAddressChange,
            label = stringResource(R.string.second_label),
            hint = stringResource(R.string.add_address_hint),
            isError = failed != null || state.error != null,
            onGo = actions.onAddSecond,
        )
        ErrorMessage(failed?.message ?: state.error)
    }
}

@Composable
private fun AddressKindRow(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
