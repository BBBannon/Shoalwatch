package app.shoalwatch.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Milestone 11 Task 8: the person, filtered list, genres and studios routes are registered in the app's own graph function, an id
 * arrives as given and an absent genre or studio arrives as null (never an empty id).
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val arrived = mutableListOf<String>()
    private val library = "a".repeat(32)
    private val genre = "b".repeat(32)

    @Before fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") { Text("start") }
                browsingDestinations(
                    library = {}, series = { _, _, _ -> }, item = {}, player = { _, _, _, _ -> }, downloadedSeries = {},
                    person = { arrived += "person:$it" },
                    browse = { parent, g, s -> arrived += "browse:$parent|$g|$s" },
                    genres = { arrived += "genres:$it" },
                    studios = { arrived += "studios:$it" },
                )
            }
        }
    }

    private fun go(route: String): String {
        arrived.clear()
        rule.runOnUiThread { nav.navigate(route) }
        rule.waitForIdle()
        return arrived.last()
    }

    @Test fun aPersonOpensWithTheirId() = assertEquals("person:$genre", go(Routes.person(genre)))

    @Test fun theFilteredListOpensWithNoFilter() = assertEquals("browse:$library|null|null", go(Routes.browse(library)))

    @Test fun theFilteredListOpensAtAGenreOrAStudio() {
        assertEquals("browse:$library|$genre|null", go(Routes.browse(library, genreId = genre)))
        assertEquals("browse:$library|null|$genre", go(Routes.browse(library, studioId = genre)))
    }

    @Test fun aGenreWithNoLibraryOpensAcrossAllOfThem() = assertEquals("browse:null|$genre|null", go(Routes.browse(null, genreId = genre)))

    @Test fun genresAndStudiosOpenForALibrary() {
        assertEquals("genres:$library", go(Routes.genres(library)))
        assertEquals("studios:$library", go(Routes.studios(library)))
    }
}
