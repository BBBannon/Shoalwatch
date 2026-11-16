package app.shoalwatch.ui.series

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.previewEntry
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.flow.emptyFlow

/* Previews of the series screen in its main states (placeholders show where artwork would be). */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")

private fun season(n: Int, number: Int) =
    previewEntry(100 + n, "Season $number", ItemKind.SEASON).let { it.copy(item = it.item.copy(indexNumber = number)) }

private val ready = SeriesUiState(
    phase = SeriesPhase.READY, session = session,
    series = previewEntry(
        50, "Tidal", ItemKind.SERIES, unplayed = 5,
        overview = "A small crew follows the tide along a coast that is not on any chart, and finds that every harbour remembers them.",
    ),
    seasons = listOf(season(1, 1), season(2, 2), season(3, 0)), selectedSeasonId = "0".repeat(31) + "6a",
    episodes = listOf(
        previewEntry(1, "The Beginning", ItemKind.EPISODE, played = true, series = "Tidal", code = 1 to 1, overview = "The crew leaves port."),
        previewEntry(2, "The Turning Tide", ItemKind.EPISODE, series = "Tidal", code = 1 to 2, percent = 40f, overview = "A storm decides the route."),
        previewEntry(3, "Harbour Lights", ItemKind.EPISODE, series = "Tidal", code = 1 to 3, overview = "Somewhere to rest, at a price."),
    ),
    highlightEpisodeId = "0".repeat(31) + "2",
)

private val actions = SeriesActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> })

@Composable
private fun Screen(state: SeriesUiState) = ShoalwatchTheme { SeriesScreen(state, actions, emptyFlow(), SnackbarHostState()) }

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Series") @Composable
private fun SeriesPreview() = Screen(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Series: loading") @Composable
private fun SeriesLoadingPreview() = Screen(SeriesUiState(phase = SeriesPhase.LOADING, session = session, refreshing = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Series: offline") @Composable
private fun SeriesOfflinePreview() = Screen(ready.copy(banner = BannerKind.OFFLINE))

@Preview(showBackground = true, widthDp = 840, heightDp = 900, name = "Series: tablet") @Composable
private fun SeriesTabletPreview() = Screen(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 760, fontScale = 2f, name = "Series: large text") @Composable
private fun SeriesLargeTextPreview() = Screen(ready)
