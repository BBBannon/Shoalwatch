package app.shoalwatch.ui.search

import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.SearchResult
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val library = FakeLibrary()
    private val scheduler get() = main.dispatcher.scheduler

    private val connections = FakeConnections()

    private fun vm() = SearchViewModel(catalog, library, connections)
    private fun advance(ms: Long) { scheduler.advanceTimeBy(ms); scheduler.runCurrent() }

    private val results = SearchResult(listOf(entry(1, name = "Tide Film"), entry(2, ItemKind.SERIES, "Tidal"), episode(3, name = "The Tide")), offline = false)

    @Test fun nobodySignedInIsTheSignedOutState() {
        catalog.activeFlow.value = null
        assertEquals(SearchPhase.SIGNED_OUT, vm().state.value.phase)
    }

    @Test fun startsIdleAndAskingForTwoLettersWithNothingSent() {
        val v = vm()
        assertEquals(SearchPhase.IDLE, v.state.value.phase)
        v.onQueryChange("t")
        advance(1_000)
        assertEquals(SearchPhase.IDLE, v.state.value.phase)
        assertTrue(library.searches.isEmpty())
        assertTrue(v.state.value.tooShort)
    }

    @Test fun aSearchWaitsThreeHundredMillisecondsAfterTheLastKeystroke() {
        library.onSearch = { _, _ -> results }
        val v = vm()
        v.onQueryChange("ti")
        advance(299)
        assertTrue(library.searches.isEmpty())
        v.onQueryChange("tid")
        advance(299)
        assertTrue(library.searches.isEmpty())
        advance(1)
        assertEquals(listOf(BEN to "tid"), library.searches)
        assertEquals(SearchPhase.RESULTS, v.state.value.phase)
    }

    @Test fun resultsAreGroupedIntoFilmsSeriesAndEpisodes() {
        library.onSearch = { _, _ -> results }
        val v = vm()
        v.onQueryChange("tide")
        advance(300)
        val s = v.state.value
        assertEquals(listOf("Tide Film"), s.films.map { it.item.name })
        assertEquals(listOf("Tidal"), s.series.map { it.item.name })
        assertEquals(listOf("The Tide"), s.episodes.map { it.item.name })
        assertFalse(s.offline)
    }

    @Test fun theQueryIsTrimmedAndTheSameSearchIsNotRepeated() {
        library.onSearch = { _, _ -> results }
        val v = vm()
        v.onQueryChange("  tide ")
        advance(300)
        v.onQueryChange("tide")
        advance(300)
        assertEquals(listOf(BEN to "tide"), library.searches)
    }

    @Test fun aSupersededSearchIsCancelledAndItsAnswerNeverShown() {
        val slow = CompletableDeferred<SearchResult>()
        library.onSearch = { _, term -> if (term == "slow") slow.await() else SearchResult(listOf(entry(9, name = "Fast Film")), false) }
        val v = vm()
        v.onQueryChange("slow")
        advance(300)
        assertEquals(SearchPhase.SEARCHING, v.state.value.phase)
        v.onQueryChange("fast")
        advance(300)
        assertEquals(listOf("Fast Film"), v.state.value.films.map { it.item.name })
        slow.complete(SearchResult(listOf(entry(8, name = "Slow Film")), false))
        scheduler.runCurrent()
        assertEquals(listOf("Fast Film"), v.state.value.films.map { it.item.name })
    }

    @Test fun clearingTheQueryClearsTheResultsAndSendsNothing() {
        library.onSearch = { _, _ -> results }
        val v = vm()
        v.onQueryChange("tide")
        advance(300)
        v.clear()
        advance(300)
        assertEquals("", v.state.value.query)
        assertEquals(SearchPhase.IDLE, v.state.value.phase)
        assertTrue(v.state.value.films.isEmpty())
        assertEquals(1, library.searches.size)
    }

    @Test fun offlineResultsSayTheyAreOnlyWhatIsSaved() {
        library.onSearch = { _, _ -> SearchResult(listOf(entry(1, name = "Saved Film")), offline = true) }
        val v = vm()
        v.onQueryChange("saved")
        advance(300)
        assertTrue(v.state.value.offline)
        assertEquals(SearchPhase.RESULTS, v.state.value.phase)
    }

    @Test fun noMatchesIsTheEmptyState() {
        library.onSearch = { _, _ -> SearchResult(emptyList(), false) }
        val v = vm()
        v.onQueryChange("zzz")
        advance(300)
        assertEquals(SearchPhase.EMPTY, v.state.value.phase)
    }

    @Test fun aFailureIsAnErrorWithItsOwnMessageAndRetrySearchesAgain() {
        var fail = true
        library.onSearch = { _, _ -> if (fail) throw AppError.of(AppError.Kind.SERVER_ERROR) else results }
        val v = vm()
        v.onQueryChange("tide")
        advance(300)
        assertEquals(SearchPhase.ERROR, v.state.value.phase)
        assertEquals(AppError.of(AppError.Kind.SERVER_ERROR).userMessage, v.state.value.error)
        fail = false
        v.retry()
        advance(0)
        assertEquals(SearchPhase.RESULTS, v.state.value.phase)
        assertNull(v.state.value.error)
        assertEquals(2, library.searches.size)
    }

    @Test fun anUnexpectedFailureStillGetsAPlainMessage() {
        library.onSearch = { _, _ -> error("boom") }
        val v = vm()
        v.onQueryChange("tide")
        advance(300)
        assertEquals(SearchPhase.ERROR, v.state.value.phase)
        assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, v.state.value.error)
    }

    @Test fun switchingAccountClearsTheTypedTextAndTheResultsAndSearchesNothingAsTheNewUser() {
        library.onSearch = { s, _ -> SearchResult(listOf(entry(if (s == BEN) 1 else 2, name = if (s == BEN) "Ben hit" else "Amy hit")), false) }
        val v = vm()
        v.onQueryChange("hit")
        advance(300)
        assertEquals(listOf("Ben hit"), v.state.value.films.map { it.item.name })
        catalog.activeFlow.value = account(AMY, "amy")
        scheduler.runCurrent()
        assertEquals(AMY, v.state.value.session)
        assertEquals("", v.state.value.query)
        assertEquals(SearchPhase.IDLE, v.state.value.phase)
        assertTrue(v.state.value.films.isEmpty())
        advance(1_000)
        assertEquals(listOf(BEN to "hit"), library.searches)
    }

    @Test fun afterASwitchTheNewUserSearchesAsThemselves() {
        library.onSearch = { s, _ -> SearchResult(listOf(entry(if (s == BEN) 1 else 2, name = if (s == BEN) "Ben hit" else "Amy hit")), false) }
        val v = vm()
        v.onQueryChange("hit")
        advance(300)
        catalog.activeFlow.value = account(AMY, "amy")
        scheduler.runCurrent()
        v.onQueryChange("hit")
        advance(300)
        assertEquals(listOf("Amy hit"), v.state.value.films.map { it.item.name })
        assertEquals(listOf(BEN to "hit", AMY to "hit"), library.searches)
    }

    @Test fun signingOutClearsTheTypedTextToo() {
        val v = vm()
        v.onQueryChange("secret")
        advance(300)
        catalog.activeFlow.value = null
        scheduler.runCurrent()
        assertEquals("", v.state.value.query)
    }

    @Test fun nothingIsRememberedBetweenInstances() {
        library.onSearch = { _, _ -> results }
        val first = vm()
        first.onQueryChange("tide")
        advance(300)
        assertEquals("", vm().state.value.query)
    }

    @Test fun retryingASearchOfAnOfflineServerAsksForAProbeFirst() {
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        val v = vm()
        v.retry()
        assertEquals(listOf("probe:$SERVER_ID"), connections.log)
    }

    @Test fun retryingASearchOfAnOnlineServerDoesNotProbe() {
        connections.stateFor(SERVER_ID).value = Connection.Online(app.shoalwatch.ui.address("http://192.168.1.10:8096"), isHome = true)
        vm().retry()
        assertTrue(connections.log.isEmpty())
    }
}
