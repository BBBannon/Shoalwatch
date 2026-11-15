package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.admin.ServerDevice
import java.time.Instant

class DevicesActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onRemove: (deviceId: String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** Devices signed in to the server: who, which app, last seen, and Remove (never for this phone). */
@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesActions) {
    val text = rememberAdminText()
    val now = state.frame.updatedAt ?: Instant.now()
    AdminScaffold(
        stringResource(R.string.admin_devices_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        if (state.devices.isEmpty() && state.frame.updatedAt != null) {
            item(key = "empty") { Text(stringResource(R.string.admin_devices_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.devices, key = { "device-${it.id}" }) { d -> DeviceCard(d, text, now, state.frame.canAct) { actions.onRemove(d.id) } }
    }
    if (state.frame.gate is AdminGate.Open) {
        state.confirm?.let { c ->
            val name = text.name(c.name, R.string.admin_unnamed_device)
            AdminConfirmDialog(
                stringResource(R.string.admin_confirm_device_title, name),
                listOf(stringResource(R.string.admin_confirm_device_body, text.name(c.userNames, R.string.admin_unnamed_user))),
                stringResource(R.string.admin_confirm_device_action),
                state.frame.canAct, actions.onConfirm, actions.onDismissConfirm,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(device: ServerDevice, text: AdminText, now: Instant, canAct: Boolean, onRemove: () -> Unit) {
    val name = text.name(device.name, R.string.admin_unnamed_device)
    val body = MaterialTheme.typography.bodyMedium
    AdminCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (device.isThisDevice) AdminBadge(stringResource(R.string.admin_this_phone))
        }
        if (device.userNames.isNotEmpty()) Text(stringResource(R.string.admin_device_users, device.userNames.joinToString(", ")), style = body)
        val app = when {
            device.appName.isEmpty() -> null
            device.appVersion.isEmpty() -> device.appName
            else -> stringResource(R.string.admin_device_app, device.appName, device.appVersion)
        }
        app?.let { Text(it, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(R.string.admin_device_last_seen, text.ago(device.lastSeen, now)), style = body)
        if (device.isThisDevice) {
            Text(stringResource(R.string.admin_device_this_phone_note), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AdminAction(stringResource(R.string.admin_device_remove), stringResource(R.string.admin_device_remove_cd, name), canAct, caution = true, onClick = onRemove)
        }
    }
}
