package app.shoalwatch.ui.search

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.shell.Tab
import androidx.compose.material3.SnackbarHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The search screen composes in each state at phone and tablet size and both text sizes. */
abstract class SearchRenderBase : RenderBase() {
    private val queries = mutableListOf<String>()
    private val opened = mutableListOf<Destination>()
    private var clears = 0
    private var retries = 0
    private var servers = 0

    private val actions = SearchActions(
        onQueryChange = { queries += it }, onClear = { clears++ }, onRetry = { retries++ }, onOpen = { opened += it }, onOpenServers = { servers++ },
    )

    private val results = SearchUiState(
        phase = SearchPhase.RESULTS, session = BEN, query = "tide",
        films = listOf(entry(1, name = "Tide Film")),
        series = listOf(entry(2, ItemKind.SERIES, "Tidal")),
        episodes = listOf(episode(3, season = 1, number = 2, name = "The Tide")),
    )

    private fun showSearch(state: SearchUiState) = show { SearchScreen(state, actions) }

    @Test fun theFieldIsThereAndFocusedTheFirstTimeTheTabOpens() {
        showSearch(SearchUiState(session = BEN))
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        assertShown(text(R.string.search_hint))
    }

    @Test fun idleSaysHowManyLettersToType() {
        showSearch(SearchUiState(session = BEN))
        assertShown(text(R.string.search_prompt))
    }

    @Test fun typingTellsTheViewModel() {
        showSearch(SearchUiState(session = BEN))
        rule.onNode(hasSetTextAction()).performTextInput("tid")
        rule.waitForIdle()
        assertEquals("tid", queries.last())
    }

    @Test fun theClearButtonAppearsWithTextAndClears() {
        showSearch(SearchUiState(session = BEN))
        rule.onNode(hasSetTextAction()).performTextInput("tid")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(text(R.string.search_clear)).performClick()
        rule.waitForIdle()
        assertEquals(1, clears)
        assertEquals("", queries.last())
    }

    @Test fun resultsAreGroupedUnderHeadings() {
        showSearch(results)
        assertShown(text(R.string.search_heading_films))
        assertShown(text(R.string.search_heading_series))
        assertShown(text(R.string.search_heading_episodes))
        assertTrue(headings() >= 3)
    }

    @Test fun rowsSayWhatTheyAre() {
        showSearch(results)
        assertDescribed("Tide Film, 2019")
        assertDescribed("Tidal, Series · 2019")
        assertDescribed("The Tide, Series 50 · S1 E2")
    }

    @Test fun tappingARowOpensTheRightPlace() {
        showSearch(results)
        rule.onNodeWithContentDescription("Tide Film, 2019").performClick()
        rule.onNodeWithContentDescription("Tidal, Series · 2019").performClick()
        rule.onNodeWithContentDescription("The Tide, Series 50 · S1 E2").performClick()
        assertEquals(
            listOf<Destination>(Destination.Detail(id(1)), Destination.Series(id(2)), Destination.Series(id(50), id(61), id(3))),
            opened,
        )
    }

    @Test fun offlineResultsSayTheyAreOnlyWhatIsSaved() {
        showSearch(results.copy(offline = true))
        assertShown(text(R.string.search_offline))
        assertShown(text(R.string.search_heading_films))
    }

    @Test fun noMatchesIsOneFriendlySentence() {
        showSearch(SearchUiState(SearchPhase.EMPTY, BEN, query = "zzz"))
        assertShown(text(R.string.search_empty_title))
        assertShown(text(R.string.search_empty_body, "zzz"))
    }

    @Test fun aFailureShowsItsMessageAndRetry() {
        showSearch(SearchUiState(SearchPhase.ERROR, BEN, query = "tide", error = "The server had a problem."))
        assertShown(text(R.string.error_title))
        assertShown("The server had a problem.")
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, retries)
    }

    @Test fun searchingKeepsTheFieldAndShowsNoStaleResults() {
        showSearch(SearchUiState(SearchPhase.SEARCHING, BEN, query = "tide"))
        assertShown(text(R.string.search_hint))
        assertNotShown(text(R.string.search_empty_title))
    }

    @Test fun nobodySignedInLinksToTheServersList() {
        showSearch(SearchUiState(SearchPhase.SIGNED_OUT))
        assertShown(text(R.string.home_signed_out_title))
        rule.onAllNodes(hasText(text(R.string.home_manage))).onFirst().performClick()
        assertEquals(1, servers)
    }

    @Test fun theShellKeepsSearchSelectedAndNotHiddenBehindTheBar() {
        show { AppShell(Tab.SEARCH, {}, SnackbarHostState()) { SearchScreen(results, actions) } }
        assertShown(text(R.string.nav_search))
        assertShown(text(R.string.nav_home))
        assertShown(text(R.string.nav_downloads))
    }
}

@Config(qualifiers = "w360dp-h640dp") class SearchPhoneRenderTest : SearchRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class SearchPhoneLargeTextRenderTest : SearchRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class SearchTabletRenderTest : SearchRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class SearchTabletLargeTextRenderTest : SearchRenderBase() { override val fontScale = 2f }
