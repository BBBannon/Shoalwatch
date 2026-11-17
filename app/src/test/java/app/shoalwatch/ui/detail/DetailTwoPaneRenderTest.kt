package app.shoalwatch.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.TABLET_LANDSCAPE
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.downloads.DownloadControls
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** On a landscape tablet the film page is two panes: the picture and Play on the left, everything else on the right. */
abstract class DetailTwoPaneRenderBase : RenderBase() {
    protected abstract val twoPane: Boolean
    private val snackbar = SnackbarHostState()
    private val plays = mutableListOf<StartFrom>()
    private val downloads = mutableListOf<String>()
    private val actions = DetailActions(
        onBack = {}, onRefresh = {}, onOpen = {}, onSignIn = {}, onReview = {}, onOpenServers = {}, onSetPlayed = {}, onSetFavourite = {},
        onPlay = { plays += it },
    )
    private val controls = DownloadControls(emptyMap(), onDownload = { downloads += it }, onRetry = {}, onOpenDownloads = {})

    private fun film(data: UserData? = userData()): ItemWithUserData =
        entry(7, name = "The Example Film", data = data).let { it.copy(item = it.item.copy(overview = "Slow and kind.", officialRating = "PG")) }

    private fun showFilm(data: UserData? = userData()) =
        show { DetailScreen(DetailUiState(DetailPhase.READY, BEN, film(data)), actions, emptyFlow(), snackbar, controls) }

    private fun leftOf(label: String): Float = rule.onAllNodes(hasText(label)).onFirst().performScrollTo().fetchSemanticsNode().boundsInRoot.left
    private val pane get() = with(rule.density) { 360.dp.toPx() }

    @Test fun thePictureAndPlayAreOnTheLeftAndTheOverviewOnTheRight() {
        showFilm()
        val overview = leftOf("Slow and kind.")
        val play = leftOf(text(R.string.detail_play))
        if (twoPane) {
            val picture = rule.onNodeWithTag(DETAIL_PICTURE_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("the picture in the left pane: $picture", picture.left < with(rule.density) { 400.dp.toPx() })
            assertTrue("Play in the left pane: $play", play < with(rule.density) { 400.dp.toPx() } && play < pane)
            assertTrue("the overview in the right pane: $overview", overview >= pane)
        } else {
            assertEquals("one column: the overview starts where Play does", play, overview, 1f)
        }
    }

    @Test fun resumeAndPlayFromStartWork() {
        showFilm(userData(positionMs = 23 * 60_000L, percent = 22f))
        rule.onAllNodes(hasText("Resume from 23 min")).onFirst().performScrollTo().performClick()
        rule.onAllNodes(hasText(text(R.string.detail_play_from_start))).onFirst().performScrollTo().performClick()
        assertEquals(listOf(StartFrom.RESUME, StartFrom.BEGINNING), plays)
        assertTrue(shown("Resume from 23 min · 22% watched"))
    }

    @Test fun theDownloadButtonIsThere() {
        showFilm()
        rule.onAllNodes(hasText(text(R.string.dl_download))).onFirst().performScrollTo().performClick()
        assertEquals(listOf(id(7)), downloads)
        if (twoPane) assertTrue(leftOf(text(R.string.dl_download)) < pane)
    }

    @Test fun eachPaneScrollsToItsEnd() {
        if (!twoPane) return
        showFilm()
        val right = hasScrollAction().and(hasAnyDescendant(hasText("Slow and kind.")))
        val left = hasScrollAction().and(hasAnyDescendant(hasText(text(R.string.detail_play))))
        assertEquals("the right pane scrolls on its own: ${tree()}", 1, rule.onAllNodes(right).fetchSemanticsNodes().size)
        assertEquals("the left pane scrolls on its own", 1, rule.onAllNodes(left).fetchSemanticsNodes().size)
        rule.onAllNodes(right).onFirst().performScrollToNode(hasText("Slow and kind."))
        rule.onAllNodes(left).onFirst().performScrollToNode(hasText(text(R.string.dl_download)))
    }
}

@Config(qualifiers = TABLET_LANDSCAPE) class DetailTwoPaneRenderTest : DetailTwoPaneRenderBase() { override val fontScale = 1f; override val twoPane = true }
@Config(qualifiers = TABLET_LANDSCAPE) class DetailTwoPaneLargeTextRenderTest : DetailTwoPaneRenderBase() { override val fontScale = 2f; override val twoPane = true }
@Config(qualifiers = TABLET) class DetailOnePaneTabletRenderTest : DetailTwoPaneRenderBase() { override val fontScale = 1f; override val twoPane = false }
