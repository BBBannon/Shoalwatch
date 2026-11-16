package app.shoalwatch.ui.components

import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/** What the sheet shows for one item, worked out by the screen from the item as it is now. */
data class SheetTarget(
    val itemId: String,
    val title: String,
    val played: Boolean,
    val favourite: Boolean,
    /** Only on the Continue Watching row. */
    val canRemoveFromResume: Boolean,
    /** Set for an episode: the series to go to. */
    val seriesId: String?,
    /** Whether the item can be marked watched or unwatched from here (everything the screens list can). */
    val canMarkPlayed: Boolean = true,
)

/** What each sheet row does. The screen closes the sheet itself after any of them. */
class SheetActions(
    val onRemoveFromResume: (itemId: String) -> Unit,
    val onSetPlayed: (itemId: String, played: Boolean) -> Unit,
    val onSetFavourite: (itemId: String, favourite: Boolean) -> Unit,
    val onGoToSeries: (seriesId: String) -> Unit,
    val onDetails: (itemId: String) -> Unit,
)

/**
 * The item actions sheet, opened by a long press or the dots on a card: Remove from Continue Watching (only on that row), Mark as
 * watched or unwatched, Add to or Remove from favourites, Go to series (episodes) and Details. Each action is a plain labelled
 * button at least 56dp tall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(target: SheetTarget, actions: SheetActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls: at a large text size, or on a phone held sideways, the last rows would otherwise be out of reach.
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                target.title,
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (target.canRemoveFromResume) {
                SheetRow(R.string.sheet_remove_resume, Icons.Filled.Clear) { actions.onRemoveFromResume(target.itemId); onDismiss() }
            }
            if (target.canMarkPlayed) {
                SheetRow(if (target.played) R.string.sheet_mark_unwatched else R.string.sheet_mark_watched, if (target.played) Icons.Filled.Refresh else Icons.Filled.Check) {
                    actions.onSetPlayed(target.itemId, !target.played)
                    onDismiss()
                }
            }
            SheetRow(if (target.favourite) R.string.sheet_unfavourite else R.string.sheet_favourite, if (target.favourite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite) {
                actions.onSetFavourite(target.itemId, !target.favourite)
                onDismiss()
            }
            if (target.seriesId != null) {
                SheetRow(R.string.sheet_go_series, Icons.AutoMirrored.Filled.ArrowForward) { actions.onGoToSeries(target.seriesId); onDismiss() }
            }
            SheetRow(R.string.sheet_details, Icons.Filled.Info) { actions.onDetails(target.itemId); onDismiss() }
        }
    }
}

@Composable
private fun SheetRow(label: Int, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
