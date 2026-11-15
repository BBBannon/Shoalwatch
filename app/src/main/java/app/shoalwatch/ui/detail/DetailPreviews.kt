package app.shoalwatch.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.previewEntry
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.flow.emptyFlow

/* Previews of the detail page in its main states (placeholders show where artwork would be). */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")

private val film = previewEntry(
    7, "The Example Film", percent = 40f,
    overview = "A lighthouse keeper receives a letter from a ship that sank forty years ago.\n\nSlow, quiet and kind.",
)

private val episode = previewEntry(8, "The Turning Tide", ItemKind.EPISODE, series = "Tidal", code = 2 to 5, overview = "A storm decides the route.")

private val actions = DetailActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun Screen(state: DetailUiState) = ShoalwatchTheme { DetailScreen(state, actions, emptyFlow(), SnackbarHostState()) }

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Detail: film") @Composable
private fun FilmPreview() = Screen(DetailUiState(DetailPhase.READY, session, film))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Detail: episode") @Composable
private fun EpisodePreview() = Screen(DetailUiState(DetailPhase.READY, session, episode))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Detail: loading") @Composable
private fun LoadingPreview() = Screen(DetailUiState(DetailPhase.LOADING, session, refreshing = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Detail: gone") @Composable
private fun GonePreview() = Screen(DetailUiState(DetailPhase.GONE, session))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Detail: offline") @Composable
private fun OfflinePreview() = Screen(DetailUiState(DetailPhase.READY, session, film, banner = BannerKind.OFFLINE))

@Preview(showBackground = true, widthDp = 840, heightDp = 900, name = "Detail: tablet") @Composable
private fun TabletPreview() = Screen(DetailUiState(DetailPhase.READY, session, film))

@Preview(showBackground = true, widthDp = 360, heightDp = 760, fontScale = 2f, name = "Detail: large text") @Composable
private fun LargeTextPreview() = Screen(DetailUiState(DetailPhase.READY, session, film))
