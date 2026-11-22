package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.TextFieldState
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.BulkAction
import app.shoalwatch.ui.browse.BulkHost
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.BulkUiState
import app.shoalwatch.ui.browse.Selection
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.selectionControls
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 11 Task 7: Add to playlist and Add to collection under the bar's More options. */
class SelectionListsTest {
    private val opened = mutableListOf<Pair<List<String>, ListKind>>()
    private val applied = mutableListOf<BulkAction>()
    private val listed = (1..3).associate { id(it) to BulkItem(id(it), "Film $it", ItemKind.MOVIE) }

    private fun host(withPicker: Boolean) = BulkHost(
        BulkUiState(session = BEN), emptyFlow(), { _, a -> applied += a }, {}, {}, {}, {},
        picker = if (withPicker) {
            ListPickerHost(
                PickerUiState(), emptyFlow(), TextFieldState(), { items, kind -> opened += items.map { it.id } to kind }, {}, {}, {}, {}, {}, {},
            )
        } else null,
    )

    private fun selected(vararg n: Int) = Selection.Saver.restore(n.map { id(it) })!!

    @Test fun bothAreOfferedWhereThereIsAPicker() {
        val controls = selectionControls(selected(1, 2), host(true), listed, BulkAction.forBrowsing, onMore = null)
        assertTrue(BulkAction.ADD_TO_PLAYLIST in controls.actions)
        assertTrue(BulkAction.ADD_TO_COLLECTION in controls.actions)
    }

    @Test fun neitherIsOfferedWithoutAPicker() {
        val controls = selectionControls(selected(1), host(false), listed, BulkAction.forBrowsing, onMore = null)
        assertFalse(BulkAction.ADD_TO_PLAYLIST in controls.actions)
        assertFalse(BulkAction.ADD_TO_COLLECTION in controls.actions)
    }

    @Test fun choosingOneOpensThePickerOnThePickedItemsInOrderAndClearsTheSelection() {
        val selection = selected(2, 1)
        val controls = selectionControls(selection, host(true), listed, BulkAction.forBrowsing, onMore = null)
        controls.onAction(BulkAction.ADD_TO_PLAYLIST)
        assertFalse(selection.active)
        val again = selected(2, 1)
        selectionControls(again, host(true), listed, BulkAction.forBrowsing, onMore = null).onAction(BulkAction.ADD_TO_COLLECTION)
        assertEquals(listOf(listOf(id(2), id(1)) to ListKind.PLAYLIST, listOf(id(2), id(1)) to ListKind.COLLECTION), opened)
        assertTrue(applied.isEmpty())
        assertFalse(selection.active)
    }
}
