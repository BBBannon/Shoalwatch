package app.shoalwatch.ui.components

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Selection on a card (Milestone 10). [selecting]: the screen is in selection mode, so a tap adds or removes this card; a long
 * press always does. [selected] shows the mark. [selectLabel] ("Select The Silent Harbour") and [stateWords] ("Selected" / "Not
 * selected") are for a screen reader.
 */
class CardSelect(
    val selected: Boolean,
    val selecting: Boolean,
    val onToggle: () -> Unit,
    val selectLabel: String,
    val stateWords: String,
)

/**
 * Makes a card one focusable, labelled element: a tap opens it, a long press (or the "more actions" custom action for a screen
 * reader) opens its actions. [state] is spoken after the label ("Working", "Up next for you"). The separate [ItemMenuButton] sits beside it, never inside, so it stays its own control.
 * With [select] (Milestone 10) a long press selects instead, a tap selects while selecting, and "Select" is a custom action too;
 * the "more actions" action and the dots button still open the one-item sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.cardAction(label: String, menuLabel: String, onClick: () -> Unit, onMenu: () -> Unit, state: String? = null, select: CardSelect? = null): Modifier =
    this
        .combinedClickable(
            onClick = if (select?.selecting == true) select.onToggle else onClick,
            onLongClick = select?.onToggle ?: onMenu,
            onLongClickLabel = select?.selectLabel ?: menuLabel,
            role = Role.Button,
        )
        .semantics(mergeDescendants = true) {
            contentDescription = label
            val words = listOfNotNull(state, select?.takeIf { it.selecting }?.stateWords)
            if (words.isNotEmpty()) stateDescription = words.joinToString(", ")
            if (select != null && select.selecting) selected = select.selected
            customActions = listOfNotNull(
                select?.let { sel -> CustomAccessibilityAction(sel.selectLabel) { sel.onToggle(); true } },
                CustomAccessibilityAction(menuLabel) { onMenu(); true },
            )
        }

/** The selected mark on a card: a filled check in the theme's primary colour, top-left. */
@Composable
fun SelectedMark(modifier: Modifier = Modifier) {
    Surface(modifier.size(28.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** A selected card's outline. */
fun Modifier.selectedOutline(selected: Boolean, color: androidx.compose.ui.graphics.Color): Modifier =
    if (selected) this.border(3.dp, color, CardShape) else this

/**
 * The "more actions" button: a 48dp touch target with a smaller disc behind the dots so it stays readable on top of a picture.
 * Labelled "More actions for <title>".
 */
@Composable
fun ItemMenuButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(TouchTarget).semantics { contentDescription = label }) {
        Surface(Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The same 48dp "more actions" button for use on a plain surface (an episode row) rather than over a picture. */
@Composable
fun MenuDots(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(TouchTarget).semantics { contentDescription = label }) {
        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
