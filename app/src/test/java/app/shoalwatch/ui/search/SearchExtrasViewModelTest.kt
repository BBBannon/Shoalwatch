package app.shoalwatch.ui.search

import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.discover.PersonRef
import app.shoalwatch.data.discover.SearchExtras
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.SearchResult
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.FakeDiscover
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.EMPTY_HOME
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.testServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Milestone 11 Task 8: search also shows People, Genres and Collections from the server while online. */
class SearchExtrasViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben")), account(BEN, "ben"))
    private val library = FakeLibrary().also { it.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS)) }
    private val discover = FakeDiscover()
    private val scheduler get() = main.dispatcher.scheduler

    private fun vm() = SearchViewModel(catalog, library, FakeConnections(), discover)
    private fun search(v: SearchViewModel, term: String = "ada") {
        v.onQueryChange(term)
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()
    }

    private val ada = PersonRef(id(30), "Ada", null, "Actor", ArtworkRef.of(BEN, id(30), ArtworkType.PRIMARY, "t30"))

    @Test fun onlineTheExtrasShowAlongsideTheTitles() {
        library.onSearch = { _, _ -> SearchResult(listOf(entry(1, name = "Ada Film")), offline = false) }
        discover.searchExtras = SearchExtras(listOf(ada), listOf(Named(id(5), "Comedy")), listOf(entry(9, ItemKind.BOX_SET, "Ada Saga")))
        val v = vm()
        search(v)
        val s = v.state.value
        assertEquals(SearchPhase.RESULTS, s.phase)
        assertEquals(listOf("Ada"), s.people.map { it.name })
        assertEquals(listOf("Comedy"), s.genres.map { it.name })
        assertEquals(listOf("Ada Saga"), s.collections.map { it.item.name })
        assertEquals(listOf("searchMore:ada"), discover.calls)
    }

    @Test fun offlineOnlySavedTitlesShow() {
        library.onSearch = { _, _ -> SearchResult(listOf(entry(1, name = "Ada Film")), offline = true) }
        discover.searchExtras = SearchExtras(listOf(ada), listOf(Named(id(5), "Comedy")), emptyList())
        val v = vm()
        search(v)
        val s = v.state.value
        assertTrue(s.offline)
        assertEquals(1, s.films.size)
        assertTrue(s.people.isEmpty() && s.genres.isEmpty() && s.collections.isEmpty())
        assertTrue("nothing is asked of the server offline", discover.calls.isEmpty())
    }

    @Test fun theExtrasFailingLeavesTheTitlesAlone() {
        library.onSearch = { _, _ -> SearchResult(listOf(entry(1, name = "Ada Film")), offline = false) }
        discover.failure = AppError.of(AppError.Kind.SERVER_ERROR)
        val v = vm()
        search(v)
        assertEquals(SearchPhase.RESULTS, v.state.value.phase)
        assertEquals(1, v.state.value.films.size)
        assertTrue(v.state.value.people.isEmpty())
    }

    @Test fun aPersonAloneIsAResultNotNothingFound() {
        library.onSearch = { _, _ -> SearchResult(emptyList(), offline = false) }
        discover.searchExtras = SearchExtras(listOf(ada), emptyList(), emptyList())
        val v = vm()
        search(v)
        assertEquals(SearchPhase.RESULTS, v.state.value.phase)
    }

    @Test fun genresShowWithNoLibraryToPick() {
        library.homeFlow(BEN).value = EMPTY_HOME
        library.onSearch = { _, _ -> SearchResult(emptyList(), offline = false) }
        discover.searchExtras = SearchExtras(emptyList(), listOf(Named(id(5), "Comedy")), emptyList())
        val v = vm()
        search(v)
        assertEquals(listOf("Comedy"), v.state.value.genres.map { it.name })
    }

    private class Slow(private val base: FakeDiscover, val gate: kotlinx.coroutines.CompletableDeferred<Unit>) : app.shoalwatch.data.discover.Discover by base {
        override suspend fun searchMore(s: app.shoalwatch.data.servers.Session, term: String): SearchExtras { gate.await(); return base.searchMore(s, term) }
    }

    @Test fun titlesShowAtOnceAndTheExtrasFollowWhenTheyArrive() {
        library.onSearch = { _, _ -> SearchResult(listOf(entry(1, name = "Ada Film")), offline = false) }
        discover.searchExtras = SearchExtras(listOf(ada), emptyList(), emptyList())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val v = SearchViewModel(catalog, library, FakeConnections(), Slow(discover, gate))
        search(v)
        assertEquals(1, v.state.value.films.size)
        assertTrue(v.state.value.people.isEmpty())
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(1, v.state.value.films.size)
        assertEquals(listOf("Ada"), v.state.value.people.map { it.name })
    }
}
