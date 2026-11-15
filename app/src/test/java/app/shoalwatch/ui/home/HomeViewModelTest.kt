package app.shoalwatch.ui.home

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.HomeRow
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.FakePlaystate
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.SHOWS
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
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

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val library = FakeLibrary()
    private val playstate = FakePlaystate()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)

    private fun vm(refreshOnOpen: Boolean = false) = HomeViewModel(catalog, connections, library, playstate, refreshOnOpen)

    private fun row(vararg items: app.shoalwatch.data.library.ItemWithUserData, f: Freshness = Freshness.Fresh) = HomeRow(items.toList(), f)

    private fun home(
        resume: HomeRow = row(),
        nextUp: HomeRow = row(),
        latest: List<Pair<app.shoalwatch.data.library.LibraryView, HomeRow>> = emptyList(),
        views: List<app.shoalwatch.data.library.LibraryView> = listOf(FILMS, SHOWS),
        viewsFreshness: Freshness = Freshness.Fresh,
    ) = Home(views, viewsFreshness, resume, nextUp, latest)

    private fun show(h: Home, s: app.shoalwatch.data.servers.Session = BEN) { library.homeFlow(s).value = h }

    // --- who and where -------------------------------------------------------------------------------------------

    @Test fun nobodySignedInIsTheSignedOutState() {
        catalog.activeFlow.value = null
        val v = vm()
        assertEquals(HomePhase.SIGNED_OUT, v.state.value.phase)
        assertTrue(library.homeCollects.isEmpty())
    }

    @Test fun showsTheActiveServerUserAndLiveConnection() {
        connections.stateFor(SERVER_ID).value = online
        show(home(resume = row(entry(1))))
        val s = vm().state.value
        assertEquals(HomePhase.READY, s.phase)
        assertEquals("Den", s.serverName)
        assertEquals("ben", s.userName)
        assertEquals(app.shoalwatch.ui.ConnectionView.Kind.HOME, s.connection?.kind)
        assertEquals(BEN, s.session)
    }

    @Test fun warmFailureShowsLoadFailedUntilRetryWorks() {
        catalog.warmStillFailing = true
        val v = vm()
        assertEquals(HomePhase.LOAD_FAILED, v.state.value.phase)
        catalog.warmStillFailing = false
        v.retry()
        assertEquals(HomePhase.READY, v.state.value.phase)
    }

    @Test fun theConnectionRetryRefreshesTheConnection() {
        val v = vm()
        v.retry()
        assertEquals(1, connections.refreshes)
    }

    // --- rows ------------------------------------------------------------------------------------------------

    @Test fun rowsAndLibrariesArePassedThrough() {
        val resume = entry(1, name = "Resume me")
        val next = episode(2)
        val latestFilm = entry(3, name = "Fresh film")
        show(home(resume = row(resume), nextUp = row(next), latest = listOf(FILMS to row(latestFilm))))
        val s = vm().state.value
        assertEquals(listOf(resume), s.resume.items)
        assertEquals(listOf(next), s.nextUp.items)
        assertEquals(FILMS, s.latest.single().view)
        assertEquals(listOf(latestFilm), s.latest.single().row.items)
        assertEquals(listOf(FILMS, SHOWS), s.views)
        assertTrue(s.hasContent)
    }

    @Test fun nothingYetShowsLoadingNotEmpty() {
        val v = vm()
        // The library has not answered at all: still loading.
        library.homeFlow(BEN).value = home(views = emptyList()).copy(resume = row(f = Freshness.Refreshing), viewsFreshness = Freshness.Refreshing)
        assertTrue(v.state.value.stillLoading)
        assertTrue(v.state.value.resume.loading)
    }

    @Test fun anEmptyFreshHomeIsFirstRunEmptyNotLoading() {
        show(home(views = emptyList()))
        val s = vm().state.value
        assertFalse(s.hasContent)
        assertFalse(s.stillLoading)
    }

    // --- session ---------------------------------------------------------------------------------------------

    @Test fun switchingAccountRestartsTheFlowAndNothingOfTheOldOneRemains() {
        show(home(resume = row(entry(1, name = "Ben's film"))), BEN)
        show(home(resume = row(entry(9, name = "Amy's film"))), AMY)
        val v = vm()
        assertEquals("Ben's film", v.state.value.resume.items.single().item.name)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, v.state.value.session)
        assertEquals("Amy's film", v.state.value.resume.items.single().item.name)
        assertEquals(listOf(BEN, AMY), library.homeCollects)
        // A late change for the old session is never shown.
        show(home(resume = row(entry(2, name = "Ben late"))), BEN)
        assertEquals("Amy's film", v.state.value.resume.items.single().item.name)
        assertEquals("amy", v.state.value.userName)
    }

    @Test fun actionsAreRunAsTheCurrentSession() {
        show(home(resume = row(entry(1))), BEN)
        show(home(resume = row(entry(1))), AMY)
        val v = vm()
        catalog.activeFlow.value = account(AMY, "amy")
        v.setFavourite(id(1), true)
        assertEquals(listOf("favourite:amy:${id(1)}:true"), playstate.calls)
    }

    @Test fun accountsOfTheServerAreListedAndSwitchingCallsThrough() {
        val v = vm()
        assertEquals(listOf("ben", "amy"), v.state.value.accounts.map { it.userName })
        v.switchAccount(AMY.userId)
        assertEquals(listOf(SERVER_ID to AMY.userId), catalog.activated)
        assertEquals(AMY, v.state.value.session)
    }

    // --- freshness -> banner ---------------------------------------------------------------------------------

    private fun staleAll(kind: AppError.Kind) {
        val f = Freshness.Stale(kind)
        show(home(resume = row(entry(1), f = f), nextUp = row(entry(2), f = f), latest = listOf(FILMS to row(entry(3), f = f)), viewsFreshness = f))
    }

    @Test fun everyFailureKindMapsToItsBanner() {
        val expected = mapOf(
            AppError.Kind.OFFLINE to BannerKind.OFFLINE, AppError.Kind.NETWORK to BannerKind.OFFLINE, AppError.Kind.TIMEOUT to BannerKind.OFFLINE,
            AppError.Kind.UNAUTHORISED to BannerKind.SIGN_IN, AppError.Kind.NOT_SIGNED_IN to BannerKind.SIGN_IN,
            AppError.Kind.TLS_UNTRUSTED to BannerKind.CERTIFICATE, AppError.Kind.TLS_PIN_CHANGED to BannerKind.CERTIFICATE,
            AppError.Kind.SERVER_ERROR to BannerKind.GENERIC, AppError.Kind.UNKNOWN to BannerKind.GENERIC,
        )
        for ((kind, banner) in expected) {
            staleAll(kind)
            assertEquals(kind.name, banner, vm().state.value.topBanner)
        }
    }

    @Test fun theSameFailureOnEveryRowShowsOneBannerAtTheTopNotFive() {
        staleAll(AppError.Kind.OFFLINE)
        val s = vm().state.value
        assertEquals(BannerKind.OFFLINE, s.topBanner)
        assertNull(s.resume.banner)
        assertNull(s.nextUp.banner)
        assertNull(s.latest.single().row.banner)
        assertNull(s.viewsBanner)
        assertEquals(listOf(entry(1)), s.resume.items) // the saved content stays
    }

    @Test fun rowsThatFailDifferentlyEachShowTheirOwnBannerInline() {
        show(
            home(
                resume = row(entry(1), f = Freshness.Stale(AppError.Kind.OFFLINE)),
                nextUp = row(entry(2), f = Freshness.Stale(AppError.Kind.SERVER_ERROR)),
                latest = listOf(FILMS to row(entry(3))),
            ),
        )
        val s = vm().state.value
        assertNull(s.topBanner)
        assertEquals(BannerKind.OFFLINE, s.resume.banner)
        assertEquals(BannerKind.GENERIC, s.nextUp.banner)
        assertNull(s.latest.single().row.banner)
    }

    @Test fun aFailedLibrariesListShowsItsBannerWhileTheSavedLibrariesStay() {
        show(home(viewsFreshness = Freshness.Stale(AppError.Kind.TIMEOUT)))
        val s = vm().state.value
        assertEquals(BannerKind.OFFLINE, s.topBanner)
        assertEquals(listOf(FILMS, SHOWS), s.views)
    }

    @Test fun refreshingShowsTheProgressLineAndNoBanner() {
        show(home(resume = row(entry(1), f = Freshness.Refreshing)))
        val s = vm().state.value
        assertTrue(s.refreshing)
        assertNull(s.topBanner)
    }

    @Test fun staleWithNothingSavedIsAnEmptyErrorNotASpinner() {
        show(home(views = emptyList(), resume = row(f = Freshness.Stale(AppError.Kind.UNKNOWN)), viewsFreshness = Freshness.Stale(AppError.Kind.UNKNOWN)))
        val s = vm().state.value
        assertFalse(s.hasContent)
        assertFalse(s.stillLoading)
        assertEquals(BannerKind.GENERIC, s.topBanner)
    }

    // --- refresh triggers ------------------------------------------------------------------------------------

    @Test fun retryAndPullCallRefreshForHomeAsTheSession() {
        val v = vm()
        v.refresh()
        assertEquals(listOf(BEN to RefreshTarget.Home), library.refreshes)
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

    @Test fun regainingTheConnectionRefreshesOnceButOpeningWhileOnlineDoesNot() {
        connections.stateFor(SERVER_ID).value = online
        val v = vm()
        assertTrue(library.refreshes.isEmpty())
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        assertTrue(library.refreshes.isEmpty())
        connections.stateFor(SERVER_ID).value = online
        assertEquals(listOf(BEN to RefreshTarget.Home), library.refreshes)
        vm() // a plain re-open (re-collect) while online adds nothing
        assertEquals(1, library.refreshes.size)
        assertEquals(HomePhase.READY, v.state.value.phase)
    }

    @Test fun aNetworkChangeThroughCheckingCountsAsRegained() {
        connections.stateFor(SERVER_ID).value = online
        vm()
        connections.stateFor(SERVER_ID).value = Connection.Checking(previous = online)
        connections.stateFor(SERVER_ID).value = online
        assertEquals(1, library.refreshes.size)
    }

    @Test fun afterASuccessfulSignInTheNewHomeRefreshesOnce() {
        vm(refreshOnOpen = true)
        assertEquals(listOf(BEN to RefreshTarget.Home), library.refreshes)
        vm(refreshOnOpen = false)
        assertEquals(1, library.refreshes.size)
    }

    @Test fun aPlainCollectionDoesNotRefreshByItself() {
        val v = vm()
        assertTrue(library.refreshes.isEmpty())
        v.state.value
        assertTrue(library.refreshes.isEmpty())
    }

    // --- actions ---------------------------------------------------------------------------------------------

    @Test fun removingFromContinueWatchingCallsPlaystateAsTheSession() {
        show(home(resume = row(entry(1))))
        vm().removeFromResume(id(1))
        assertEquals(listOf("remove:ben:${id(1)}"), playstate.calls)
    }

    @Test fun markingWatchedAndFavouritingCallPlaystate() {
        val v = vm()
        v.setPlayed(id(1), true)
        v.setPlayed(id(1), false)
        v.setFavourite(id(2), true)
        assertEquals(listOf("played:ben:${id(1)}:true", "played:ben:${id(1)}:false", "favourite:ben:${id(2)}:true"), playstate.calls)
    }

    @Test fun aControlShowsWorkingUntilTheServerHasAnswered() {
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        val v = vm()
        v.setPlayed(id(1), true)
        assertEquals(setOf(Work(id(1), ActionKind.PLAYED)), v.state.value.working)
        v.setPlayed(id(1), true) // a second tap on the same control while it works is ignored
        assertEquals(1, playstate.calls.size)
        gate.complete(Unit)
        assertTrue(v.state.value.working.isEmpty())
    }

    @Test fun aFailureIsShownWithItsOwnMessageAndTheControlStopsWorking() = kotlinx.coroutines.test.runTest {
        playstate.failWith = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        v.messages.test {
            v.setFavourite(id(1), true)
            assertEquals(AppError.of(AppError.Kind.OFFLINE).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(v.state.value.working.isEmpty())
    }

    @Test fun anUnexpectedFailureStillGetsAPlainMessage() = kotlinx.coroutines.test.runTest {
        val v = HomeViewModel(catalog, connections, library, object : app.shoalwatch.data.library.Playstate {
            override suspend fun setPlayed(s: app.shoalwatch.data.servers.Session, itemId: String, played: Boolean) = error("boom")
            override suspend fun setFavourite(s: app.shoalwatch.data.servers.Session, itemId: String, favourite: Boolean) = error("boom")
            override suspend fun removeFromContinueWatching(s: app.shoalwatch.data.servers.Session, itemId: String) = error("boom")
        })
        v.messages.test {
            v.setPlayed(id(1), true)
            assertEquals(AppError.of(AppError.Kind.UNKNOWN).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun anEpisodeInContinueWatchingIsPassedThroughWithItsKind() {
        show(home(resume = row(episode(5))))
        assertEquals(ItemKind.EPISODE, vm().state.value.resume.items.single().item.kind)
    }
}
