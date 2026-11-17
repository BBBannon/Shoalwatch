package app.shoalwatch.ui.series

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.TABLET_LANDSCAPE
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** On a landscape tablet (900dp and wider) the series page is two panes: the series on the left, the episodes on the right. */
abstract class SeriesTwoPaneRenderBase : RenderBase() {
    protected abstract val twoPane: Boolean
    private val snackbar = SnackbarHostState()
    private val actions = picking {}

    private fun picking(onPick: (String) -> Unit) = SeriesActions(
        onBack = {}, onPickSeason = onPick, onRefresh = {}, onOpen = {}, onSignIn = {}, onReview = {}, onOpenServers = {},
        onSetSeriesPlayed = {}, onSetSeriesFavourite = {}, onSetSeasonPlayed = { _, _ -> }, onSetPlayed = { _, _ -> }, onSetFavourite = { _, _ -> },
    )

    private fun season(n: Int, number: Int): ItemWithUserData =
        entry(60 + n, ItemKind.SEASON, "Season $number").let { it.copy(item = it.item.copy(indexNumber = number, seriesId = id(50))) }

    private val tidal = entry(50, ItemKind.SERIES, "Tidal", data = userData(unplayed = 5))
        .let { it.copy(item = it.item.copy(overview = "A crew follows the tide.", officialRating = "TV-14")) }

    private val many = (1..20).map { episode(it, 1, it, name = "Ep $it") }

    private val ready = SeriesUiState(
        phase = SeriesPhase.READY, session = BEN, series = tidal, seasons = listOf(season(1, 1), season(2, 2)), episodes = many,
        selectedSeasonId = id(61),
    )

