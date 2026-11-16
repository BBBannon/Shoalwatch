package app.shoalwatch.ui.downloads

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.rememberStrings

/** What the settings screen does. */
data class DownloadSettingsActions(
    val onBack: () -> Unit,
    val onMobileData: (Boolean) -> Unit,
    val onStorage: (StorageKind, String?) -> Unit,
    val onQuality: (DownloadQuality?) -> Unit,
    val onSharing: (Boolean) -> Unit,
)

/**
 * "Download settings": mobile data, where new downloads go (the SD card only while one is mounted, with its plain warning),
 * the quality for new downloads, the active server's sharing switch, and a link to the system notification settings when
 * notifications are off. Every row is 48dp or more and says its state; the screen scrolls (`AppScreen`).
 */
@Composable
fun DownloadSettingsScreen(state: DownloadSettingsUiState, actions: DownloadSettingsActions, notificationsOn: Boolean = notificationsEnabled()) {
    val strings = rememberStrings()
    val context = LocalContext.current
    AppScreen(title = stringResource(R.string.dl_settings_title), onBack = actions.onBack) {
        SwitchRow(stringResource(R.string.dl_mobile_data), stringResource(R.string.dl_mobile_data_off), state.settings.mobileData, actions.onMobileData)
        Heading(stringResource(R.string.dl_save_to))
        QualityRow(stringResource(R.string.dl_save_phone), null, state.settings.storage == StorageKind.INTERNAL) { actions.onStorage(StorageKind.INTERNAL, null) }
        state.cards.forEach { card ->
            val on = state.settings.storage == StorageKind.REMOVABLE && state.settings.volumeId == card.volumeId
            QualityRow(stringResource(R.string.dl_save_card), stringResource(R.string.dl_card_warning), on) { actions.onStorage(StorageKind.REMOVABLE, card.volumeId) }
        }
        Text(stringResource(R.string.dl_existing_stay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Heading(stringResource(R.string.dl_quality_for_new))
        QualityRow(stringResource(R.string.dl_quality_ask), null, state.settings.defaultQuality == null) { actions.onQuality(null) }
        DownloadQuality.entries.forEach { q -> QualityRow(DownloadText.quality(strings, q), null, state.settings.defaultQuality == q) { actions.onQuality(q) } }
        if (state.session != null) {
            SwitchRow(stringResource(R.string.dl_share), stringResource(R.string.dl_share_body, state.serverName.orEmpty()), state.sharing, actions.onSharing)
        }
        if (!notificationsOn) {
            Text(stringResource(R.string.dl_notifications_off), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.dl_notification_settings)) }
        }
    }
}

@Composable
private fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(LocalContext.current).areNotificationsEnabled()

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** A switch row: the whole row toggles (role Switch, its state said), 48dp or more. */
@Composable
private fun SwitchRow(label: String, body: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
