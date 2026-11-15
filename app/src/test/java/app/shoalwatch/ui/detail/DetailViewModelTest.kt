package app.shoalwatch.ui.detail

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.RefreshTarget
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

class DetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val library = FakeLibrary()
    private val playstate = FakePlaystate()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val film = id(7)

    private fun vm() = DetailViewModel(film, catalog, connections, library, playstate)
    private fun publish(d: ItemDetail, s: app.shoalwatch.data.servers.Session = BEN) { library.itemFlow(s, film).value = d }

    @Test fun nobodySignedInIsTheSignedOutState() {
        catalog.activeFlow.value = null
        assertEquals(DetailPhase.SIGNED_OUT, vm().state.value.phase)
    }

    @Test fun showsTheItemOnceSaved() {
        publish(ItemDetail(entry(7, name = "The Film", data = userData(positionMs = 600_000)), Freshness.Fresh))
        val s = vm().state.value
        assertEquals(DetailPhase.READY, s.phase)
        assertEquals("The Film", s.item?.item?.name)
        assertEquals(600_000L, s.item?.userData?.positionMs)
        assertEquals(BEN, s.session)
    }

    @Test fun beforeTheFirstFetchItIsLoadingNotGone() {
        publish(ItemDetail(null, Freshness.Refreshing))
        val s = vm().state.value
        assertEquals(DetailPhase.LOADING, s.phase)
        assertTrue(s.refreshing)
    }

    @Test fun anItemTheServerNoLongerShowsIsTheGoneState() {
        publish(ItemDetail(null, Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)))
        assertEquals(DetailPhase.GONE, vm().state.value.phase)
    }

    @Test fun aSavedItemIsNotGoneEvenIfTheRefreshSaysNotFound() {
        publish(ItemDetail(entry(7), Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)))
        assertEquals(DetailPhase.READY, vm().state.value.phase)
    }

    @Test fun staleUnknownWithNoItemIsAnErrorStateWithRetryNotASpinner() {
        publish(ItemDetail(null, Freshness.Stale(AppError.Kind.UNKNOWN)))
        val s = vm().state.value
        assertEquals(DetailPhase.ERROR, s.phase)
        assertEquals(BannerKind.GENERIC, s.banner)
        assertFalse(s.refreshing)
    }

    @Test fun everyFailureMapsToItsBannerAndTheItemStays() {
        val expected = mapOf(
            AppError.Kind.OFFLINE to BannerKind.OFFLINE, AppError.Kind.NETWORK to BannerKind.OFFLINE, AppError.Kind.TIMEOUT to BannerKind.OFFLINE,
            AppError.Kind.UNAUTHORISED to BannerKind.SIGN_IN, AppError.Kind.NOT_SIGNED_IN to BannerKind.SIGN_IN,
            AppError.Kind.TLS_UNTRUSTED to BannerKind.CERTIFICATE, AppError.Kind.TLS_PIN_CHANGED to BannerKind.CERTIFICATE,
            AppError.Kind.SERVER_ERROR to BannerKind.GENERIC,
        )
        for ((kind, banner) in expected) {
            publish(ItemDetail(entry(7), Freshness.Stale(kind)))
            val s = vm().state.value
            assertEquals(kind.name, banner, s.banner)
            assertEquals(DetailPhase.READY, s.phase)
        }
    }

    @Test fun refreshingKeepsTheItemAndShowsNoBanner() {
        publish(ItemDetail(entry(7), Freshness.Refreshing))
        val s = vm().state.value
        assertTrue(s.refreshing)
        assertNull(s.banner)
        assertEquals(DetailPhase.READY, s.phase)
    }

    @Test fun switchingAccountRestartsAndNothingOfTheOldOneRemains() {
        publish(ItemDetail(entry(7, name = "Ben view"), Freshness.Fresh))
        publish(ItemDetail(entry(7, name = "Amy view"), Freshness.Fresh), AMY)
        val v = vm()
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals("Amy view", v.state.value.item?.item?.name)
        publish(ItemDetail(entry(7, name = "Ben late"), Freshness.Fresh))
        assertEquals("Amy view", v.state.value.item?.item?.name)
        assertEquals(listOf(BEN to film, AMY to film), library.itemCollects)
    }

    @Test fun retryAndPullRefreshTheItem() {
        val gate = CompletableDeferred<Unit>()
        library.onRefresh = { _, _ -> gate.await() }
        val v = vm()
        v.refresh()
        assertEquals(listOf(BEN to RefreshTarget.Item(film)), library.refreshes)
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
        assertEquals(listOf(BEN to RefreshTarget.Item(film)), library.refreshes)
        vm()
        assertEquals(1, library.refreshes.size)
    }

    @Test fun watchedAndFavouriteCallPlaystateAndShowWorkingUntilDone() = kotlinx.coroutines.test.runTest {
        val gate = CompletableDeferred<Unit>()
        playstate.gate = gate
        playstate.failWith = AppError.of(AppError.Kind.TIMEOUT)
        val v = vm()
        v.messages.test {
            v.setPlayed(true)
            v.setFavourite(true)
            assertEquals(setOf(Work(film, ActionKind.PLAYED), Work(film, ActionKind.FAVOURITE)), v.state.value.working)
            gate.complete(Unit)
            assertEquals(AppError.of(AppError.Kind.TIMEOUT).userMessage, awaitItem())
            assertEquals(AppError.of(AppError.Kind.TIMEOUT).userMessage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("played:ben:$film:true", "favourite:ben:$film:true"), playstate.calls)
        assertTrue(v.state.value.working.isEmpty())
    }

    @Test fun anEpisodeKeepsItsSeriesForGoToSeries() {
        publish(ItemDetail(episode(7, season = 2, number = 5), Freshness.Fresh))
        val item = vm().state.value.item!!.item
        assertEquals(id(50), item.seriesId)
        assertEquals(id(62), item.seasonId)
    }
}
