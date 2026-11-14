package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** An error shown next to what caused it. The icon and the words carry the meaning, not just the colour; screen readers announce it. */
@Composable
fun ErrorMessage(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

enum class Tone { Danger, Caution, Info }

/** A boxed notice with an icon and a bold title: [Tone.Danger] for security problems, [Tone.Caution] for warnings, [Tone.Info] for tips. */
@Composable
fun NoticeCard(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Caution,
    icon: ImageVector = if (tone == Tone.Info) Icons.Filled.Info else Icons.Filled.Warning,
    content: @Composable () -> Unit = {},
) {
    val colours = MaterialTheme.colorScheme
    val (container, onContainer) = when (tone) {
        Tone.Danger -> colours.errorContainer to colours.onErrorContainer
        Tone.Caution -> colours.tertiaryContainer to colours.onTertiaryContainer
        Tone.Info -> colours.secondaryContainer to colours.onSecondaryContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                content()
            }
        }
    }
}
