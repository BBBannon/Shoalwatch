package app.shoalwatch.ui.series

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.FakePlaystate
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeriesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val library = FakeLibrary()
    private val playstate = FakePlaystate()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)

    private val series = id(50)
    private val season1 = id(61)
    private val season2 = id(62)
    private val tidal = entry(50, ItemKind.SERIES, "Tidal", data = userData(unplayed = 5))

    private fun season(n: Int, number: Int) =
        entry(60 + n, ItemKind.SEASON, "Season $number").let { it.copy(item = it.item.copy(indexNumber = number, seriesId = series)) }

    private val seasons = listOf(season(1, 1), season(2, 2))

    private fun detail(
        selected: String? = season1,
        episodes: List<ItemWithUserData> = listOf(episode(1, 1, 1), episode(2, 1, 2)),
        f: Freshness = Freshness.Fresh,
        s: ItemWithUserData? = tidal,
        seasons: List<ItemWithUserData> = this.seasons,
    ) = SeriesDetail(s, seasons, episodes, selected, f)

    private fun vm(season: String? = null, highlight: String? = null, handle: SavedStateHandle = SavedStateHandle()) =
        SeriesViewModel(series, season, highlight, handle, catalog, connections, library, playstate)

    private fun publish(seasonArg: String?, d: SeriesDetail, s: app.shoalwatch.data.servers.Session = BEN) { library.seriesFlow(s, series, seasonArg).value = d }

    // --- who and what -----------------------------------------------------------------------------------------

    @Test fun nobodySignedInIsTheSignedOutState() {
        catalog.activeFlow.value = null
        assertEquals(SeriesPhase.SIGNED_OUT, vm().state.value.phase)
    }

    @Test fun showsTheSeriesItsSeasonsAndTheEpisodesOfTheSelectedSeason() {
        publish(null, detail())
        val s = vm().state.value
        assertEquals(SeriesPhase.READY, s.phase)
        assertEquals("Tidal", s.series?.item?.name)
        assertEquals(2, s.seasons.size)
        assertEquals(2, s.episodes.size)
        assertEquals(season1, s.selectedSeasonId)
    }

    @Test fun theRouteSeasonAndTheHighlightedEpisodeAreUsed() {
        publish(season2, detail(selected = season2, episodes = listOf(episode(5, 2, 5))))
        val s = vm(season = season2, highlight = id(5)).state.value
        assertEquals(season2, s.selectedSeasonId)
        assertEquals(id(5), s.highlightEpisodeId)
        assertEquals(listOf(Triple(BEN, series, season2 as String?)), library.seriesCollects)
    }

    @Test fun anUnknownSeriesIsTheNoLongerAvailableState() {
        publish(null, detail(s = null, seasons = emptyList(), episodes = emptyList(), selected = null, f = Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)))
        assertEquals(SeriesPhase.GONE, vm().state.value.phase)
    }

    @Test fun beforeTheFirstAnswerItIsLoading() {
        publish(null, detail(s = null, seasons = emptyList(), episodes = emptyList(), selected = null, f = Freshness.Refreshing))
        val s = vm().state.value
        assertTrue(s.refreshing)
        assertTrue(s.series == null && s.seasons.isEmpty())
    }

    // --- season selection ------------------------------------------------------------------------------------

    @Test fun theDefaultSeasonComesFromTheLibraryAndAUserChoiceSticks() {
        publish(null, detail(selected = season1))
        publish(season2, detail(selected = season2, episodes = listOf(episode(5, 2, 5))))
        val v = vm()
        assertEquals(season1, v.state.value.selectedSeasonId)
        v.pickSeason(season2)
        assertEquals(season2, v.state.value.selectedSeasonId)
        assertEquals(listOf(id(5)), v.state.value.episodes.map { it.item.id })
        // the library moves its own default (the next unwatched episode changed): the user's choice stays
        publish(season2, detail(selected = season1, episodes = listOf(episode(5, 2, 5))))
        assertEquals(season2, v.state.value.selectedSeasonId)
    }

    @Test fun theChoiceSurvivesARefresh() {
        publish(season2, detail(selected = season2))
        val v = vm()
        v.pickSeason(season2)
        v.refresh()
        assertEquals(listOf(BEN to RefreshTarget.Series(series, season2)), library.refreshes)
        assertEquals(season2, v.state.value.selectedSeasonId)
    }

    @Test fun theChoiceSurvivesRecreationThroughTheSavedHandle() {
        val handle = SavedStateHandle()
        publish(season2, detail(selected = season2))
        vm(handle = handle).pickSeason(season2)
        val again = vm(handle = handle)
        assertEquals(season2, again.state.value.selectedSeasonId)
        assertEquals(season2, library.seriesCollects.last().third)
    }

    @Test fun refreshWithNoChoiceUsesTheRouteSeasonOrNone() {
        publish(null, detail())
        vm().refresh()
        assertEquals(RefreshTarget.Series(series, null), library.refreshes.single().second)
        library.refreshes.clear()
        publish(season2, detail(selected = season2))
        vm(season = season2).refresh()
        assertEquals(RefreshTarget.Series(series, season2), library.refreshes.single().second)
    }

    // --- session ---------------------------------------------------------------------------------------------

    @Test fun switchingAccountRestartsAndNothingOfTheOldOneRemains() {
        publish(null, detail(s = entry(50, ItemKind.SERIES, "Ben's view")))
        publish(null, detail(s = entry(50, ItemKind.SERIES, "Amy's view")), AMY)
        val v = vm()
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals("Amy's view", v.state.value.series?.item?.name)
        publish(null, detail(s = entry(50, ItemKind.SERIES, "Ben late")))
        assertEquals("Amy's view", v.state.value.series?.item?.name)
        assertEquals(AMY, v.state.value.session)
    }

    // --- freshness -------------------------------------------------------------------------------------------

    @Test fun everyFailureMapsToItsBannerAndContentStays() {
        val expected = mapOf(
            AppError.Kind.OFFLINE to BannerKind.OFFLINE, AppError.Kind.NETWORK to BannerKind.OFFLINE, AppError.Kind.TIMEOUT to BannerKind.OFFLINE,
            AppError.Kind.UNAUTHORISED to BannerKind.SIGN_IN, AppError.Kind.NOT_SIGNED_IN to BannerKind.SIGN_IN,
            AppError.Kind.TLS_UNTRUSTED to BannerKind.CERTIFICATE, AppError.Kind.TLS_PIN_CHANGED to BannerKind.CERTIFICATE,
            AppError.Kind.SERVER_ERROR to BannerKind.GENERIC,
        )
        for ((kind, banner) in expected) {
            publish(null, detail(f = Freshness.Stale(kind)))
            val s = vm().state.value
            assertEquals(kind.name, banner, s.banner)
            assertEquals(2, s.episodes.size)
        }
    }

    @Test fun refreshingShowsTheLineAndNoBanner() {
        publish(null, detail(f = Freshness.Refreshing))
        val s = vm().state.value
        assertTrue(s.refreshing)
        assertNull(s.banner)
    }

    @Test fun staleUnknownWithNothingSavedIsAnErrorStateWithRetry() {
        publish(null, detail(s = null, seasons = emptyList(), episodes = emptyList(), selected = null, f = Freshness.Stale(AppError.Kind.UNKNOWN)))
        val s = vm().state.value
        assertEquals(BannerKind.GENERIC, s.banner)
        assertEquals(SeriesPhase.READY, s.phase)
        assertTrue(s.series == null)
        assertFalse(s.refreshing)
    }

    // --- refresh triggers ---------------------------------------------------------------------------------------

    @Test fun pullingShowsWhileTheRefreshRuns() {
        val gate = CompletableDeferred<Unit>()
        library.onRefresh = { _, _ -> gate.await() }
        publish(null, detail())
        val v = vm()
        v.refresh()
        assertTrue(v.state.value.pulling)
        gate.complete(Unit)
        assertFalse(v.state.value.pulling)
    }

    @Test fun regainingTheConnectionRefreshesButOpeningWhileOnlineDoesNot() {
        connections.stateFor(SERVER_ID).value = online
        publish(null, detail())
        vm()
        assertTrue(library.refreshes.isEmpty())
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        connections.stateFor(SERVER_ID).value = online
        assertEquals(listOf(BEN to RefreshTarget.Series(series, null)), library.refreshes)
        vm()
        assertEquals(1, library.refreshes.size)
    }

    // --- actions ---------------------------------------------------------------------------------------------

    @Test fun seriesAndSeasonAndEpisodeActionsCallPlaystateAsTheSession() {
        publish(null, detail())
        val v = vm()
        v.setSeriesPlayed(true)
        v.setSeasonPlayed(season1, true)
        v.setPlayed(id(1), false)
        v.setFavourite(id(1), true)
        v.setSeriesFavourite(true)
        assertEquals(
            listOf(
                "played:ben:$series:true", "played:ben:$season1:true", "played:ben:${id(1)}:false",
                "favourite:ben:${id(1)}:true", "favourite:ben:$series:true",
            ),
            playstate.calls,
        )
    }

    @Test fun aControlShowsWorkingUntilTheServerAnswersAndAFailureIsShown() = kotlinx.coroutines.test.runTest {
        publish(null, detail())
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        playstate.failWith = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        v.messages.test {
            v.setSeriesPlayed(true)
            assertEquals(setOf(Work(series, ActionKind.PLAYED)), v.state.value.working)
            gate.complete(Unit)
            assertEquals(AppError.of(AppError.Kind.OFFLINE).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(v.state.value.working.isEmpty())
    }

    @Test fun theSeriesPlayedStateAndFavouriteComeFromItsUserData() {
        publish(null, detail(s = entry(50, ItemKind.SERIES, "Tidal", data = userData(played = true, favourite = true))))
        val s = vm().state.value
        assertTrue(s.seriesPlayed)
        assertTrue(s.seriesFavourite)
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

    @Test fun aSeasonPickedByOneAccountIsNotAppliedToTheNext() {
        publish(null, detail())
        publish(season2, detail(selected = season2, episodes = listOf(episode(5, 2, 5))))
        publish(null, detail(), AMY)
        val v = vm()
        v.pickSeason(season2)
        assertEquals(season2, v.state.value.selectedSeasonId)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, library.seriesCollects.last().first)
        assertNull(library.seriesCollects.last().third)
        assertEquals(season1, v.state.value.selectedSeasonId)
    }

    @Test fun aSeasonSavedBeforeTheAppWasRecreatedBelongsToItsOwnAccountOnly() {
        val handle = SavedStateHandle()
        publish(null, detail())
        publish(season2, detail(selected = season2, episodes = listOf(episode(5, 2, 5))))
        vm(handle = handle).pickSeason(season2)
        catalog.activeFlow.value = account(AMY, "amy")
        publish(null, detail(), AMY)
        val again = vm(handle = handle)
        assertEquals(season1, again.state.value.selectedSeasonId)
    }
}
