package app.shoalwatch.ui.library

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Listing
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.ChildrenKey
import app.shoalwatch.ui.browse.EMPTY_HOME
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.FakePlaystate
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.SHOWS
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryGridViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val library = FakeLibrary()
    private val playstate = FakePlaystate()
    private val memory = BrowseMemory()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)

    private val films = ListShape.forLibrary(LibraryKind.MOVIES)
    private val byName = Sort(SortBy.NAME, descending = false)

    init {
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS, SHOWS))
        library.homeFlow(AMY).value = EMPTY_HOME.copy(views = listOf(FILMS, SHOWS))
    }

    private fun vm(parent: String = FILMS.id, mem: BrowseMemory = memory) =
        LibraryGridViewModel(parent, catalog, connections, library, playstate, mem)

    private fun key(s: Session = BEN, parent: String = FILMS.id, shape: ListShape = films, sort: Sort = byName, filter: ItemFilter = ItemFilter()) =
        ChildrenKey(s, parent, shape, sort, filter)

    private fun list(
        vararg items: ItemWithUserData,
        total: Int? = null,
        f: Freshness = Freshness.Fresh,
        more: Boolean = false,
        k: ChildrenKey = key(),
    ) { library.listFlow(k).value = Listing(items.toList(), total, f, more) }

    private fun films(n: Int, from: Int = 1) = (from until from + n).map { entry(it, name = "Film $it") }.toTypedArray()

    // --- what is asked for ---------------------------------------------------------------------------------------

    @Test fun nobodySignedInIsTheSignedOutState() {
        catalog.activeFlow.value = null
        assertEquals(GridPhase.SIGNED_OUT, vm().state.value.phase)
        assertTrue(library.childrenCollects.isEmpty())
    }

    @Test fun aFilmsLibraryAsksForItsFilmsWithTheDefaultSortAndNoFilter() {
        list(*films(2), total = 40)
        val s = vm().state.value
        assertEquals(listOf(key()), library.childrenCollects)
        assertEquals(GridPhase.READY, s.phase)
        assertEquals("Films", s.title)
        assertEquals(LibraryKind.MOVIES, s.kind)
        assertEquals(2, s.items.size)
        assertEquals(40, s.total)
        assertEquals(byName, s.sort)
        assertEquals(ItemFilter(), s.filter)
    }

    @Test fun aTvLibraryAsksForItsSeries() {
        list(k = key(parent = SHOWS.id, shape = ListShape.forLibrary(LibraryKind.TV)))
        vm(SHOWS.id)
        assertEquals(ListShape(setOf(app.shoalwatch.data.library.ItemKind.SERIES), true), library.childrenCollects.single().shape)
    }

    @Test fun anIdThatIsNotALibraryIsOpenedAsAFolderAndTakesItsNameFromTheItem() {
        val folder = id(77)
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS))
        library.itemFlow(BEN, folder).value = app.shoalwatch.data.library.ItemDetail(entry(77, app.shoalwatch.data.library.ItemKind.BOX_SET, "Sea Collection"), Freshness.Fresh)
        val v = vm(folder)
        assertEquals(ListShape.DIRECT, library.childrenCollects.single().shape)
        assertEquals("Sea Collection", v.state.value.title)
    }

    @Test fun whileTheLibrariesAreStillLoadingNothingIsAskedFor() {
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = emptyList(), viewsFreshness = Freshness.Refreshing)
        val v = vm()
        assertEquals(GridPhase.LOADING, v.state.value.phase)
        assertTrue(library.childrenCollects.isEmpty())
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS))
        assertEquals(GridPhase.READY, v.state.value.phase)
    }

    @Test fun anIdTheServerDoesNotKnowIsTheNoLongerAvailableState() {
        val v = vm(id(404))
        list(k = key(parent = id(404), shape = ListShape.DIRECT), f = Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND))
        assertEquals(GridPhase.GONE, v.state.value.phase)
    }

    // --- session ---------------------------------------------------------------------------------------------

    @Test fun switchingAccountRestartsTheListAndNothingOfTheOldOneRemains() {
        list(entry(1, name = "Ben film"))
        list(entry(2, name = "Amy film"), k = key(AMY))
        val v = vm()
        assertEquals("Ben film", v.state.value.items.single().item.name)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, v.state.value.session)
        assertEquals("Amy film", v.state.value.items.single().item.name)
        list(entry(3, name = "Ben late"))
        assertEquals("Amy film", v.state.value.items.single().item.name)
        assertEquals(listOf(BEN, AMY), library.childrenCollects.map { it.s })
    }

    @Test fun actionsRunAsTheCurrentSession() {
        val v = vm()
        catalog.activeFlow.value = account(AMY, "amy")
        v.setPlayed(id(1), true)
        assertEquals(listOf("played:amy:${id(1)}:true"), playstate.calls)
    }

    // --- freshness -------------------------------------------------------------------------------------------

    @Test fun everyFailureMapsToItsBannerAndTheSavedFilmsStay() {
        val expected = mapOf(
            AppError.Kind.OFFLINE to BannerKind.OFFLINE, AppError.Kind.TIMEOUT to BannerKind.OFFLINE, AppError.Kind.NETWORK to BannerKind.OFFLINE,
            AppError.Kind.UNAUTHORISED to BannerKind.SIGN_IN, AppError.Kind.NOT_SIGNED_IN to BannerKind.SIGN_IN,
            AppError.Kind.TLS_UNTRUSTED to BannerKind.CERTIFICATE, AppError.Kind.TLS_PIN_CHANGED to BannerKind.CERTIFICATE,
            AppError.Kind.SERVER_ERROR to BannerKind.GENERIC,
        )
        for ((kind, banner) in expected) {
            list(*films(3), f = Freshness.Stale(kind))
            val s = vm().state.value
            assertEquals(kind.name, banner, s.banner)
            assertEquals(3, s.items.size)
        }
    }

    @Test fun refreshingShowsTheProgressLineAndKeepsTheContentWithNoBanner() {
        list(*films(3), f = Freshness.Refreshing)
        val s = vm().state.value
        assertTrue(s.refreshing)
        assertNull(s.banner)
        assertEquals(3, s.items.size)
    }

    @Test fun staleUnknownWithNothingSavedIsAnErrorWithRetryNotASpinner() {
        list(f = Freshness.Stale(AppError.Kind.UNKNOWN))
        val s = vm().state.value
        assertEquals(BannerKind.GENERIC, s.banner)
        assertFalse(s.refreshing)
        assertTrue(s.items.isEmpty())
        assertTrue("the error state (with Retry) must show", s.showError)
        assertFalse(s.showSkeleton)
    }

    // --- refresh ------------------------------------------------------------------------------------------------

    @Test fun retryRefreshesTheSameListWithTheSameTuple() {
        val v = vm()
        v.refresh()
        assertEquals(listOf(BEN to RefreshTarget.Children(FILMS.id, films, byName, ItemFilter())), library.refreshes)
    }

    @Test fun pullingShowsWhileTheRefreshRuns() {
        val gate = CompletableDeferred<Unit>()
        library.onRefresh = { _, _ -> gate.await() }
        val v = vm()
        v.refresh()
        assertTrue(v.state.value.pulling)
        gate.complete(Unit)
        assertFalse(v.state.value.pulling)
    }

    @Test fun regainingTheConnectionRefreshesButOpeningWhileOnlineDoesNot() {
        connections.stateFor(SERVER_ID).value = online
        vm()
        assertTrue(library.refreshes.isEmpty())
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        connections.stateFor(SERVER_ID).value = online
        assertEquals(1, library.refreshes.size)
        assertEquals(RefreshTarget.Children(FILMS.id, films, byName, ItemFilter()), library.refreshes.single().second)
        vm()
        assertEquals(1, library.refreshes.size)
    }

    // --- paging ----------------------------------------------------------------------------------------------

    @Test fun nearTheEndWithMoreToLoadAsksForTheNextPageWithTheSameTuple() {
        list(*films(30), more = true)
        val v = vm()
        v.onEndVisible(false)
        assertTrue(library.loadMores.isEmpty())
        v.onEndVisible(true)
        assertEquals(listOf(key()), library.loadMores)
        assertEquals(library.childrenCollects.last(), library.loadMores.last())
    }

    @Test fun anEmptyListThatCanLoadMoreLoadsInsteadOfSayingEmpty() {
        list(more = true)
        val v = vm()
        assertEquals(listOf(key()), library.loadMores)
        assertFalse(v.state.value.showEmpty)
    }

    @Test fun whenFreshAgainWithTheEndStillInViewItLoadsAgain() {
        list(*films(10), more = true)
        val v = vm()
        v.onEndVisible(true)
        assertEquals(1, library.loadMores.size)
        list(*films(10), more = true, f = Freshness.Refreshing)
        list(*films(20), more = true)
        assertEquals(2, library.loadMores.size)
        list(*films(20), more = true, f = Freshness.Refreshing)
        list(*films(20), more = true) // a page that kept nothing: still more, still at the end
        assertEquals(3, library.loadMores.size)
        v.onEndVisible(false)
        list(*films(20), more = true, f = Freshness.Refreshing)
        list(*films(30), more = true)
        assertEquals(3, library.loadMores.size)
    }

    @Test fun nothingIsLoadedWhenThereIsNoMore() {
        list(*films(30), more = false)
        vm().onEndVisible(true)
        list(more = false)
        assertTrue(library.loadMores.isEmpty())
    }

    @Test fun nothingIsLoadedWhileACallIsInFlight() {
        val gate = CompletableDeferred<Unit>()
        library.loadMoreGate = gate
        list(*films(30), more = true)
        val v = vm()
        v.onEndVisible(true)
        assertEquals(1, library.loadMores.size)
        v.onEndVisible(false)
        v.onEndVisible(true)
        list(*films(30), more = true, f = Freshness.Fresh)
        list(*films(31), more = true)
        assertEquals(1, library.loadMores.size)
        gate.complete(Unit)
    }

    @Test fun nothingIsLoadedWhileRefreshingOrAfterAFailure() {
        list(*films(30), more = true, f = Freshness.Refreshing)
        val v = vm()
        v.onEndVisible(true)
        assertTrue(library.loadMores.isEmpty())
        list(*films(30), more = true, f = Freshness.Stale(AppError.Kind.OFFLINE))
        assertTrue(library.loadMores.isEmpty())
        assertEquals(BannerKind.OFFLINE, v.state.value.banner)
    }

    // --- sort and filter ------------------------------------------------------------------------------------

    @Test fun changingTheSortRekeysTheListAndLoadMoreAndRefreshUseTheNewTuple() {
        val byDate = Sort(SortBy.DATE_ADDED, descending = true)
        list(*films(3))
        list(*films(5), more = true, k = key(sort = byDate))
        val v = vm()
        v.setSort(byDate)
        assertEquals(5, v.state.value.items.size)
        assertEquals(byDate, v.state.value.sort)
        v.onEndVisible(true)
        v.refresh()
        assertEquals(key(sort = byDate), library.loadMores.single())
        assertEquals(RefreshTarget.Children(FILMS.id, films, byDate, ItemFilter()), library.refreshes.single().second)
    }

    @Test fun changingTheFilterRekeysTheList() {
        val fav = ItemFilter(favouritesOnly = true)
        list(*films(2), k = key(filter = fav))
        val v = vm()
        v.setFilter(fav)
        assertEquals(2, v.state.value.items.size)
        assertTrue(v.state.value.filtered)
        v.clearFilters()
        assertFalse(v.state.value.filtered)
        assertEquals(ItemFilter(), v.state.value.filter)
    }

    @Test fun theChoiceIsRememberedPerLibraryForTheSessionNotForOthers() {
        val byRating = Sort(SortBy.RATING, descending = true)
        val first = vm()
        first.setSort(byRating)
        first.setFilter(ItemFilter(unwatchedOnly = true))
        val again = vm()
        assertEquals(byRating, again.state.value.sort)
        assertTrue(again.state.value.filter.unwatchedOnly)
        assertEquals(byName, vm(SHOWS.id).state.value.sort)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(byName, again.state.value.sort) // another account starts fresh
    }

    // --- actions ---------------------------------------------------------------------------------------------

    @Test fun markingWatchedAndFavouritingCallPlaystateAndShowWorkingUntilDone() {
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        val v = vm()
        v.setPlayed(id(1), true)
        assertEquals(setOf(Work(id(1), ActionKind.PLAYED)), v.state.value.working)
        gate.complete(Unit)
        assertTrue(v.state.value.working.isEmpty())
        v.setFavourite(id(1), true)
        assertEquals(listOf("played:ben:${id(1)}:true", "favourite:ben:${id(1)}:true"), playstate.calls)
    }

    @Test fun favouritingInAFavouritesListAsksForARefreshOfThatList() {
        val fav = ItemFilter(favouritesOnly = true)
        val v = vm()
        v.setFilter(fav)
        v.setFavourite(id(1), false)
        assertEquals(listOf(BEN to RefreshTarget.Children(FILMS.id, films, byName, fav)), library.refreshes)
    }

    @Test fun favouritingInAPlainListDoesNotRefresh() {
        vm().setFavourite(id(1), true)
        assertTrue(library.refreshes.isEmpty())
    }

    @Test fun aFailedActionShowsItsMessageAndDoesNotRefresh() = kotlinx.coroutines.test.runTest {
        playstate.failWith = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        v.setFilter(ItemFilter(favouritesOnly = true))
        v.messages.test {
            v.setFavourite(id(1), true)
            assertEquals(AppError.of(AppError.Kind.OFFLINE).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(library.refreshes.isEmpty())
        assertTrue(v.state.value.working.isEmpty())
    }

    // --- one session never sees another's work -------------------------------------------------------------------

    @Test fun aSlowActionOfOneAccountIsNeitherShownToTheNextNorBlocksTheNextOnesOwnTap() {
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        val v = vm()
        v.setPlayed(id(1), true)
        assertEquals(1, v.state.value.working.size)
        catalog.activeFlow.value = account(AMY, "amy")
        assertTrue(v.state.value.working.isEmpty())
        v.setPlayed(id(1), true)
        assertEquals(listOf("ben", "amy"), playstate.calls.map { it.split(":")[1] })
        assertEquals(1, v.state.value.working.size)
        gate.complete(Unit)
        assertTrue(v.state.value.working.isEmpty())
    }

    @Test fun aFailureOfTheOldAccountsActionIsNeverShownToTheNextAccount() = kotlinx.coroutines.test.runTest {
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        val v = vm()
        v.messages.test {
            v.setFavourite(id(1), true)
            catalog.activeFlow.value = account(AMY, "amy")
            playstate.failWith = AppError.of(AppError.Kind.OFFLINE)
            gate.complete(Unit)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun thePullIndicatorOfTheOldAccountIsNotShownToTheNext() {
        val gate = CompletableDeferred<Unit>()
        library.onRefresh = { _, _ -> gate.await() }
        val v = vm()
        v.refresh()
        assertTrue(v.state.value.pulling)
        catalog.activeFlow.value = account(AMY, "amy")
        assertFalse(v.state.value.pulling)
        gate.complete(Unit)
        assertFalse(v.state.value.pulling)
    }

    // --- a failing local call is reported, never fatal -------------------------------------------------------------

    @Test fun aFailingPullIsReportedAndStopsPulling() = kotlinx.coroutines.test.runTest {
        library.onRefresh = { _, _ -> throw AppError.of(AppError.Kind.UNKNOWN) }
        val v = vm()
        v.messages.test {
            v.refresh()
            assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(v.state.value.pulling)
    }

    @Test fun aFailingRefreshWhenTheConnectionComesBackIsReported() = kotlinx.coroutines.test.runTest {
        connections.stateFor(SERVER_ID).value = online
        library.onRefresh = { _, _ -> error("disk full") }
        val v = vm()
        v.messages.test {
            connections.stateFor(SERVER_ID).value = Connection.Offline()
            connections.stateFor(SERVER_ID).value = online
            assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun anInFlightPageLoadOfOneAccountDoesNotBlockTheNextAccountsFirstPage() {
        val gate = CompletableDeferred<Unit>()
        library.loadMoreGate = gate
        list(more = true)
        list(more = true, k = key(AMY))
        vm()
        assertEquals(listOf(key()), library.loadMores)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(listOf(key(), key(AMY)), library.loadMores)
        gate.complete(Unit)
    }

    @Test fun aFailingPageLoadIsReportedAndLaterLoadsStillWork() = kotlinx.coroutines.test.runTest {
        library.onLoadMore = { throw AppError.of(AppError.Kind.UNKNOWN) }
        list(more = true)
        val v = vm()
        v.messages.test {
            assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        v.refresh()
        assertEquals(1, library.refreshes.size)
    }

    @Test fun aListThatCouldNotLoadMoreAndHasNothingShowsTheErrorNotJustABanner() {
        val s = LibraryGridUiState(phase = GridPhase.READY, banner = BannerKind.GENERIC, canLoadMore = true)
        assertTrue(s.showError)
        assertFalse(s.showSkeleton)
    }
}
