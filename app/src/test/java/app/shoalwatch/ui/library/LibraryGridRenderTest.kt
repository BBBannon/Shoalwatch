package app.shoalwatch.ui.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.present.Destination
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The library grid composes in each main state and exposes the right labels, at phone and tablet size and both text sizes. */
abstract class LibraryGridRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val opened = mutableListOf<Destination>()
    private val sorts = mutableListOf<Sort>()
    private val filters = mutableListOf<ItemFilter>()
    private val ends = mutableListOf<Boolean>()
    private var cleared = 0
    private var refreshed = 0
    private var backs = 0
    private var signIns = 0
    private var reviews = 0
    private var servers = 0
    private val played = mutableListOf<Pair<String, Boolean>>()

    private val actions = LibraryGridActions(
        onBack = { backs++ }, onSetSort = { sorts += it }, onSetFilter = { filters += it }, onClearFilters = { cleared++ },
        onEndVisible = { ends += it }, onRefresh = { refreshed++ }, onOpen = { opened += it }, onSignIn = { signIns++ },
        onReview = { reviews++ }, onOpenServers = { servers++ }, onSetPlayed = { id, p -> played += id to p }, onSetFavourite = { _, _ -> },
    )

    private val films = (1..6).map { entry(it, name = "Film $it", data = userData(played = it == 2)) }

    private val ready = LibraryGridUiState(
        phase = GridPhase.READY, session = BEN, title = "Films", kind = LibraryKind.MOVIES, items = films, total = 412, fresh = true,
    )

    private fun showGrid(state: LibraryGridUiState) = show { LibraryGridScreen(state, actions, emptyFlow(), snackbar) }

    @Test fun showsTheTitleTheTotalAndEveryPosterAsOneLabelledElement() {
        showGrid(ready)
        assertShown("Films")
        assertShown("412 films")
        assertDescribed("Film 1, 2019")
        assertDescribed("Film 2, 2019, watched")
        assertDescribed(text(R.string.cd_more_actions, "Film 1"))
        assertTrue(headings() >= 1)
    }

    @Test fun tappingAPosterOpensItsDetail() {
        showGrid(ready)
        rule.onNodeWithContentDescription("Film 1, 2019").performClick()
        assertEquals(listOf<Destination>(Destination.Detail(id(1))), opened)
    }

    @Test fun aSeriesPosterShowsItsUnwatchedCountAndOpensTheSeries() {
        showGrid(ready.copy(kind = LibraryKind.TV, items = listOf(entry(9, ItemKind.SERIES, "Tidal", data = userData(unplayed = 3)))))
        assertDescribed("Tidal, 2019, 3 unwatched episodes")
        rule.onNodeWithContentDescription("Tidal, 2019, 3 unwatched episodes").performClick()
        assertEquals(listOf<Destination>(Destination.Series(id(9))), opened)
    }

    @Test fun theSortChipShowsTheCurrentSortAndTheMenuChangesIt() {
        showGrid(ready)
        assertShown(text(R.string.sort_current, text(R.string.sort_name), text(R.string.sort_name_up)))
        rule.onAllNodes(hasText(text(R.string.sort_current, text(R.string.sort_name), text(R.string.sort_name_up)))).onFirst().performClick()
        rule.waitForIdle()
        assertShown(text(R.string.sort_release))
        assertShown(text(R.string.sort_rating))
        assertShown(text(R.string.sort_date_added))
        rule.onAllNodes(hasText(text(R.string.sort_date_added))).onFirst().performClick()
        assertEquals(listOf(Sort(SortBy.DATE_ADDED, descending = true)), sorts)
    }

    @Test fun theMenuCanFlipTheDirection() {
        showGrid(ready)
        rule.onAllNodes(hasText(text(R.string.sort_current, text(R.string.sort_name), text(R.string.sort_name_up)))).onFirst().performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(text(R.string.sort_name_down))).onFirst().performClick()
        assertEquals(listOf(Sort(SortBy.NAME, descending = true)), sorts)
    }

    @Test fun everySortHasPlainWordsForItsDirectionNotAscendingOrDescending() {
        val expected = mapOf(
            (SortBy.NAME to false) to "A to Z", (SortBy.NAME to true) to "Z to A",
            (SortBy.DATE_ADDED to false) to "Oldest first", (SortBy.DATE_ADDED to true) to "Newest first",
            (SortBy.RELEASE_DATE to false) to "Oldest first", (SortBy.RELEASE_DATE to true) to "Newest first",
            (SortBy.RATING to false) to "Lowest first", (SortBy.RATING to true) to "Highest first",
        )
        for ((key, words) in expected) assertEquals(key.toString(), words, text(key.first.directionLabel(key.second)))
        assertEquals(SortBy.entries.size * 2, expected.size)
    }

    @Test fun theChipReadsSortThenTheNameThenThePlainDirection() {
        showGrid(ready.copy(sort = Sort(SortBy.DATE_ADDED, descending = true)))
        assertShown("Sort: Date added, Newest first")
    }

    @Test fun theSortMenuSaysWhichChoiceIsSelectedAsRadioButtons() {
        showGrid(ready)
        rule.onAllNodes(hasText("Sort: Name, A to Z")).onFirst().performClick()
        rule.waitForIdle()
        fun selected(label: String) = rule.onAllNodes(hasText(label).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)))
        selected(text(R.string.sort_name)).onFirst().assertIsSelected()
        selected(text(R.string.sort_name_up)).onFirst().assertIsSelected()
        selected(text(R.string.sort_rating)).onFirst().assertIsNotSelected()
        selected(text(R.string.sort_name_down)).onFirst().assertIsNotSelected()
    }

    @Test fun theTitleHasARefreshActionAndStaysOnOneLine() {
        showGrid(ready)
        val title = rule.onAllNodes(hasText("Films").and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))).onFirst()
        val actions = title.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertEquals(listOf(text(R.string.action_refresh)), actions.map { it.label })
        actions.single().action()
        assertEquals(1, refreshed)
    }

    @Test fun theFilterChipsToggleAndSayWhetherTheyAreOn() {
        showGrid(ready.copy(filter = ItemFilter(favouritesOnly = true)))
        rule.onAllNodes(hasText(text(R.string.filter_unwatched))).onFirst().performClick()
        assertEquals(listOf(ItemFilter(unwatchedOnly = true, favouritesOnly = true)), filters)
        rule.onAllNodes(hasText(text(R.string.filter_favourites))).onFirst().performClick()
        assertEquals(ItemFilter(), filters.last())
    }

    @Test fun theEndOfTheGridIsReportedWhenItIsClose() {
        showGrid(ready)
        rule.waitForIdle()
        assertTrue("reported: $ends", ends.contains(true))
    }

    @Test fun firstLoadShowsSkeletonCardsAndNoEmptyMessage() {
        showGrid(LibraryGridUiState(phase = GridPhase.LOADING, session = BEN, title = "Films"))
        assertDescribed(text(R.string.cd_loading_content))
        assertNotShown(text(R.string.library_empty_title))
    }

    @Test fun anEmptyListThatCanLoadMoreIsNotShownAsEmpty() {
        showGrid(ready.copy(items = emptyList(), total = null, canLoadMore = true))
        assertNotShown(text(R.string.library_empty_title))
    }

    @Test fun aLoadingMoreIndicatorShowsBelowTheContent() {
        showGrid(ready.copy(refreshing = true, fresh = false, canLoadMore = true, items = films))
        rule.onAllNodes(hasScrollAction()).onFirst()
        assertShown("Films")
    }

    @Test fun anEmptyLibrarySaysSoInOneSentence() {
        showGrid(ready.copy(items = emptyList(), total = 0))
        assertShown(text(R.string.library_empty_title))
        assertShown(text(R.string.library_empty_body))
    }

    @Test fun anEmptyFilteredListOffersToClearTheFilters() {
        showGrid(ready.copy(items = emptyList(), filter = ItemFilter(favouritesOnly = true)))
        assertShown(text(R.string.library_empty_filtered))
        rule.onAllNodes(hasText(text(R.string.action_clear_filters))).onFirst().performClick()
        assertEquals(1, cleared)
    }

    @Test fun anOfflineBannerKeepsThePostersAndOffersRetry() {
        showGrid(ready.copy(banner = BannerKind.OFFLINE, fresh = false))
        assertShown(text(R.string.banner_offline))
        assertDescribed("Film 1, 2019")
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anExpiredSignInOffersSignIn() {
        showGrid(ready.copy(banner = BannerKind.SIGN_IN, fresh = false))
        assertShown(text(R.string.banner_signin))
        rule.onAllNodes(hasText(text(R.string.action_sign_in))).onFirst().performClick()
        assertEquals(1, signIns)
    }

    @Test fun aCertificateProblemOffersReview() {
        showGrid(ready.copy(banner = BannerKind.CERTIFICATE, fresh = false))
        assertShown(text(R.string.banner_certificate))
        rule.onAllNodes(hasText(text(R.string.action_review))).onFirst().performClick()
        assertEquals(1, reviews)
    }

    @Test fun aFailureWithNothingSavedIsAnErrorStateWithRetryNotASpinner() {
        showGrid(ready.copy(items = emptyList(), total = null, banner = BannerKind.GENERIC, fresh = false))
        // ONE message with its button: the banner is left out.
        assertShown(text(R.string.error_title))
        assertNotShown(text(R.string.banner_generic))
        assertNotShown(text(R.string.library_empty_title))
        assertEquals(1, rule.onAllNodes(hasText(text(R.string.action_retry))).fetchSemanticsNodes().size)
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun aFailedPageLoadOnAnEmptyListStillShowsTheErrorWithItsButton() {
        showGrid(ready.copy(items = emptyList(), total = null, banner = BannerKind.OFFLINE, fresh = false, canLoadMore = true))
        assertShown(text(R.string.error_title))
        assertEquals(1, rule.onAllNodes(hasText(text(R.string.action_retry))).fetchSemanticsNodes().size)
    }

    @Test fun anItemThatIsGoneSaysSo() {
        showGrid(LibraryGridUiState(phase = GridPhase.GONE, session = BEN))
        assertShown(text(R.string.gone_title))
        rule.onAllNodes(hasText(text(R.string.cd_back))).onFirst().performClick()
        assertEquals(1, backs)
    }

    @Test fun nobodySignedInLinksToTheServersList() {
        showGrid(LibraryGridUiState(phase = GridPhase.SIGNED_OUT))
        assertShown(text(R.string.home_signed_out_title))
        rule.onAllNodes(hasText(text(R.string.home_manage))).onFirst().performClick()
        assertEquals(1, servers)
    }

    @Test fun theBackArrowGoesBack() {
        showGrid(ready)
        rule.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun theMoreButtonOpensTheSheetAndMarkingWatchedIsPassedOn() {
        showGrid(ready)
        rule.onNodeWithContentDescription(text(R.string.cd_more_actions, "Film 1")).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.sheet_mark_watched))
        assertShown(text(R.string.sheet_favourite))
        assertNotShown(text(R.string.sheet_remove_resume))
        rule.onAllNodes(hasText(text(R.string.sheet_mark_watched))).onFirst().performClick()
        assertEquals(listOf(id(1) to true), played)
    }

    @Test fun aWorkingPosterStillHasItsLabel() {
        showGrid(ready.copy(working = setOf(Work(id(2), ActionKind.PLAYED))))
        assertDescribed("Film 2, 2019, watched")
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.cd_more_actions, "Film 2"))).fetchSemanticsNodes().isNotEmpty())
    }
}

@Config(qualifiers = "w360dp-h640dp") class LibraryGridPhoneRenderTest : LibraryGridRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class LibraryGridPhoneLargeTextRenderTest : LibraryGridRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class LibraryGridTabletRenderTest : LibraryGridRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class LibraryGridTabletLargeTextRenderTest : LibraryGridRenderBase() { override val fontScale = 2f }