    private fun rowOf(n: Int) = SemanticsMatcher("episode row $n") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Episode $n, Ep $n") } == true
    }

    @Test fun eachPaneHasItsHeadingOnlyInTwoPanes() {
        show { SeriesScreen(ready, actions, emptyFlow(), snackbar) }
        for (label in listOf(text(R.string.pane_series_about), text(R.string.pane_series_episodes))) {
            if (twoPane) {
                assertShown(label)
                val node = rule.onAllNodes(hasText(label)).onFirst().fetchSemanticsNode()
                assertTrue("$label is a heading", node.config.getOrNull(SemanticsProperties.Heading) != null)
            } else {
                assertNotShown(label)
            }
        }
    }

    @Test fun choosingASeasonChangesOnlyTheRightPane() {
        if (!twoPane) return
        val state = mutableStateOf(ready)
        show { SeriesScreen(state.value, actions, emptyFlow(), snackbar) }
        val overview = { rule.onAllNodes(hasText("A crew follows the tide.")).onFirst().fetchSemanticsNode().boundsInRoot }
        val before = overview()
        assertTrue("the overview is in the left pane: $before", before.right <= with(rule.density) { 400.dp.toPx() } + 1f)
        state.value = ready.copy(selectedSeasonId = id(62), episodes = listOf(episode(31, 2, 1, name = "Second")))
        rule.waitForIdle()
        assertEquals(before, overview())
        assertTrue(describedStarting("Episode 1, Second"))
    }

    /** Review M6 of Tasks 9-10: the chips are in the other pane, so a new season starts the episode list at its top. */
    @Test fun choosingASeasonStartsTheEpisodesAtTheTop() {
        if (!twoPane) return
        val state = mutableStateOf(ready)
        val pick = picking { state.value = ready.copy(selectedSeasonId = it, episodes = secondSeason) } // the ViewModel's answer
        show { SeriesScreen(state.value, pick, emptyFlow(), snackbar) }
        rule.onNode(episodesList).performScrollToNode(rowOf(18))
        rule.waitForIdle()
        assertTrue("scrolled down first", rule.onAllNodes(rowOf(1)).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasText("Season 2").and(hasClickAction())).performClick() // the chip in the left pane
        rule.waitForIdle()
        val first = rule.onAllNodes(describedMatcher("Episode 1, Second 1")).fetchSemanticsNodes()
        assertTrue("episode 1 of the new season is shown", first.isNotEmpty())
    }

    /**
     * Re-review N3: opened from Continue Watching on an episode of season 2 while the cache still chose season 1, the refresh moves
     * the library's choice to season 2. That is not the user's pick: the highlight's scroll stands.
     */
    @Test fun aSeasonTheLibraryChoosesKeepsTheHighlightInView() {
        if (!twoPane) return
        val state = mutableStateOf(ready.copy(highlightEpisodeId = id(88)))
        show { SeriesScreen(state.value, actions, emptyFlow(), snackbar) }
        rule.waitForIdle()
        state.value = ready.copy(highlightEpisodeId = id(88), selectedSeasonId = id(62), episodes = secondSeason) // after the refresh
        rule.waitForIdle()
        val row = rule.onAllNodes(describedMatcher("Episode 18, Second 18")).fetchSemanticsNodes()
        assertTrue("the highlighted episode is composed\n${tree()}", row.isNotEmpty())
        val screenHeight = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue("and on screen: ${row.first().boundsInRoot}", row.first().boundsInRoot.top < screenHeight && row.first().boundsInRoot.bottom > 0f)
    }

    private val secondSeason = (1..20).map { episode(70 + it, 2, it, name = "Second $it") }
    private val episodesList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)) // the right pane's list

    private fun describedMatcher(prefix: String) = SemanticsMatcher("description starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    @Test fun theHighlightedEpisodeIsScrolledIntoViewInTheRightPane() {
        show { SeriesScreen(ready.copy(highlightEpisodeId = id(18)), actions, emptyFlow(), snackbar) }
        rule.waitForIdle()
        val row = rule.onAllNodes(rowOf(18)).fetchSemanticsNodes()
        assertTrue("episode 18 not composed\n${tree()}", row.isNotEmpty())
        val bounds = row.first().boundsInRoot
        val screenHeight = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue("episode 18 is on screen: $bounds", bounds.top < screenHeight && bounds.bottom > 0f)
        if (twoPane) assertTrue("in the right pane: $bounds", bounds.left >= with(rule.density) { 400.dp.toPx() } - 1f)
    }

    @Test fun backIsTheFirstThingReadAndEveryEpisodeRowIs48dp() {
        show { SeriesScreen(ready, actions, emptyFlow(), snackbar) }
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val first = clickables.minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        assertEquals(listOf(text(R.string.cd_back)), first.config.getOrNull(SemanticsProperties.ContentDescription))
        val min = with(rule.density) { 48.dp.toPx() }
        rule.onAllNodes(SemanticsMatcher("an episode row") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Episode ") } == true
        }).fetchSemanticsNodes().forEach { assertTrue("${it.config}", it.boundsInRoot.height >= min - 1f) }
    }

    @Test fun bothPanesScrollToTheirLastElement() {
        if (!twoPane) return
        show { SeriesScreen(ready, actions, emptyFlow(), snackbar) }
        // The episode list: a vertical list (the season chips are a one-row horizontal one). Matched by shape, not by a row,
        // because the first rows leave the composition as it scrolls.
        val right = hasScrollAction().and(SemanticsMatcher("a vertical list") { n ->
            n.config.getOrNull(SemanticsProperties.CollectionInfo)?.let { it.rowCount > 1 && it.columnCount == 1 } == true
        })
        val left = hasScrollAction().and(hasAnyDescendant(hasText("A crew follows the tide.")))
        assertEquals("one scrolling pane holds the episodes: ${tree()}", 1, rule.onAllNodes(right).fetchSemanticsNodes().size)
        assertEquals("another holds the series", 1, rule.onAllNodes(left).fetchSemanticsNodes().size)
        rule.onAllNodes(right).onFirst().performScrollToNode(rowOf(20))
        assertTrue(rule.onAllNodes(rowOf(20)).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(left).onFirst().performScrollToNode(hasText("Season 2"))
        val chip = rule.onAllNodes(hasText("Season 2")).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("Season 2 chip in the left pane: $chip", chip.left < with(rule.density) { 400.dp.toPx() })
    }
}

@Config(qualifiers = TABLET_LANDSCAPE) class SeriesTwoPaneRenderTest : SeriesTwoPaneRenderBase() { override val fontScale = 1f; override val twoPane = true }
@Config(qualifiers = TABLET_LANDSCAPE) class SeriesTwoPaneLargeTextRenderTest : SeriesTwoPaneRenderBase() { override val fontScale = 2f; override val twoPane = true }
@Config(qualifiers = TABLET) class SeriesOnePaneTabletRenderTest : SeriesTwoPaneRenderBase() { override val fontScale = 1f; override val twoPane = false }
