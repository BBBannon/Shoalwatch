package app.shoalwatch.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy

private fun SortBy.label(): Int = when (this) {
    SortBy.NAME -> R.string.sort_name
    SortBy.DATE_ADDED -> R.string.sort_date_added
    SortBy.RELEASE_DATE -> R.string.sort_release
    SortBy.RATING -> R.string.sort_rating
}

/** Names go A to Z; dates and ratings start with the newest and best. */
private fun SortBy.defaultDescending(): Boolean = this != SortBy.NAME

/**
 * The compact sort and filter bar: one chip that opens the sort menu (Name, Date added, Release date, Rating, then ascending or
 * descending) and a chip each for Unwatched and Favourites. The chips are toggles that say whether they are on.
 */
@Composable
fun SortFilterChips(sort: Sort, filter: ItemFilter, onSort: (Sort) -> Unit, onFilter: (ItemFilter) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val direction = stringResource(if (sort.descending) R.string.sort_descending else R.string.sort_ascending)
    val current = stringResource(R.string.sort_current, stringResource(sort.by.label()), direction)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(current) },
                trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SortBy.entries.forEach { by ->
                    MenuChoice(stringResource(by.label()), selected = by == sort.by) {
                        open = false
                        if (by != sort.by) onSort(Sort(by, by.defaultDescending()))
                    }
                }
                HorizontalDivider()
                MenuChoice(stringResource(R.string.sort_ascending), selected = !sort.descending) { open = false; onSort(sort.copy(descending = false)) }
                MenuChoice(stringResource(R.string.sort_descending), selected = sort.descending) { open = false; onSort(sort.copy(descending = true)) }
            }
        }
        FilterChip(
            selected = filter.unwatchedOnly,
            onClick = { onFilter(filter.copy(unwatchedOnly = !filter.unwatchedOnly)) },
            label = { Text(stringResource(R.string.filter_unwatched)) },
        )
        FilterChip(
            selected = filter.favouritesOnly,
            onClick = { onFilter(filter.copy(favouritesOnly = !filter.favouritesOnly)) },
            label = { Text(stringResource(R.string.filter_favourites)) },
        )
    }
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}
