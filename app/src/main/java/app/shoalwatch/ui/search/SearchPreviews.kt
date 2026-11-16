package app.shoalwatch.ui.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.previewEntry
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.shell.Tab
import app.shoalwatch.ui.theme.ShoalwatchTheme

/* Previews of Search in its main states (placeholders show where artwork would be). */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")

private val results = SearchUiState(
    phase = SearchPhase.RESULTS, session = session, query = "tide",
    films = listOf(previewEntry(1, "Tide of Dawn"), previewEntry(2, "High Tide")),
    series = listOf(previewEntry(3, "Tidal", ItemKind.SERIES)),
    episodes = listOf(previewEntry(4, "The Turning Tide", ItemKind.EPISODE, series = "Tidal", code = 2 to 5)),
)

private val actions = SearchActions({}, {}, {}, {}, {})

@Composable
private fun Screen(state: SearchUiState) = ShoalwatchTheme { AppShell(Tab.SEARCH, {}, SnackbarHostState()) { SearchScreen(state, actions) } }

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Search: results") @Composable
private fun ResultsPreview() = Screen(results)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Search: idle") @Composable
private fun IdlePreview() = Screen(SearchUiState(session = session))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Search: offline") @Composable
private fun OfflinePreview() = Screen(results.copy(offline = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Search: no results") @Composable
private fun EmptyPreview() = Screen(SearchUiState(SearchPhase.EMPTY, session, query = "zzz"))

@Preview(showBackground = true, widthDp = 840, heightDp = 900, name = "Search: tablet") @Composable
private fun TabletPreview() = Screen(results)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, name = "Search: large text") @Composable
private fun LargeTextPreview() = Screen(results)
