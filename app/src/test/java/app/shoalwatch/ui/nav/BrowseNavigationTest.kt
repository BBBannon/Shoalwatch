package app.shoalwatch.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.present.Destination
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the REAL navigation: the app's own `Routes`, `open(destination)` and the real library, series and item destinations
 * (`browsingDestinations`, the same function the app's graph is built from). Only the screens are swapped for recorders, so what
 * arrives is exactly what a real screen would be handed. Guards the bug where a series with no season opened as "no longer
 * available" because an empty `season=` reached the library as a (malformed) id.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val arrived = mutableListOf<String>()

    private val series = "a".repeat(32)
    private val season = "b".repeat(32)
    private val episode = "c".repeat(32)

    @Before fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") { Text("start") }
                browsingDestinations(
                    library = { viewId -> arrived += "library:$viewId" },
                    series = { id, s, e -> arrived += "series:$id|$s|$e" },
                    item = { id -> arrived += "item:$id" },
                    player = { id, from -> arrived += "play:$id|$from" },
                    downloadedSeries = { id -> arrived += "downloaded:$id" },
                )
            }
        }
    }

    private fun open(destination: Destination): String {
        arrived.clear()
        rule.runOnUiThread { nav.open(destination) }
        rule.waitForIdle()
        return arrived.last()
    }

    @Test fun aSeriesWithNoSeasonAndNoEpisodeArrivesWithBothNull() {
        assertEquals("series:$series|null|null", open(Destination.Series(series)))
    }

    @Test fun aSeriesAtASeasonArrivesWithThatSeasonAndNoEpisode() {
        assertEquals("series:$series|$season|null", open(Destination.Series(series, season)))
    }

    @Test fun aSeriesWithAnEpisodeButNoSeasonArrivesWithTheEpisodeOnly() {
        assertEquals("series:$series|null|$episode", open(Destination.Series(series, null, episode)))
    }

    @Test fun aSeriesWithBothArrivesWithBoth() {
        assertEquals("series:$series|$season|$episode", open(Destination.Series(series, season, episode)))
    }

    @Test fun emptyStringsAreTreatedAsAbsent() {
        assertEquals("series:$series|null|null", open(Destination.Series(series, "", "")))
    }

    @Test fun aDownloadedSeriesArrivesWithItsIdAndAMalformedOneArrivesAsItIs() {
        arrived.clear()
        rule.runOnUiThread { nav.navigate(Routes.downloadedSeries(series)) }
        rule.waitForIdle()
        assertEquals("downloaded:$series", arrived.last())
        arrived.clear()
        rule.runOnUiThread { nav.navigate(Routes.downloadedSeries("../not an id")) }
        rule.waitForIdle()
        assertEquals("the screen gets it and the seam answers it as empty (no longer available)", "downloaded:../not an id", arrived.last())
    }

    @Test fun anItemArrivesWithItsId() {
        assertEquals("item:$episode", open(Destination.Detail(episode)))
    }

    @Test fun aLibraryArrivesWithItsId() {
        assertEquals("library:$season", open(Destination.Library(season)))
    }

    @Test fun theRouteLeavesOutWhatIsAbsentInsteadOfSendingEmptyValues() {
        assertEquals("series/$series", Routes.series(series))
        assertEquals("series/$series", Routes.series(series, "", ""))
        assertEquals("series/$series?season=$season", Routes.series(series, season))
        assertEquals("series/$series?episode=$episode", Routes.series(series, null, episode))
        assertEquals("series/$series?season=$season&episode=$episode", Routes.series(series, season, episode))
    }

    private fun go(route: String): String {
        arrived.clear()
        rule.runOnUiThread { nav.navigate(route) }
        rule.waitForIdle()
        return arrived.last()
    }

    @Test fun thePlayerArrivesWithItsIdAndWhereToStart() {
        assertEquals("play/$episode?from=resume", Routes.player(episode, StartFrom.RESUME))
        assertEquals("play/$episode?from=start", Routes.player(episode, StartFrom.BEGINNING))
        assertEquals("play:$episode|RESUME", go(Routes.player(episode, StartFrom.RESUME)))
        assertEquals("play:$episode|BEGINNING", go(Routes.player(episode, StartFrom.BEGINNING)))
    }

    @Test fun aFromThatIsNeitherArrivesAsTheStart() {
        assertEquals("play:$episode|BEGINNING", go("play/$episode?from=sideways"))
        assertEquals("play:$episode|BEGINNING", go("play/$episode"))
        assertEquals("play:$episode|BEGINNING", go("play/$episode?from=RESUME"))
    }

    @Test fun anOddIdIsEncodedIntoThePath() {
        assertEquals("play:a/b?c|RESUME", go(Routes.player("a/b?c", StartFrom.RESUME)))
    }
}
