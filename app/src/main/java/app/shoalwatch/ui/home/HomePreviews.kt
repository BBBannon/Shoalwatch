package app.shoalwatch.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.shell.Tab
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.flow.emptyFlow

/* Previews of Home in its main states, with sample data only (no artwork loader exists in a preview, so placeholders show). */

private val id32 = "0".repeat(31)

private fun sample(n: Int, name: String, kind: ItemKind = ItemKind.MOVIE, data: UserData? = null, series: String? = null, code: Pair<Int, Int>? = null) =
    ItemWithUserData(
        Item(
            id = id32 + n.toString(16), kind = kind, name = name, sortName = null, parentId = null, seriesId = series?.let { id32 + "9" },
            seriesName = series, seasonId = null, indexNumber = code?.second, parentIndexNumber = code?.first, overview = null,
            runtimeMs = 6_000_000, year = 2019, premiereDate = null, communityRating = null, officialRating = null, childCount = null,
            images = ItemImages(null, null, null, null),
        ),
        data,
    )

private fun data(percent: Float? = null, played: Boolean = false, unplayed: Int? = null) =
    UserData(played, if (percent != null) 3_000_000 else 0, percent, if (played) 1 else 0, false, null, unplayed)

private val views = listOf(LibraryView(id32 + "1", "Films", LibraryKind.MOVIES), LibraryView(id32 + "2", "TV Shows", LibraryKind.TV))

private val ready = HomeUiState(
    phase = HomePhase.READY, session = Session(id32 + "a", id32 + "b"), serverId = "srv", serverName = "Den", userName = "ben",
    connection = ConnectionView(ConnectionView.Kind.HOME), views = views,
    resume = RowUi(
        listOf(
            sample(3, "The Turning Tide", ItemKind.EPISODE, data(42f), series = "Tidal", code = 2 to 5),
            sample(4, "Harbour Lights", data = data(70f)),
        ),
    ),
    nextUp = RowUi(listOf(sample(5, "Second Wave", ItemKind.EPISODE, series = "Tidal", code = 1 to 2))),
    latest = listOf(
        LatestUi(views[0], RowUi(listOf(sample(6, "Deep Blue", data = data(played = true)), sample(7, "Coral Road"), sample(8, "Night Swim")))),
        LatestUi(views[1], RowUi(listOf(sample(9, "Tidal", ItemKind.SERIES, data(unplayed = 7)), sample(10, "Lighthouse", ItemKind.SERIES)))),
    ),
)

private val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })

@Composable
private fun Preview(state: HomeUiState) = ShoalwatchTheme {
    val snackbar = SnackbarHostState()
    AppShell(Tab.HOME, {}, snackbar) { HomeScreen(state, actions, emptyFlow(), snackbar) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Home") @Composable
private fun HomePreview() = Preview(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Home: loading") @Composable
private fun HomeLoadingPreview() = Preview(HomeUiState(phase = HomePhase.LOADING, serverName = "Den"))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Home: offline") @Composable
private fun HomeOfflinePreview() = Preview(ready.copy(topBanner = BannerKind.OFFLINE, connection = ConnectionView(ConnectionView.Kind.OFFLINE)))

@Preview(showBackground = true, widthDp = 360, heightDp = 720, name = "Home: first run, empty") @Composable
private fun HomeEmptyPreview() = Preview(ready.copy(views = emptyList(), resume = RowUi(), nextUp = RowUi(), latest = emptyList()))

@Preview(showBackground = true, widthDp = 840, heightDp = 900, name = "Home: tablet") @Composable
private fun HomeTabletPreview() = Preview(ready)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, name = "Home: large text") @Composable
private fun HomeLargeTextPreview() = Preview(ready)
