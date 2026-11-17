package app.shoalwatch.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.BulletRow
import app.shoalwatch.ui.components.ConfirmDialog
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.IconTextButton
import app.shoalwatch.ui.components.Loading
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.Tone
import app.shoalwatch.ui.downloads.SyncStatusLine
import androidx.compose.foundation.text.input.TextFieldState
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.ui.lock.GrownUpAsk
import app.shoalwatch.ui.lock.GrownUpDialog
import app.shoalwatch.ui.lock.ProtectedBadge
import app.shoalwatch.ui.lock.SwitchPinDialog
import app.shoalwatch.ui.lock.SwitchPrompt

class ServersActions(
    val onBack: () -> Unit,
    val onAddServer: () -> Unit,
    val onSwitch: (serverId: String, userId: String) -> Unit,
    val onAskSignOut: (serverId: String, userId: String) -> Unit,
    val onAskRemove: (serverId: String) -> Unit,
    val onAddAccount: (serverId: String) -> Unit,
    val onSettings: (serverId: String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onDismissNotice: () -> Unit = {},
    val onAdmin: () -> Unit = {},
    /** An expired sign-in: sign in again as that profile (its name filled in). */
    val onSignInAgain: (serverId: String, userName: String) -> Unit = { _, _ -> },
    val onSubmitSwitch: () -> Unit = {},
    val onDismissSwitch: () -> Unit = {},
    val onChooseGrownUp: (AccountSummary) -> Unit = {},
    val onSubmitGrownUp: () -> Unit = {},
    val onDismissGrownUp: () -> Unit = {},
)

/** The PIN dialogs the Servers screen can show: a switch into a protected profile, or a grown-up's check. */
class ServersPinDialogs(
    val switchPrompt: SwitchPrompt? = null,
    val switchPin: TextFieldState? = null,
    val grownUp: GrownUpAsk? = null,
    val grownUpPin: TextFieldState? = null,
)

/** Every saved server with its accounts: switch the active one, add, sign out, remove, open connection settings. */
@Composable
fun ServersScreen(state: ServersUiState, actions: ServersActions, dialogs: ServersPinDialogs = ServersPinDialogs()) {
    AppScreen(
        title = stringResource(R.string.servers_title),
        onBack = actions.onBack,
        bottomBar = { PrimaryButton(stringResource(R.string.servers_add), actions.onAddServer) },
    ) {
        ErrorMessage(state.error)
        if (state.householdRace) ErrorMessage(stringResource(R.string.grownup_race))
        state.notice?.let { notice ->
            NoticeCard(
                title = stringResource(if (notice == ServersNotice.SignedOutHereOnly) R.string.notice_signed_out_title else R.string.notice_removed_title),
                body = stringResource(R.string.notice_not_revoked_body),
                tone = Tone.Caution,
            ) { TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.action_ok)) } }
        }
        when {
            state.loading -> Loading(stringResource(R.string.loading))
            state.servers.isEmpty() -> {
                Text(stringResource(R.string.servers_empty_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.servers_empty_body), style = MaterialTheme.typography.bodyLarge)
            }
            else -> state.servers.forEach { ServerCard(it, actions, showAdmin = it.id == state.adminServerId) }
        }
    }
    when (val confirm = state.confirm) {
        is Confirm.SignOut -> ConfirmDialog(
            title = stringResource(R.string.confirm_signout_title, confirm.userName),
            confirmLabel = stringResource(R.string.sign_out),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            busyLabel = stringResource(R.string.sync_sending_first).takeIf { state.sending },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.confirm_signout_body, confirm.serverName))
                if (confirm.waiting > 0) Text(pluralStringResource(R.plurals.sign_out_waiting, confirm.waiting, confirm.waiting.toString()))
                Text(stringResource(R.string.dl_signout_downloads))
                if (confirm.sharing) Text(stringResource(R.string.dl_signout_shared))
            }
        }
        is Confirm.RemoveServer -> ConfirmDialog(
            title = stringResource(R.string.confirm_remove_title, confirm.serverName),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onDismissConfirm,
            busyLabel = stringResource(R.string.sync_sending_first).takeIf { state.sending },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.confirm_remove_body))
                confirm.accountNames.forEach { BulletRow(stringResource(R.string.confirm_remove_account, it)) }
                BulletRow(stringResource(R.string.confirm_remove_settings))
                Text(stringResource(R.string.dl_remove_downloads))
                if (confirm.accountNames.isNotEmpty()) Text(stringResource(R.string.confirm_remove_revoke))
                if (confirm.waiting > 0) Text(pluralStringResource(R.plurals.remove_server_waiting, confirm.waiting, confirm.waiting.toString()))
                confirm.profiles.forEach { BulletRow(pluralStringResource(R.plurals.remove_profile_waiting, it.waiting, it.userName, it.waiting.toString())) }
                if (confirm.profiles.any { it.expired }) Text(stringResource(R.string.remove_expired_not_sent))
            }
        }
        null -> Unit
    }
    if (dialogs.switchPrompt != null && dialogs.switchPin != null) {
        SwitchPinDialog(dialogs.switchPrompt, dialogs.switchPin, actions.onSubmitSwitch, actions.onDismissSwitch)
    }
    if (dialogs.grownUp != null && dialogs.grownUpPin != null) {
        GrownUpDialog(dialogs.grownUp, dialogs.grownUpPin, actions.onChooseGrownUp, actions.onSubmitGrownUp, actions.onDismissGrownUp)
    }
}

