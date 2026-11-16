package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
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
import java.time.Instant

class UsersActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onDisable: (userId: String) -> Unit,
    val onEnable: (userId: String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** Every account on the server with its key settings, then a note that passwords and new users are in the full dashboard. */
@Composable
fun UsersScreen(state: UsersUiState, actions: UsersActions) {
    val text = rememberAdminText()
    val now = state.frame.updatedAt ?: Instant.now()
    AdminScaffold(
        stringResource(R.string.admin_users_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        items(state.users, key = { "user-${it.user.id}" }) { row ->
            UserCard(row, text, now, state.frame.canAct, { actions.onDisable(row.user.id) }, { actions.onEnable(row.user.id) })
        }
        if (state.frame.updatedAt != null) {
            item(key = "note") {
                Text(
                    stringResource(R.string.admin_users_note_dashboard),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
    }
    if (state.frame.gate is AdminGate.Open) state.confirm?.let { UserConfirmDialog(it, text, state.frame.canAct, actions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(row: UserRow, text: AdminText, now: Instant, canAct: Boolean, onDisable: () -> Unit, onEnable: () -> Unit) {
    val user = row.user
    val name = text.name(user.name, R.string.admin_unnamed_user)
    val body = MaterialTheme.typography.bodyMedium
    AdminCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (user.isYou) AdminBadge(stringResource(R.string.admin_you))
            if (user.policyKnown && user.isAdministrator) AdminBadge(stringResource(R.string.admin_user_admin))
            if (user.policyKnown && user.isDisabled) AdminBadge(stringResource(R.string.admin_user_disabled))
        }
        Text(
            user.lastSeen?.let { stringResource(R.string.admin_user_last_seen, text.ago(it, now)) } ?: stringResource(R.string.admin_user_never_seen),
            style = body,
        )
        when {
            !user.policyKnown -> Text(stringResource(R.string.admin_user_policy_unknown), style = body)
            else -> {
                Text(stringResource(if (user.canDownload) R.string.admin_user_downloads_yes else R.string.admin_user_downloads_no), style = body)
                Text(librariesText(row), style = body)
            }
        }
        when {
            !user.policyKnown -> Unit
            user.isYou -> Text(stringResource(R.string.admin_user_you_note), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            user.isDisabled -> AdminAction(stringResource(R.string.admin_user_enable), stringResource(R.string.admin_user_enable_cd, name), canAct, onClick = onEnable)
            else -> AdminAction(stringResource(R.string.admin_user_disable), stringResource(R.string.admin_user_disable_cd, name), canAct, caution = true, onClick = onDisable)
        }
    }
}

@Composable
private fun librariesText(row: UserRow): String = when (val line = libraryLine(row)) {
    LibraryLine.All -> stringResource(R.string.admin_user_libraries_all)
    LibraryLine.None -> stringResource(R.string.admin_user_libraries_none)
    is LibraryLine.Count -> stringResource(R.string.admin_user_libraries_count, line.chosen.toString())
    is LibraryLine.Names -> {
        val names = line.names.joinToString(", ")
        val list = if (line.more > 0) stringResource(R.string.admin_user_libraries_more, names, line.more.toString()) else names
        stringResource(R.string.admin_user_libraries_some, list)
    }
}

@Composable
private fun UserConfirmDialog(c: UserConfirm, text: AdminText, canAct: Boolean, actions: UsersActions) {
    val name = text.name(c.name, R.string.admin_unnamed_user)
    if (c.disable) {
        val body = listOfNotNull(
            stringResource(R.string.admin_confirm_disable_body, name),
            if (c.isAdministrator) stringResource(R.string.admin_confirm_disable_admin, name) else null,
        )
        AdminConfirmDialog(
            stringResource(R.string.admin_confirm_disable_title, name), body, stringResource(R.string.admin_user_disable),
            canAct, actions.onConfirm, actions.onDismissConfirm,
        )
    } else {
        AdminConfirmDialog(
            stringResource(R.string.admin_confirm_enable_title, name), listOf(stringResource(R.string.admin_confirm_enable_body, name)),
            stringResource(R.string.admin_user_enable), canAct, actions.onConfirm, actions.onDismissConfirm,
        )
    }
}
