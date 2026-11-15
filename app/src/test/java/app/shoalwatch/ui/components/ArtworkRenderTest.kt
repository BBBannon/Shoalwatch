package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.images.ArtworkTestBase
import app.shoalwatch.ui.theme.ShoalwatchTheme
import coil3.SingletonImageLoader
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

/** `Artwork` composes at each aspect with and without a ref, keeps its shape, exposes the description and hides the placeholder letter. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkRenderTest : ArtworkTestBase() {
    @get:Rule val compose = createComposeRule()

    @Before fun useTheTestLoader() {
        selector.answer = { null } // offline: a ref's picture cannot load, which is the placeholder path
        SingletonImageLoader.setUnsafe(loader)
    }

    private class Shown(val ref: app.shoalwatch.data.images.ArtworkRef?, val aspect: ArtworkAspect, val description: String?)

    private val shown = mutableStateOf(Shown(null, ArtworkAspect.POSTER, null))
    private var started = false

    /** A test may call `setContent` once, so later calls change what the one composition shows. */
    private fun show(ref: app.shoalwatch.data.images.ArtworkRef?, aspect: ArtworkAspect, description: String? = "Poster of Alien") {
        shown.value = Shown(ref, aspect, description)
        if (!started) {
            started = true
            compose.setContent {
                val current = shown.value
                ShoalwatchTheme { Artwork(current.ref, current.description, Modifier.width(120.dp), current.aspect, title = "Alien") }
            }
        }
        compose.waitForIdle()
    }

    @Test fun everyAspectKeepsItsShapeWithAndWithoutARef() {
        for (aspect in ArtworkAspect.entries) {
            for (ref in listOf(null, ref())) {
                show(ref, aspect)
                compose.onNodeWithContentDescription("Poster of Alien").assertIsDisplayed()
                    .assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo((120f / aspect.ratio).dp)
            }
        }
    }

    @Test fun theDescriptionIsExposedAndThePlaceholderLetterIsNotAnnouncedSeparately() {
        show(ref(), ArtworkAspect.POSTER)
        compose.onNodeWithContentDescription("Poster of Alien").assertExists()
        compose.onAllNodesWithText("A").assertCountEquals(0)
        show(null, ArtworkAspect.WIDE)
        compose.onNodeWithContentDescription("Poster of Alien").assertExists()
        compose.onAllNodesWithText("A").assertCountEquals(0)
    }

    @Test fun aDecorativePictureHasNoDescriptionAtAll() {
        show(ref(), ArtworkAspect.SQUARE, description = null)
        compose.onAllNodes(hasContentDescription("Poster of Alien")).assertCountEquals(0)
        show(null, ArtworkAspect.SQUARE, description = null)
        compose.onAllNodes(hasContentDescription("Poster of Alien")).assertCountEquals(0)
    }

    @Test fun aLoadedPictureStillExposesItsDescription() {
        selector.answer = { online() }
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        show(ref(), ArtworkAspect.POSTER)
        val deadline = System.currentTimeMillis() + 5_000
        while (web.requestCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Poster of Alien").assertExists()
        assertTrue(web.requestCount >= 1)
    }
}