@Composable
private fun ServerCard(server: ServerItem, actions: ServersActions, showAdmin: Boolean) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.found_version, server.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (server.certificateChanged) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.cert_badge), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (server.accounts.isEmpty()) {
                Text(stringResource(R.string.no_accounts), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Column(Modifier.padding(top = 8.dp).selectableGroup()) {
                    server.accounts.forEach { if (it.expired) ExpiredAccountRow(server.id, it, actions) else AccountRow(server.id, it, actions) }
                }
            }
            IconTextButton(stringResource(R.string.server_add_account), Icons.Filled.Add, { actions.onAddAccount(server.id) })
            IconTextButton(stringResource(R.string.server_settings), Icons.Filled.Settings, { actions.onSettings(server.id) })
            if (showAdmin) {
                val adminLabel = stringResource(R.string.admin_entry_cd, server.name)
                IconTextButton(stringResource(R.string.admin_entry), Icons.Filled.Build, actions.onAdmin, Modifier.semantics { contentDescription = adminLabel })
            }
            IconTextButton(stringResource(R.string.server_remove), Icons.Filled.Delete, { actions.onAskRemove(server.id) }, caution = true)
        }
    }
}

/**
 * A profile whose sign-in the server rejected: "Sign-in expired" and "Sign in again" (its name filled in), with its sync line
 * ("Sign in again to send 6 changes."). Not a radio: it cannot be made active until it is signed in again (Tasks 9-10 review I2).
 */
@Composable
private fun ExpiredAccountRow(serverId: String, account: AccountItem, actions: ServersActions) {
    val againLabel = stringResource(R.string.account_sign_in_again_cd, account.userName)
    val signOutLabel = stringResource(R.string.sign_out_cd, account.userName)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(account.userName, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.account_sign_in_expired), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { actions.onSignInAgain(serverId, account.userName) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = againLabel }) {
            Text(stringResource(R.string.account_sign_in_again))
        }
        TextButton(onClick = { actions.onAskSignOut(serverId, account.userId) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = signOutLabel }) {
            Text(stringResource(R.string.sign_out))
        }
    }
    SyncStatusLine(account.sync, System.currentTimeMillis(), Modifier.fillMaxWidth().padding(start = 36.dp, bottom = 8.dp))
}

/** One account: tapping the row makes it the active one (shown by a tick and the word "Active"); "Sign out" is separate. */
@Composable
private fun AccountRow(serverId: String, account: AccountItem, actions: ServersActions) {
    val signOutLabel = stringResource(R.string.sign_out_cd, account.userName)
    val useLabel = stringResource(R.string.account_use_cd, if (account.isProtected) stringResource(R.string.profile_protected_cd, account.userName) else account.userName)
    val activeLabel = stringResource(R.string.profile_protected_cd, account.userName)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .selectable(selected = account.isActive, role = Role.RadioButton, onClick = { actions.onSwitch(serverId, account.userId) })
                .semantics {
                    if (!account.isActive) contentDescription = useLabel else if (account.isProtected) contentDescription = activeLabel
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (account.isActive) Icons.Filled.CheckCircle else Icons.Filled.Person,
                contentDescription = null,
                tint = if (account.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(account.userName, style = MaterialTheme.typography.bodyLarge)
                if (account.isActive) Text(stringResource(R.string.account_active), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (account.isProtected) ProtectedBadge()
            }
        }
        TextButton(onClick = { actions.onAskSignOut(serverId, account.userId) }, modifier = Modifier.semantics { contentDescription = signOutLabel }) {
            Text(stringResource(R.string.sign_out))
        }
    }
    // Its own element under the row, lined up with the name (icon 24dp + gap 12dp).
    SyncStatusLine(account.sync, System.currentTimeMillis(), Modifier.fillMaxWidth().padding(start = 36.dp, bottom = 8.dp))
}
