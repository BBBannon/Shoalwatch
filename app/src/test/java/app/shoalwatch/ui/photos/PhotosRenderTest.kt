package app.shoalwatch.ui.photos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.lists.ListsPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Milestone 11 Task 10: the photo viewer at phone and tablet size and at font scale 1.0 and 2.0. It says which photo of how many,
 * has a 48dp Close button, pages on a swipe, toggles zoom with a double tap (and offers the same as a TalkBack action), and says
 * "You're offline" with Try again when its read fails.
 */
abstract class PhotosRenderBase : RenderBase() {
    private val done = mutableListOf<String>()
    private val actions = PhotoViewerActions(onClose = { done += "close" }, onRetry = { done += "retry" }, onOpenServers = { done += "servers" })

    private fun photos(count: Int = 12): List<Item> = (1..count).map { item(it, ItemKind.PHOTO, "IMG $it") }

    private fun ready(startIndex: Int = 2, count: Int = 12) = PhotoViewerState(session = BEN, phase = ListsPhase.READY, photos = photos(count), startIndex = startIndex)

    @Test fun itSaysWhichPhotoOfHowManyAndOffersClose() {
        show { PhotoViewerScreen(ready(), actions) }
        assertDescribed(text(R.string.photo_cd, 3, 12, "IMG 3"))
        rule.onNodeWithContentDescription(text(R.string.photo_close)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf("close"), done)
    }

    @Test fun aSwipePagesOnToTheNextPhoto() {
        show { PhotoViewerScreen(ready(), actions) }
        rule.onNodeWithContentDescription(text(R.string.photo_cd, 3, 12, "IMG 3")).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertDescribed(text(R.string.photo_cd, 4, 12, "IMG 4"))
    }

    @Test fun doubleTapTogglesZoomAndTalkBackHasTheSameAction() {
        show { PhotoViewerScreen(ready(), actions) }
        val page = rule.onNodeWithContentDescription(text(R.string.photo_cd, 3, 12, "IMG 3"))
        fun label() = page.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)?.map { it.label }
        assertEquals(listOf(text(R.string.photo_zoom_in)), label())
        page.performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(listOf(text(R.string.photo_zoom_out)), label())
        page.performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(listOf(text(R.string.photo_zoom_in)), label())
    }

    @Test fun theFirstPhotoOpensToo() {
        show { PhotoViewerScreen(ready(startIndex = 0), actions) }
        assertDescribed(text(R.string.photo_cd, 1, 12, "IMG 1"))
    }

    @Test fun aSinglePhotoSaysOneOfOne() {
        show { PhotoViewerScreen(ready(startIndex = 0, count = 1), actions) }
        assertDescribed(text(R.string.photo_cd, 1, 1, "IMG 1"))
    }

    @Test fun aPhotoWithNoPictureStillShowsAndIsNamed() {
        val bare = PhotoViewerState(session = BEN, phase = ListsPhase.READY, photos = listOf(item(1, ItemKind.PHOTO, "IMG 1", art = false)))
        show { PhotoViewerScreen(bare, actions) }
        assertDescribed(text(R.string.photo_cd, 1, 1, "IMG 1"))
    }

    @Test fun offlineSaysSoWithTryAgainAndClose() {
        show { PhotoViewerScreen(PhotoViewerState(session = BEN, phase = ListsPhase.OFFLINE), actions) }
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        rule.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        assertEquals(listOf("retry", "close"), done)
    }

    @Test fun aPhotoThatIsGoneSaysSo() {
        show { PhotoViewerScreen(PhotoViewerState(session = BEN, phase = ListsPhase.GONE), actions) }
        assertShown(text(R.string.gone_title))
    }

    @Test fun aFailedReadOffersTryAgain() {
        show { PhotoViewerScreen(PhotoViewerState(session = BEN, phase = ListsPhase.FAILED), actions) }
        assertShown(text(R.string.lists_load_failed_title))
    }

    @Test fun signedOutSaysSoWithAWayToServers() {
        show { PhotoViewerScreen(PhotoViewerState(), actions) }
        assertShown(text(R.string.home_signed_out_title))
        assertTrue(done.isEmpty())
    }

    @Test fun loadingShowsOnlyTheCloseArrow() {
        show { PhotoViewerScreen(PhotoViewerState(session = BEN, phase = ListsPhase.LOADING), actions) }
        assertDescribed(text(R.string.cd_back))
    }

    @Test fun theGestureAreaIsAnImageForTalkBack() {
        show { PhotoViewerScreen(ready(), actions) }
        val image = rule.onNodeWithContentDescription(text(R.string.photo_cd, 3, 12, "IMG 3")).fetchSemanticsNode()
        assertTrue(image.config.getOrNull(SemanticsProperties.Role) != null)
    }
}

@Config(qualifiers = PHONE) class PhotosPhoneRenderTest : PhotosRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class PhotosPhoneLargeTextRenderTest : PhotosRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class PhotosTabletRenderTest : PhotosRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class PhotosTabletLargeTextRenderTest : PhotosRenderBase() { override val fontScale = 2f }
