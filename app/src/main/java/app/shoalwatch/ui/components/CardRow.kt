package app.shoalwatch.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A horizontal row of cards that settles on a card edge when flicked, with the screen margin at the start and the next card
 * showing partly at the end, so it is plain that it scrolls. [key] must be unique within the row (an item id).
 */
@Composable
fun <T> CardRow(items: List<T>, key: (T) -> Any, modifier: Modifier = Modifier, card: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(state),
    ) {
        items(items, key = key) { card(it) }
    }
}
