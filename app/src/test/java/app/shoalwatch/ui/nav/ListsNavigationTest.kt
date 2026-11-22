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
 * Milestone 11 Task 7: the Playlists and Playlist routes are registered in the app's own graph function, so navigating to them no
 * longer crashes, a playlist's id arrives as given, and Play and Shuffle reach the player with the queue's token.
 */
@RunWith(RobolectricTestRunner::class)
class ListsNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val arrived = mutableListOf<String>()
    private val playlist = "d".repeat(32)

    @Before fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") { Text("start") }
                browsingDestinations(
                    library = {}, series = { _, _, _ -> }, item = {},
                    player = { id, from, queue, source -> arrived += "play:$id|$from|$queue|$source" },
                    downloadedSeries = {},
                    playlists = { arrived += "playlists" },
                    playlist = { id -> arrived += "playlist:$id" },
                )
            }
        }
    }

    private fun go(block: () -> Unit): String {
        arrived.clear()
        rule.runOnUiThread { block() }
        rule.waitForIdle()
        return arrived.last()
    }

    @Test fun thePlaylistsScreenIsReachable() {
        assertEquals("playlists", go { nav.navigate(Routes.PLAYLISTS) })
    }

    @Test fun aPlaylistOpensFromItsDestinationWithItsId() {
        assertEquals("playlist:$playlist", go { nav.open(Destination.Playlist(playlist)) })
    }

    @Test fun playCarriesTheQueueTokenToThePlayer() {
        val token = "AbCdEfGhIjKlMnOpQrStUv"
        assertEquals("play:${"1".repeat(32)}|BEGINNING|$token|null", go { nav.navigate(Routes.player("1".repeat(32), StartFrom.BEGINNING, token)) })
    }
}
