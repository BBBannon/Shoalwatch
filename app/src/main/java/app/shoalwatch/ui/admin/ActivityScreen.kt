package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.ui.components.SecondaryButton
import java.time.Instant

class ActivityActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** The activity log, newest first; "Show older entries" at the end until the end of the log or Shoalwatch's limit. */
@Composable
fun ActivityScreen(state: ActivityUiState, actions: ActivityActions) {
    val text = rememberAdminText()
    val now = state.frame.updatedAt ?: Instant.now()
    AdminScaffold(
        stringResource(R.string.admin_activity_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        items(state.entries, key = { "entry-${it.id}" }) { entry -> ActivityRow(entry, text, now) }
        if (state.frame.updatedAt == null) return@AdminScaffold
        item(key = "end") {
            when {
                state.entries.isEmpty() -> Text(stringResource(R.string.admin_activity_empty), style = MaterialTheme.typography.bodyMedium)
                state.limitReached -> EndLine(stringResource(R.string.admin_activity_limit))
                state.endReached -> EndLine(stringResource(R.string.admin_activity_end))
                else -> SecondaryButton(stringResource(R.string.admin_activity_more), actions.onLoadMore, enabled = state.frame.online && !state.loadingMore)
            }
        }
    }
}

@Composable
private fun EndLine(line: String) {
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One element for TalkBack: "Warning. kid failed to sign in. IP address: 192.168.1.20. Today at 14:02". The word is always written. */
@Composable
private fun ActivityRow(entry: ActivityEntry, text: AdminText, now: Instant) {
    val severity = text.severity(entry.severity)
    val time = text.ago(entry.at, now)
    val said = listOfNotNull(entry.text.trimEnd('.').ifEmpty { null }, entry.detail?.trimEnd('.')?.ifEmpty { null }).joinToString(". ")
    val spoken = stringResource(R.string.admin_activity_row_spoken, severity, said, time)
    val serious = entry.severity != Severity.INFORMATION
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (serious) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = if (serious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(severity, style = MaterialTheme.typography.labelLarge, color = if (serious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (entry.text.isNotEmpty()) Text(entry.text, style = MaterialTheme.typography.bodyMedium)
            entry.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
