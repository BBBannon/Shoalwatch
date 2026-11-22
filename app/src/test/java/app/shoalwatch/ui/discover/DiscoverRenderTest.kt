package app.shoalwatch.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.Extras
import app.shoalwatch.data.discover.FilterOptions
import app.shoalwatch.data.discover.MediaVersion
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.discover.OnlineTrailer
import app.shoalwatch.data.discover.PersonRef
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.detail.DetailActions
import app.shoalwatch.ui.detail.DetailPhase
import app.shoalwatch.ui.detail.DetailScreen
import app.shoalwatch.ui.detail.DetailUiState
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.present.Destination
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Milestone 11 Task 8: the extras block, the person page, the filtered list, the genres and studios pages and the search extras at
 * phone and tablet size and at font scale 1.0 and 2.0. Every control is 48dp or more and names what it does.
 */
abstract class DiscoverRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val done = mutableListOf<String>()

    private fun person(n: Int, name: String, role: String?) = PersonRef(id(n), name, role, "Actor", ArtworkRef.of(BEN, id(n), ArtworkType.PRIMARY, "t$n"))

    private val extrasActions = ExtrasActions(
        onOpen = { done += "open:$it" }, onOpenPerson = { done += "person:$it" }, onPlayTrailer = { done += "trailer:$it" },
        onWatchOnline = { done += "online:${it.host}" }, onChooseVersion = { done += "version:$it" }, onRetry = { done += "retry" },
    )

    private val full = Extras(
        cast = listOf(person(1, "Ada Lovelace", "Countess"), person(2, "Grace Hopper", null)),
        localTrailers = listOf(entry(3, ItemKind.VIDEO, "Teaser")),
        onlineTrailers = listOf(OnlineTrailer("https://www.youtube.com/watch?v=abc", "www.youtube.com", null)),
        specialFeatures = listOf(entry(4, ItemKind.VIDEO, "Making of")),
        similar = listOf(entry(5, name = "Similar Film")),
        versions = listOf(MediaVersion("a".repeat(32), "1080p"), MediaVersion("b".repeat(32), "")),
    )

    private fun showExtras(state: ExtrasUiState) = show {
        val controls = ExtrasControls(state, extrasActions)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ExtrasVersions(controls)
            ExtrasBlock(controls, BEN)
        }
    }

    private val ready = ExtrasUiState(BEN, ExtrasPhase.READY, full, listOf(CollectionRef(id(7), "The Saga")))

    @Test fun everyPartShowsUnderItsOwnHeading() {
        showExtras(ready)
        listOf(R.string.extras_cast, R.string.extras_trailers, R.string.extras_extras, R.string.extras_similar, R.string.extras_collections, R.string.extras_version_heading)
            .forEach { rule.onAllNodes(hasText(text(it))).onFirst().performScrollTo(); assertShown(text(it)) }
        assertEquals(5, headings())
    }

    @Test fun castCardsOpenThePersonAndNameThemWithTheirPart() {
        showExtras(ready)
        rule.onNodeWithContentDescription("Ada Lovelace, Countess").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Grace Hopper, Actor").assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("person:${id(1)}"), done)
    }

    @Test fun anOnlineTrailerNamesItsHostAndALocalOneNamesItself() {
        showExtras(ready)
        rule.onNode(hasText(text(R.string.extras_watch_trailer, "www.youtube.com"))).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText(text(R.string.extras_play_trailer, "Teaser"))).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("online:www.youtube.com", "trailer:${id(3)}"), done)
    }

    @Test fun theVersionChooserMarksTheChosenOneAndNamesAnUnnamedOne() {
        showExtras(ready.copy(chosenVersionId = "b".repeat(32)))
        rule.onNode(hasText("1080p")).assertIsNotSelected()
        rule.onNode(hasText(text(R.string.extras_version_n, 2))).assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNode(hasText("1080p")).performClick()
        assertEquals(listOf("version:${"a".repeat(32)}"), done)
    }

    @Test fun aSingleVersionShowsNoChooser() {
        showExtras(ready.copy(extras = full.copy(versions = emptyList())))
        assertNotShown(text(R.string.extras_version_heading))
    }

    @Test fun eachPartHidesOnItsOwnWhenEmpty() {
        showExtras(ready.copy(extras = Extras.NONE.copy(similar = full.similar), collections = emptyList()))
        assertShown(text(R.string.extras_similar))
        listOf(R.string.extras_cast, R.string.extras_trailers, R.string.extras_extras, R.string.extras_collections).forEach { assertNotShown(text(it)) }
    }

    @Test fun collectionsOpenAsALibrary() {
        showExtras(ready)
        rule.onNode(hasText("The Saga")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("open:${Destination.Library(id(7))}"), done)
    }

    @Test fun offlineSaysSoWithTryAgain() {
        showExtras(ready.copy(phase = ExtrasPhase.OFFLINE, extras = Extras.NONE))
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("retry"), done)
    }

    @Test fun whileLoadingNothingShows() {
        showExtras(ready.copy(phase = ExtrasPhase.LOADING, extras = Extras.NONE))
        assertNotShown(text(R.string.extras_cast))
        assertNotShown(text(R.string.lists_offline_title))
    }

    private val detailActions = DetailActions(
        onBack = {}, onRefresh = {}, onOpen = {}, onSignIn = {}, onReview = {}, onOpenServers = {}, onSetPlayed = {}, onSetFavourite = {}, onPlay = {},
    )

    @Test fun theDetailPageShowsTheBlockUnderItsOwnContent() {
        val state = DetailUiState(DetailPhase.READY, BEN, entry(7, name = "The Example Film"))
        show { DetailScreen(state, detailActions, emptyFlow(), snackbar, null, ExtrasControls(ready, extrasActions)) }
        assertShown("The Example Film")
        rule.onAllNodes(hasText(text(R.string.extras_cast))).onFirst().performScrollTo()
        assertShown(text(R.string.extras_cast))
        assertDescribed("Ada Lovelace, Countess")
    }

    // ---- Person page

    private val personActions = PersonActions(
        onBack = { done += "back" }, onOpen = { done += "open:$it" }, onEndVisible = {}, onRetry = { done += "retry" }, onRetryMore = { done += "more" },
    )

    private fun personState(phase: ListsPhase = ListsPhase.READY, works: PagedState<app.shoalwatch.data.library.ItemWithUserData> = PagedState(ListsPhase.READY, listOf(entry(11, name = "First Film"), entry(12, ItemKind.SERIES, "A Show")))) =
        PersonUiState(BEN, phase, item(30, ItemKind.OTHER, "Ada Lovelace", overview = "Mathematician."), works)

    @Test fun thePersonPageShowsPictureOverviewAndWorks() {
        show { PersonScreen(personState(), personActions) }
        assertShown("Ada Lovelace")
        assertShown("Mathematician.")
        assertShown(text(R.string.person_works))
        rule.onNodeWithContentDescription("First Film, 2019").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("open:${Destination.Detail(id(11))}"), done)
        rule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp)
    }

    @Test fun aPersonWithNoWorksSaysSo() {
        show { PersonScreen(personState(works = PagedState(ListsPhase.READY, emptyList())), personActions) }
        assertShown(text(R.string.person_no_works))
    }

    @Test fun thePersonPageIsOfflineWithTryAgain() {
        show { PersonScreen(PersonUiState(BEN, ListsPhase.OFFLINE), personActions) }
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), done)
    }

    @Test fun aLaterPageThatFailedOffersTryAgain() {
        show { PersonScreen(personState(works = PagedState(ListsPhase.READY, listOf(entry(11, name = "First Film")), canLoadMore = true, moreFailed = true)), personActions) }
        scrollTo(hasText(text(R.string.action_retry)))
        rule.onNode(hasText(text(R.string.action_retry))).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("more"), done)
    }

    // ---- Filtered list

    private val browseActions = BrowseActions(
        onBack = {}, onOpen = { done += "open:$it" }, onGenre = { done += "genre:$it" }, onStudio = { done += "studio:$it" }, onYear = { done += "year:$it" },
        onRating = { done += "rating:$it" }, onTag = { done += "tag:$it" }, onLetter = { done += "letter:$it" }, onClear = { done += "clear" },
        onEndVisible = {}, onRetry = { done += "retry" }, onRetryMore = {},
    )

    private val options = FilterOptions(listOf(2019, 2020), listOf("PG", "15"), listOf("kids"), listOf(Named(id(5), "Comedy")), listOf(Named(id(6), "Acme")))

    private fun browseState(filter: BrowseFilter = BrowseFilter(), list: PagedState<app.shoalwatch.data.library.ItemWithUserData> = PagedState(ListsPhase.READY, listOf(entry(1, name = "Apple Film")))) =
        BrowseUiState(BEN, ListsPhase.READY, "Films", filter, options, list)

    @Test fun theFilteredListHasTheFiveFiltersAndTheLetterBar() {
        show { BrowseScreen(browseState(), browseActions) }
        assertShown("Films")
        listOf(R.string.discover_filter_genre, R.string.discover_filter_year, R.string.discover_filter_rating, R.string.discover_filter_studio, R.string.discover_filter_tag)
            .forEach { rule.onNode(hasText(text(it))).assertHeightIsAtLeast(48.dp) }
        rule.onNodeWithContentDescription(text(R.string.discover_letter_hash_cd)).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription(text(R.string.discover_letter_cd, "A")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("letter:A"), done)
        rule.onNodeWithContentDescription("Apple Film, 2019").assertExists()
    }

    @Test fun aFilterMenuOffersAnyAndTheLibrarysOwnValues() {
        show { BrowseScreen(browseState(), browseActions) }
        rule.onNode(hasText(text(R.string.discover_filter_genre))).performClick()
        assertShown(text(R.string.discover_filter_any))
        rule.onNode(hasText("Comedy")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("genre:${id(5)}"), done)
    }

    @Test fun theChosenValueShowsOnItsChipAndClearFiltersAppears() {
        show { BrowseScreen(browseState(BrowseFilter(genreId = id(5), year = 2020, letter = "B")), browseActions) }
        assertShown(text(R.string.discover_filter_chosen, text(R.string.discover_filter_genre), "Comedy"))
        assertShown(text(R.string.discover_filter_chosen, text(R.string.discover_filter_year), "2020"))
        rule.onNodeWithContentDescription(text(R.string.discover_letter_cd, "B")).assertIsSelected()
        rule.onNode(hasText(text(R.string.action_clear_filters))).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("clear"), done)
    }

    @Test fun nothingMatchingSaysSoWithClearFilters() {
        show { BrowseScreen(browseState(BrowseFilter(year = 2020), PagedState(ListsPhase.READY, emptyList())), browseActions) }
        assertShown(text(R.string.library_empty_filtered))
        assertTrue(rule.onAllNodes(hasText(text(R.string.action_clear_filters))).fetchSemanticsNodes().size >= 1)
    }

    @Test fun theFilteredListIsOfflineWithTryAgain() {
        show { BrowseScreen(browseState(list = PagedState(ListsPhase.OFFLINE)), browseActions) }
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), done)
    }

    // ---- Genres and studios

    private val namesActions = NamesActions(onBack = {}, onOpen = { done += "open:${it.name}" }, onEndVisible = {}, onRetry = { done += "retry" })

    @Test fun genresAreRowsThatOpenTheFilteredList() {
        show { NamesScreen(NamesUiState(BEN, NamesKind.GENRES, PagedState(ListsPhase.READY, listOf(Named(id(5), "Comedy"), Named(id(6), "Drama")))), namesActions) }
        assertShown(text(R.string.discover_genres_title))
        rule.onNode(hasText("Comedy")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("open:Comedy"), done)
    }

    @Test fun studiosHaveTheirOwnHeadingAndEmptyWords() {
        show { NamesScreen(NamesUiState(BEN, NamesKind.STUDIOS, PagedState(ListsPhase.READY, emptyList())), namesActions) }
        assertShown(text(R.string.discover_studios_title))
        assertShown(text(R.string.discover_studios_empty))
    }

    @Test fun aLaterPageOfGenresShowsAnAnnouncedSpinner() {
        show { NamesScreen(NamesUiState(BEN, NamesKind.GENRES, PagedState(ListsPhase.READY, listOf(Named(id(5), "Comedy")), canLoadMore = true, loadingMore = true)), namesActions) }
        assertDescribed(text(R.string.library_loading_more))
    }

    @Test fun genresAreOfflineWithTryAgain() {
        show { NamesScreen(NamesUiState(BEN, NamesKind.GENRES, PagedState(ListsPhase.OFFLINE)), namesActions) }
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), done)
    }

    // ---- The library grid's buttons

    private fun gridActions(withDiscover: Boolean) = app.shoalwatch.ui.library.LibraryGridActions(
        onBack = {}, onSetSort = {}, onSetFilter = {}, onClearFilters = {}, onEndVisible = {}, onRefresh = {}, onOpen = {}, onSignIn = {}, onReview = {},
        onOpenServers = {}, onSetPlayed = { _, _ -> }, onSetFavourite = { _, _ -> },
        onOpenFilter = if (withDiscover) ({ done += "filter" }) else null,
        onOpenGenres = if (withDiscover) ({ done += "genres" }) else null,
        onOpenStudios = if (withDiscover) ({ done += "studios" }) else null,
    )

    private fun gridState(kind: app.shoalwatch.data.library.LibraryKind) = app.shoalwatch.ui.library.LibraryGridUiState(
        phase = app.shoalwatch.ui.library.GridPhase.READY, session = BEN, title = "Films", kind = kind, items = listOf(entry(1)), fresh = true,
    )

    @Test fun aFilmsLibraryHasFilterGenresAndStudiosButtons() {
        show { app.shoalwatch.ui.library.LibraryGridScreen(gridState(app.shoalwatch.data.library.LibraryKind.MOVIES), gridActions(true), emptyFlow(), snackbar) }
        listOf(R.string.discover_filter_button, R.string.discover_genres_title, R.string.discover_studios_title).forEach {
            rule.onNode(hasText(text(it))).assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("filter", "genres", "studios"), done)
    }

    @Test fun aCollectionOrPhotoLibraryHasNoSuchButtons() {
        show { app.shoalwatch.ui.library.LibraryGridScreen(gridState(app.shoalwatch.data.library.LibraryKind.PHOTOS), gridActions(true), emptyFlow(), snackbar) }
        assertNotShown(text(R.string.discover_genres_title))
    }

    // ---- Search extras

    private val searchActions = app.shoalwatch.ui.search.SearchActions(
        onQueryChange = {}, onClear = {}, onRetry = {}, onOpen = { done += "open:$it" }, onOpenServers = {},
        onOpenPerson = { done += "person:$it" }, onOpenGenre = { genre -> done += "genre:$genre" },
    )

    @Test fun searchShowsPeopleGenresAndCollections() {
        val state = app.shoalwatch.ui.search.SearchUiState(
            phase = app.shoalwatch.ui.search.SearchPhase.RESULTS, session = BEN, query = "ada",
            people = listOf(person(1, "Ada Lovelace", null)), genres = listOf(Named(id(5), "Comedy")),
            collections = listOf(entry(9, ItemKind.BOX_SET, "Ada Saga")),
        )
        show { app.shoalwatch.ui.search.SearchScreen(state, searchActions) }
        listOf(R.string.search_heading_people, R.string.search_heading_genres, R.string.search_heading_collections).forEach { assertShown(text(it)) }
        rule.onNodeWithContentDescription("Ada Lovelace").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText("Comedy")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("person:${id(1)}", "genre:${id(5)}"), done)
    }
}

@Config(qualifiers = PHONE) class DiscoverPhoneRenderTest : DiscoverRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class DiscoverPhoneLargeTextRenderTest : DiscoverRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class DiscoverTabletRenderTest : DiscoverRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class DiscoverTabletLargeTextRenderTest : DiscoverRenderBase() { override val fontScale = 2f }
