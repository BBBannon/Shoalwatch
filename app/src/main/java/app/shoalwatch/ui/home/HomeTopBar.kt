package app.shoalwatch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.ConnectionChip
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.titleLines

/** The shortest the server name is ever squeezed to before the connection chip moves under it. */
private val MinTitleWidth = 120.dp

/** Larger text than this puts the chip under the title whatever room there is. */
private const val StackAtFontScale = 1.3f

/**
 * Home's top bar: the server name (always readable, a screen-reader heading with a Refresh action), a compact connection chip and
 * the account button. The chip sits beside the name only while the name keeps at least [MinTitleWidth] and the text is not large;
 * otherwise it goes on its own line under the name, so the name is never squeezed out by a long reading like
 * "Checking… (was Home network)".
 */
@Composable
internal fun HomeTopBar(state: HomeUiState, actions: HomeActions) {
    val refresh = stringResource(R.string.action_refresh)
    val stackedText = LocalDensity.current.fontScale > StackAtFontScale
    TopBarRow {
        TitleAndChip(
            stackedText = stackedText,
            title = {
                Text(
                    state.serverName ?: stringResource(R.string.app_name),
                    Modifier.semantics {
                        heading()
                        customActions = listOf(CustomAccessibilityAction(refresh) { actions.onRefresh(); true })
                    },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = titleLines(1),
                    overflow = TextOverflow.Ellipsis,
                )
            },
            chip = state.connection?.let { connection -> { ConnectionChip(connection, compact = true) } },
            trailing = { AccountButton(state, actions) },
        )
    }
}

/** The bar for the signed-out and could-not-load screens: the app name and a button straight to Servers and accounts. */
@Composable
internal fun PlaceholderTopBar(actions: HomeActions) {
    TopBarRow {
        TitleAndChip(
            stackedText = false,
            title = { Text(stringResource(R.string.app_name), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) },
            chip = null,
            trailing = {
                IconButton(onClick = actions.onOpenServers) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.home_manage)) }
            },
        )
    }
}

@Composable
private fun TopBarRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Gutter, end = 4.dp, top = 4.dp, bottom = 4.dp)) { content() }
}

/** True when the chip must leave the title's line: the text is large, or beside the chip the name would keep less than [minTitle]. */
internal fun chipGoesUnderTitle(stackedText: Boolean, room: Int, chipWidth: Int, gap: Int, minTitle: Int): Boolean =
    chipWidth > 0 && (stackedText || room - chipWidth - gap < minTitle)

/** Title and chip share a line when there is room (title first); otherwise the chip takes a line of its own. [trailing] stays at the end. */
@Composable
private fun TitleAndChip(
    stackedText: Boolean,
    title: @Composable () -> Unit,
    chip: (@Composable () -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { title() }
            Box { chip?.invoke() }
            Box { trailing() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = measurables[2].measure(loose)
        val room = (constraints.maxWidth - end.width - gap).coerceAtLeast(0)
        val chipPlaceable = measurables[1].measure(Constraints(maxWidth = room))
        val hasChip = chip != null && chipPlaceable.width > 0
        val stacked = hasChip && chipGoesUnderTitle(stackedText, room, chipPlaceable.width, gap, MinTitleWidth.roundToPx())
        val titleRoom = if (hasChip && !stacked) room - chipPlaceable.width - gap else room
        val titlePlaceable = measurables[0].measure(Constraints(maxWidth = titleRoom.coerceAtLeast(0)))
        val firstLine = maxOf(titlePlaceable.height, if (hasChip && !stacked) chipPlaceable.height else 0, end.height)
        val height = if (stacked) maxOf(firstLine + gap / 2 + chipPlaceable.height, end.height) else firstLine
        layout(constraints.maxWidth, height) {
            titlePlaceable.placeRelative(0, (firstLine - titlePlaceable.height) / 2)
            if (hasChip) {
                if (stacked) {
                    chipPlaceable.placeRelative(0, firstLine + gap / 2)
                } else {
                    chipPlaceable.placeRelative(titlePlaceable.width + gap, (firstLine - chipPlaceable.height) / 2)
                }
            }
            end.placeRelative(constraints.maxWidth - end.width, (firstLine - end.height) / 2)
        }
    }
}

/**
 * With one account the button is simply "Servers and accounts": one tap, nothing to switch, no menu to announce (Refresh is on the
 * title, as an action, and by pulling down). With several it opens a small menu: switch account, Refresh, and Servers and accounts;
 * the button says "Menu open" only while it is open, so it is not wordy every time it is focused.
 */
@Composable
private fun AccountButton(state: HomeUiState, actions: HomeActions) {
    if (state.accounts.size <= 1) {
        IconButton(onClick = actions.onOpenServers) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.home_manage)) }
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val menuOpen = stringResource(R.string.state_menu_open)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { if (open) stateDescription = menuOpen }) {
            Icon(Icons.Filled.AccountCircle, stringResource(R.string.account_menu_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.account_switch_title),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            val current = state.session?.userId
            state.accounts.forEach { account ->
                val name = if (account.userId == current) stringResource(R.string.account_current, account.userName) else account.userName
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; if (account.userId != current) actions.onSwitchAccount(account.userId) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_refresh), style = MaterialTheme.typography.bodyLarge) },
                onClick = { open = false; actions.onRefresh() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_manage), style = MaterialTheme.typography.bodyLarge) },
                onClick = { open = false; actions.onOpenServers() },
            )
        }
    }
}
