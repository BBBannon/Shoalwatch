package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.shoalwatch.data.playback.StartFrom

/**
 * The library, series and item destinations: their routes, argument names, and how an argument that is not there arrives (as
 * null, never as an empty id). The screens are passed in so a test can drive this exact graph with recorders.
 */
internal fun NavGraphBuilder.browsingDestinations(
    library: @Composable (viewId: String) -> Unit,
    series: @Composable (seriesId: String, seasonId: String?, episodeId: String?) -> Unit,
    item: @Composable (itemId: String) -> Unit,
    player: @Composable (itemId: String, from: StartFrom, queueToken: String?, sourceId: String?) -> Unit,
    downloadedSeries: @Composable (seriesId: String) -> Unit,
    storage: @Composable () -> Unit = {},
    playlists: @Composable () -> Unit = {},
    playlist: @Composable (playlistId: String) -> Unit = {},
    person: @Composable (personId: String) -> Unit = {},
    browse: @Composable (parentId: String?, genreId: String?, studioId: String?) -> Unit = { _, _, _ -> },
    genres: @Composable (parentId: String) -> Unit = {},
    studios: @Composable (parentId: String) -> Unit = {},
    photo: @Composable (photoId: String, parentId: String?) -> Unit = { _, _ -> },
) {
    composable(Routes.STORAGE) { storage() }
    composable(Routes.PLAYLISTS) { playlists() }
    composable(Routes.PLAYLIST, listOf(navArgument(Routes.ARG_PLAYLIST) { type = NavType.StringType })) {
        playlist(it.arguments?.getString(Routes.ARG_PLAYLIST).orEmpty())
    }
    composable(
        Routes.PHOTO,
        listOf(
            navArgument(Routes.ARG_ITEM) { type = NavType.StringType },
            navArgument(Routes.ARG_PARENT) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        photo(it.arguments?.getString(Routes.ARG_ITEM).orEmpty(), it.arguments?.getString(Routes.ARG_PARENT)?.ifEmpty { null })
    }
    composable(Routes.PERSON, listOf(navArgument(Routes.ARG_PERSON) { type = NavType.StringType })) {
        person(it.arguments?.getString(Routes.ARG_PERSON).orEmpty())
    }
    composable(
        Routes.BROWSE,
        listOf(
            navArgument(Routes.ARG_PARENT) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_GENRE) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_STUDIO) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val args = it.arguments
        browse(args?.getString(Routes.ARG_PARENT)?.ifEmpty { null }, args?.getString(Routes.ARG_GENRE)?.ifEmpty { null }, args?.getString(Routes.ARG_STUDIO)?.ifEmpty { null })
    }
    composable(Routes.GENRES, listOf(navArgument(Routes.ARG_PARENT) { type = NavType.StringType })) { genres(it.arguments?.getString(Routes.ARG_PARENT).orEmpty()) }
    composable(Routes.STUDIOS, listOf(navArgument(Routes.ARG_PARENT) { type = NavType.StringType })) { studios(it.arguments?.getString(Routes.ARG_PARENT).orEmpty()) }
    composable(Routes.DOWNLOADED_SERIES, listOf(navArgument(Routes.ARG_SERIES) { type = NavType.StringType })) {
        downloadedSeries(it.arguments?.getString(Routes.ARG_SERIES).orEmpty())
    }
    composable(Routes.LIBRARY, listOf(navArgument(Routes.ARG_VIEW) { type = NavType.StringType })) {
        library(it.arguments?.getString(Routes.ARG_VIEW).orEmpty())
    }
    composable(
        Routes.SERIES,
        listOf(
            navArgument(Routes.ARG_SERIES) { type = NavType.StringType },
            navArgument(Routes.ARG_SEASON) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_EPISODE) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val args = it.arguments
        series(
            args?.getString(Routes.ARG_SERIES).orEmpty(),
            args?.getString(Routes.ARG_SEASON)?.ifEmpty { null },
            args?.getString(Routes.ARG_EPISODE)?.ifEmpty { null },
        )
    }
    composable(Routes.ITEM, listOf(navArgument(Routes.ARG_ITEM) { type = NavType.StringType })) {
        item(it.arguments?.getString(Routes.ARG_ITEM).orEmpty())
    }
    composable(
        Routes.PLAYER,
        listOf(
            navArgument(Routes.ARG_ITEM) { type = NavType.StringType },
            navArgument(Routes.ARG_FROM) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_QUEUE) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_SOURCE) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val from = if (it.arguments?.getString(Routes.ARG_FROM) == Routes.FROM_RESUME) StartFrom.RESUME else StartFrom.BEGINNING
        player(it.arguments?.getString(Routes.ARG_ITEM).orEmpty(), from, it.arguments?.getString(Routes.ARG_QUEUE), it.arguments?.getString(Routes.ARG_SOURCE))
    }
}
