package app.shoalwatch.ui.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.previewEntry
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.flow.emptyFlow

/* Previews of the library grid in its main states (placeholders show where artwork would be). */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")

private val ready = LibraryGridUiState(
    phase = GridPhase.READY, session = session, title = "Films", kind = LibraryKind.MOVIES, total = 412, fresh = true,
    items = listOf(
        previewEntry(1, "Deep Blue", played = true), previewEntry(2, "Coral Road"), previewEntry(3, "Night Swim"),
        previewEntry(4, "The Harbour Keeper"), previewEntry(5, "Low Tide"), previewEntry(6, "Salt"),
        previewEntry(7, "A Very Long Title That Needs Two Lines"), previewEntry(8, "Reef"),
    ),
)

private val actions = LibraryGridActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })

@Composable
private fun Grid(state: LibraryGridUiState) = ShoalwatchTheme { LibraryGridScreen(state, actions, emptyFlow(), SnackbarHostState()) }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Grid") @Composable
private fun GridPreview() = Grid(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Grid: series with counts") @Composable
private fun GridSeriesPreview() = Grid(
    ready.copy(
        title = "TV Shows", kind = LibraryKind.TV, total = 12,
        items = listOf(previewEntry(1, "Tidal", ItemKind.SERIES, unplayed = 7), previewEntry(2, "Lighthouse", ItemKind.SERIES, played = true), previewEntry(3, "Seabirds", ItemKind.SERIES)),
    ),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Grid: loading") @Composable
private fun GridLoadingPreview() = Grid(LibraryGridUiState(phase = GridPhase.LOADING, session = session, title = "Films"))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Grid: offline") @Composable
private fun GridOfflinePreview() = Grid(ready.copy(banner = BannerKind.OFFLINE, fresh = false))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Grid: nothing matches") @Composable
private fun GridEmptyPreview() = Grid(ready.copy(items = emptyList(), filter = app.shoalwatch.data.library.ItemFilter(favouritesOnly = true)))

@Preview(showBackground = true, widthDp = 840, heightDp = 900, name = "Grid: tablet") @Composable
private fun GridTabletPreview() = Grid(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, name = "Grid: large text") @Composable
private fun GridLargeTextPreview() = Grid(ready)
