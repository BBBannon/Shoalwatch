package app.shoalwatch.ui.browse

import androidx.lifecycle.SavedStateHandle
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Listing
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.detail.DetailPhase
import app.shoalwatch.ui.detail.DetailViewModel
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.library.BrowseMemory
import app.shoalwatch.ui.library.LibraryGridViewModel
import app.shoalwatch.ui.series.SeriesViewModel
import app.shoalwatch.ui.testServer
import app.shoalwatch.data.servers.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A local failure (a damaged or full database) ends a screen's library flow with an error state; the screen's Retry must bring
 * it back once the database works again, on Home, a library grid, a series and an item alike (one shared mechanism in
 * `ItemActions.guard`). Each test: the flow fails -> the error state with Retry -> the store works again -> Retry -> content.
 */
class RetryAfterLocalFailureTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val library = FakeLibrary()
    private val playstate = FakePlaystate()

    /** The shared shape of every test below. */
    private fun <T> failThenRetry(make: () -> T, showsError: (T) -> Boolean, retry: (T) -> Unit, showsContent: (T) -> Boolean) {
        library.observeFailure = IllegalStateException("database disk image is malformed")
        val v = make()
        assertTrue("the failure shows the error state", showsError(v))
        assertFalse(showsContent(v))
        library.observeFailure = null // the store works again
        retry(v)
        assertTrue("Retry brings the screen back", showsContent(v))
        assertFalse(showsError(v))
    }

    @Test fun homeComesBackAfterRetry() {
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS))
        failThenRetry(
            make = { HomeViewModel(catalog, connections, library, playstate) },
            showsError = { it.state.value.localFailure },
            retry = { it.refresh() },
            showsContent = { it.state.value.views == listOf(FILMS) },
        )
    }

    @Test fun aLibraryGridComesBackAfterRetryEvenThoughItsShapeWasNotKnown() {
        val films = ListShape.forLibrary(LibraryKind.MOVIES)
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS))
        library.listFlow(ChildrenKey(BEN, FILMS.id, films, Sort(SortBy.NAME, descending = false), ItemFilter())).value =
            Listing(listOf(entry(5, name = "Film 5")), 1, Freshness.Fresh, canLoadMore = false)
        failThenRetry(
            make = { LibraryGridViewModel(FILMS.id, catalog, connections, library, playstate, BrowseMemory()) },
            showsError = { it.state.value.showError },
            retry = { it.refresh() },
            showsContent = { it.state.value.items.size == 1 },
        )
    }

    @Test fun aSeriesComesBackAfterRetry() {
        val seriesId = id(50)
        val tidal = entry(50, ItemKind.SERIES, "Tidal")
        library.seriesFlow(BEN, seriesId).value = SeriesDetail(tidal, emptyList(), emptyList(), null, Freshness.Fresh)
        failThenRetry(
            make = { SeriesViewModel(seriesId, null, null, SavedStateHandle(), catalog, connections, library, playstate) },
            showsError = { it.state.value.series == null && it.state.value.banner != null },
            retry = { it.refresh() },
            showsContent = { it.state.value.series == tidal },
        )
    }

    @Test fun anItemComesBackAfterRetry() {
        val film = entry(7)
        library.itemFlow(BEN, film.item.id).value = ItemDetail(film, Freshness.Fresh)
        failThenRetry(
            make = { DetailViewModel(film.item.id, catalog, connections, library, playstate) },
            showsError = { it.state.value.phase == DetailPhase.ERROR },
            retry = { it.refresh() },
            showsContent = { it.state.value.phase == DetailPhase.READY },
        )
    }

    // --- the grid's Retry while its shape is unknown (M9) ---------------------------------------------------------

    @Test fun theGridsRetryWithAnUnknownShapeReprobesAnOfflineServerAndRefreshesTheLibraries() {
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        library.homeFlow(BEN).value = EMPTY_HOME.copy(viewsFreshness = Freshness.Stale(app.shoalwatch.core.AppError.Kind.OFFLINE))
        val v = LibraryGridViewModel(FILMS.id, catalog, connections, library, playstate, BrowseMemory())
        assertNull("the shape is not known", v.state.value.shape)
        v.refresh()
        assertEquals(listOf("probe:$SERVER_ID"), connections.log)
        assertEquals(listOf(BEN to RefreshTarget.Home), library.refreshes)
    }

    // --- Home: one message, the right wording (M8) ----------------------------------------------------------------

    @Test fun homeShowsOneMessageForALocalFailureNotTheRefreshBannerToo() {
        library.observeFailure = IllegalStateException("database disk image is malformed")
        val s = HomeViewModel(catalog, connections, library, playstate).state.value
        assertTrue(s.localFailure)
        assertNull("no \"Couldn't refresh\" banner as well", s.topBanner)
    }
}
