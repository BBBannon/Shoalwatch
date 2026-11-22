package app.shoalwatch.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.destination
import app.shoalwatch.ui.present.detailsDestination
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Milestone 11 Task 10: the photo route is registered. A photo tile in a grid and a photo in a playlist both arrive at the viewer
 * with the photo and (when there is one) its folder; a playlist entry with no folder arrives with a null parent, never an empty id.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val arrived = mutableListOf<String>()

    @Before fun setUp() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") { Text("start") }
                browsingDestinations(
                    library = {}, series = { _, _, _ -> }, item = {}, player = { _, _, _, _ -> }, downloadedSeries = {},
                    photo = { photo, parent -> arrived += "photo:$photo|$parent" },
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

    @Test fun aPhotoOpensTheViewerWithItsFolder() {
        assertEquals("photo:${id(1)}|${id(2)}", open(Destination.Photo(id(1), id(2))))
    }

    @Test fun aPhotoFromAPlaylistWithNoFolderArrivesWithANullParent() {
        assertEquals("photo:${id(1)}|null", open(Destination.Photo(id(1), null)))
    }

    @Test fun aPhotoTileAndItsDetailsBothGoToTheViewer() {
        val photo = ItemWithUserData(item(1, ItemKind.PHOTO).copy(parentId = id(2)), null)
        assertEquals(Destination.Photo(id(1), id(2)), photo.destination())
        assertEquals(Destination.Photo(id(1), id(2)), photo.detailsDestination())
    }

    @Test fun otherDetailsAreUnchanged() {
        assertEquals(Destination.Detail(id(3)), ItemWithUserData(item(3, ItemKind.MOVIE), null).detailsDestination())
        assertEquals(Destination.Detail(id(3)), ItemWithUserData(item(3, ItemKind.VIDEO), null).detailsDestination())
    }
}
