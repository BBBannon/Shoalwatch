package app.offlinefin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.ConnectionView
import app.offlinefin.ui.ConnectionView.Kind

@Composable
private fun Kind.label(): String = stringResource(
    when (this) {
        Kind.HOME -> R.string.conn_home
        Kind.AWAY -> R.string.conn_away
        Kind.CHECKING -> R.string.conn_checking
        Kind.OFFLINE -> R.string.conn_offline
    },
)

private fun Kind.icon(): ImageVector = when (this) {
    Kind.HOME -> Icons.Filled.Home
    Kind.AWAY -> Icons.Filled.Place
    Kind.CHECKING -> Icons.Filled.Refresh
    Kind.OFFLINE -> Icons.Filled.Warning
}

/**
 * Where the connection is right now: icon plus words ("Home network", "Away", "Checking…", "Offline"), never
 * colour alone. While checking it shows what it was a moment ago, subtly. For [Kind.OFFLINE] the plain-English
 * reason is shown beneath.
 */
@Composable
fun ConnectionChip(view: ConnectionView, modifier: Modifier = Modifier) {
    val label = when {
        view.kind == Kind.CHECKING && view.previous != null -> stringResource(R.string.conn_checking_was, view.previous.label())
        else -> view.kind.label()
    }
    val description = stringResource(R.string.conn_cd, label)
    val offline = view.kind == Kind.OFFLINE
    val container = if (offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (offline) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (view.kind == Kind.CHECKING) Color.Transparent else container,
            contentColor = if (view.kind == Kind.CHECKING) MaterialTheme.colorScheme.onSurfaceVariant else content,
            border = if (view.kind == Kind.CHECKING) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(view.kind.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (offline && view.detail != null) {
            Text(view.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
