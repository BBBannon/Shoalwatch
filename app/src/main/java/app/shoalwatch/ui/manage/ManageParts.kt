package app.shoalwatch.ui.manage

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.LockableField
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.ui.components.TouchTarget

/** A labelled check box row, 48dp tall, the whole row its target. */
@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A labelled radio row, 48dp tall, the whole row its target. */
@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun RefreshMode.label(): Int = when (this) {
    RefreshMode.SCAN -> R.string.manage_refresh_scan
    RefreshMode.MISSING -> R.string.manage_refresh_missing
    RefreshMode.REPLACE_ALL -> R.string.manage_refresh_all
}

internal fun ImageKind.label(): Int = when (this) {
    ImageKind.PRIMARY -> R.string.image_kind_primary
    ImageKind.BACKDROP -> R.string.image_kind_backdrop
    ImageKind.LOGO -> R.string.image_kind_logo
    ImageKind.THUMB -> R.string.image_kind_thumb
    ImageKind.BANNER -> R.string.image_kind_banner
}

internal fun LockableField.label(): Int = when (this) {
    LockableField.NAME -> R.string.lock_field_name
    LockableField.OVERVIEW -> R.string.lock_field_overview
    LockableField.GENRES -> R.string.lock_field_genres
    LockableField.TAGS -> R.string.lock_field_tags
    LockableField.STUDIOS -> R.string.lock_field_studios
    LockableField.OFFICIAL_RATING -> R.string.lock_field_official_rating
    LockableField.CAST -> R.string.lock_field_cast
    LockableField.PRODUCTION_LOCATIONS -> R.string.lock_field_locations
    LockableField.RUNTIME -> R.string.lock_field_runtime
}

/** "“The Silent Harbour”" with curly quotes, or the neutral word for an item with no name. */
@Composable
internal fun quoted(title: String): String =
    "“" + title.ifEmpty { androidx.compose.ui.res.stringResource(R.string.manage_unnamed) } + "”"
