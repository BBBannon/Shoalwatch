package app.shoalwatch.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.shoalwatch.ui.discover.TapOnce
import app.shoalwatch.ui.present.Destination
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Milestone 11 final fix wave (T8 review): a double tap on a Search title or collection row opens one page, and Clear filters on
 * the all-libraries list (a genre from Search) goes back to Search instead of listing the whole server.
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverRouteActionsTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val library = "a".repeat(32)
    private val genre = "b".repeat(32)
    private val film = "c".repeat(32)

    @Before fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.SEARCH) {
                composable(Routes.SEARCH) { Text("search") }
                browsingDestinations(library = {}, series = { _, _, _ -> }, item = {}, player = { _, _, _, _ -> }, downloadedSeries = {})
            }
        }
    }

    private fun onUi(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun stack(): List<String?> {
        @android.annotation.SuppressLint("RestrictedApi")
        val entries = nav.currentBackStack.value.filter { it.destination !is androidx.navigation.NavGraph }
        return entries.map { it.destination.route }
    }

    @Test fun aDoubleTapOnASearchRowOpensOnePage() {
        val tap = TapOnce()
        onUi {
            val open = nav.openOnce(tap)
            open(Destination.Detail(film))
            open(Destination.Detail(film))
        }
        assertEquals(listOf(Routes.SEARCH, Routes.ITEM), stack())
        tap.reset() // the page came back on top
        onUi { nav.popBackStack(); nav.openOnce(tap)(Destination.Detail(film)) }
        assertEquals(listOf(Routes.SEARCH, Routes.ITEM), stack())
    }

    @Test fun clearFiltersOnTheAllLibrariesListGoesBackToSearch() {
        var clearedInPlace = 0
        onUi { nav.navigate(Routes.browse(null, genreId = genre)) }
        assertEquals(listOf(Routes.SEARCH, Routes.BROWSE), stack())
        onUi { nav.clearBrowseFilters(null) { clearedInPlace++ } }
        assertEquals(listOf(Routes.SEARCH), stack())
        assertEquals(0, clearedInPlace)
    }

    @Test fun clearFiltersOnALibrarysListGoesBackToTheLibraryOrClearsInPlace() {
        var clearedInPlace = 0
        onUi { nav.navigate(Routes.library(library)); nav.navigate(Routes.browse(library, genreId = genre)) }
        onUi { nav.clearBrowseFilters(library) { clearedInPlace++ } }
        assertEquals(listOf(Routes.SEARCH, Routes.LIBRARY), stack())
        onUi { nav.popBackStack(); nav.navigate(Routes.browse(library)) }
        onUi { nav.clearBrowseFilters(library) { clearedInPlace++ } }
        assertEquals("no library page beneath: cleared in place", listOf(Routes.SEARCH, Routes.BROWSE), stack())
        assertEquals(1, clearedInPlace)
    }
}
