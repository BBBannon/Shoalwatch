package app.shoalwatch.ui.browse

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.ui.components.LocalArtworkLoader
import app.shoalwatch.ui.theme.ShoalwatchTheme
import coil3.ImageLoader
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Shared set-up for the screen render tests (Robolectric + Compose test). A concrete subclass fixes the screen size with
 * `@Config(qualifiers = ...)` and the text size with [fontScale]; the tests are written once in an abstract class and run for
 * each of: phone (360x640dp) and tablet (840x1200dp), at font scale 1.0 and 2.0. Artwork uses a plain loader that cannot
 * fetch, so pictures show their placeholders.
 */
@RunWith(RobolectricTestRunner::class)
abstract class RenderBase {
    @get:Rule val rule = createComposeRule()

    protected abstract val fontScale: Float
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    protected fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    protected fun show(content: @Composable () -> Unit) = rule.setContent {
        val ctx = LocalContext.current
        val loader = remember { ImageLoader.Builder(ctx).build() }
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, fontScale),
            LocalArtworkLoader provides loader,
        ) { ShoalwatchTheme(content = content) }
    }

    protected fun shown(label: String) = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    protected fun described(label: String) = rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    protected fun describedStarting(prefix: String) =
        rule.onAllNodes(SemanticsMatcher("starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
        }).fetchSemanticsNodes().isNotEmpty()

    /** The semantics tree as text, attached to a failed assertion so the failure says what was on screen. */
    protected fun tree(): String = rule.onAllNodes(isRoot()).printToString(maxDepth = 12).take(7000)

    protected fun assertShown(label: String) = assertTrue("not shown: $label\n${tree()}", shown(label))
    protected fun assertNotShown(label: String) = assertTrue("unexpectedly shown: $label\n${tree()}", !shown(label))
    protected fun assertDescribed(label: String) = assertTrue("no element described: $label\n${tree()}", described(label))

    /** Scrolls the page's list until a node matching [matcher] has been composed (a lazy list only composes what is near the screen). */
    protected fun scrollTo(matcher: SemanticsMatcher) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
    }

    protected fun headings(): Int = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size
}

const val PHONE = "w360dp-h640dp"
const val TABLET = "w840dp-h1200dp"

