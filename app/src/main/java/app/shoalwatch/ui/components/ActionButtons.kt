package app.shoalwatch.ui.components

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/**
 * "Mark as watched" / "Mark as unwatched" as a button: [watched] is the current state, so it offers the opposite. While the change
 * is on its way to the server ([working]) it shows a small spinner and is switched off, so a tap never looks missed or repeats.
 */
@Composable
fun WatchedButton(label: String, watched: Boolean, working: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val workingText = stringResource(R.string.cd_working)
    FilledTonalButton(
        onClick = onClick, enabled = !working,
        modifier = modifier.heightIn(min = 56.dp).semantics { if (working) stateDescription = workingText },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            else Icon(if (watched) Icons.Filled.Refresh else Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, textAlign = TextAlign.Start, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A heart that is filled when favourite; a 48dp control labelled with what it will do ("Add to favourites"). A spinner while working. */
@Composable
fun FavouriteButton(favourite: Boolean, working: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (favourite) R.string.sheet_unfavourite else R.string.sheet_favourite)
    val workingText = stringResource(R.string.cd_working)
    IconButton(
        onClick = onClick, enabled = !working,
        modifier = modifier.size(56.dp).semantics { contentDescription = label; if (working) stateDescription = workingText },
    ) {
        if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(
            if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = if (favourite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
