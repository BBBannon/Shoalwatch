package app.shoalwatch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A group's heading (a screen-reader heading). */
@Composable
internal fun SettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** A help line under a row: small, but never under 13sp (`bodySmall` is 14sp here, and grows with the font scale). */
@Composable
internal fun SettingsHelp(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A switch row: the whole row toggles (role Switch, its state said), 48dp or more; [help] is read with it. Disabled rows say so. */
@Composable
internal fun SettingsSwitch(label: String, help: String?, on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) SettingsHelp(help)
        }
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** One choice of several: radio rows in a selectable group, each 48dp or more and saying whether it is selected. */
@Composable
internal fun <T> SettingsRadios(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        choices.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value == selected, role = Role.RadioButton) { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A plain action in the list (48dp or more), labelled by its words. */
@Composable
internal fun SettingsButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}
