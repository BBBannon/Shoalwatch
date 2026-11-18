package app.shoalwatch.ui.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.OfflineSeason
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.downloads.SeriesSummary
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.HomeRow
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.FakePlayerEngine
import app.shoalwatch.data.playback.PlaybackControllerTestBase
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Judged
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The car screen's ViewModel (Milestone 9) over a real `PlaybackController` and `ActivePlayback` on the playback fakes: what it may
 * show for each lock state, its lists, its player, and one playback at a time between the phone and the car.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CarViewModelTest : PlaybackControllerTestBase() {
    private val dispatcher = StandardTestDispatcher()
    private val active = MutableStateFlow<Session?>(BEN)
    private val lock = MutableStateFlow<LockState>(LockState.Open)
    private val openFor = MutableStateFlow<Judged?>(Judged(BEN))
    private val downloads = FakeDownloads()
    private val store = ViewModelStore()
    private val carEngine = FakePlayerEngine().also { it.name = "car" }
    private val film = ItemWithUserData(item(70, ItemKind.MOVIE, "Film 70"), null)

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() { store.clear(); Dispatchers.resetMain() }

    private fun TestScope.vm(): CarViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = CarViewModel(
                active, lock, openFor, library, downloads, { s, _ -> controllerFor(s, backgroundScope, carEngine) }, { Quality.MAXIMUM },
            ) as T
        }
        return ViewModelProvider.create(store, factory)[CarViewModel::class.java].also { it.setOnPhoneDisplay(false); runCurrent() }
    }

    private fun entry(i: ItemWithUserData, state: DownloadState = DownloadState.COMPLETE, available: Boolean = true) =
        OfflineEntry(i, DownloadInfo(i.item.id, state, null, DownloadQuality.ORIGINAL, 1f, 100, true, StorageKind.INTERNAL, available))

    private fun homeWith(resume: List<ItemWithUserData>, nextUp: List<ItemWithUserData>, freshness: Freshness = Freshness.Fresh) =
        Home(emptyList(), freshness, HomeRow(resume, freshness), HomeRow(nextUp, freshness), emptyList())

    private fun TestScope.carPlaying(vm: CarViewModel, itemId: String = ep1) {
        vm.play(itemId); runCurrent()
        carEngine.durationMs = runtime
        carEngine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, vm.player.value?.playback?.phase)
    }

    /** The car's engine at [ms] for one controller tick. */
    private fun TestScope.carAt(ms: Long) { carEngine.positionMs = ms; testScheduler.advanceTimeBy(250); runCurrent() }

    @Test fun whatTheCarMayShowFollowsTheLockForExactlyTheActiveProfile() {
        assertEquals(CarAccess.Waiting, CarViewModel.accessOf(BEN, LockState.Checking, null))
        assertEquals(CarAccess.Locked, CarViewModel.accessOf(BEN, LockState.App, null))
        assertEquals(CarAccess.Locked, CarViewModel.accessOf(BEN, LockState.Profile(BEN, "Ben"), null))
        assertEquals("not judged yet", CarAccess.Waiting, CarViewModel.accessOf(BEN, LockState.Open, null))
        assertEquals("judged for another profile", CarAccess.Waiting, CarViewModel.accessOf(AMY, LockState.Open, Judged(BEN)))
        assertEquals(CarAccess.SignIn, CarViewModel.accessOf(null, LockState.Open, Judged(null)))
        assertEquals(CarAccess.Open(BEN), CarViewModel.accessOf(BEN, LockState.Open, Judged(BEN)))
    }

    @Test fun lockedTheCarShowsTheMessageAndReadsNothingOfTheProfileThenOpensLiveWhenThePhoneUnlocks() = runTest(dispatcher) {
        lock.value = LockState.Profile(BEN, "Ben"); openFor.value = null
        library.homeFlow(BEN).value = homeWith(listOf(episode(101)), listOf(episode(102)))
        val vm = vm()
        assertEquals(CarAccess.Locked, vm.access.value)
        assertNull(vm.lists.value)
        assertTrue("the locked profile's lists are never read", library.homeCollects.isEmpty())
        vm.play(ep1); runCurrent()
        assertNull("nothing plays while locked", vm.player.value)
        lock.value = LockState.Open; openFor.value = Judged(BEN); runCurrent() // unlocked on the phone
        assertEquals(CarAccess.Open(BEN), vm.access.value)
        assertEquals(listOf(ep1), vm.lists.value?.resume?.map { it.item.id })
        assertEquals(listOf(ep2), vm.lists.value?.nextUp?.map { it.item.id })
    }

    @Test fun signedOutOnThePhoneTheCarAsksToSignInThere() = runTest(dispatcher) {
        active.value = null; openFor.value = Judged(null)
        val vm = vm()
        assertEquals(CarAccess.SignIn, vm.access.value)
        assertNull(vm.lists.value)
    }

    @Test fun downloadsListOnlyCompleteCopiesWhoseStorageIsInThePhoneFilmsThenEpisodes() = runTest(dispatcher) {
        val series = ItemWithUserData(item(50, ItemKind.SERIES, "Series 50"), null)
        downloads.libraryFlow(BEN).value = OfflineLibrary(
            inProgress = listOf(entry(episode(103), DownloadState.RUNNING)),
            films = listOf(entry(film), entry(ItemWithUserData(item(71, ItemKind.MOVIE, "On a card"), null), available = false)),
            series = listOf(SeriesSummary(series, 2, 200)),
        )
        downloads.seriesFlow(BEN, id(50)).value = OfflineSeries(series, listOf(OfflineSeason(null, 1, listOf(entry(episode(101)), entry(episode(102), DownloadState.FAILED)))))
        val vm = vm()
        assertEquals(listOf(film.item.id, ep1), vm.lists.value?.downloads?.map { it.item.id })
    }

    @Test fun aLockWhilePlayingStopsTheCarsPlayerAndSendsItsStop() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm); carAt(60_000)
        lock.value = LockState.App; openFor.value = null; runCurrent()
        assertNull(vm.player.value)
        assertTrue(carEngine.released)
        assertEquals(listOf(BEN), reports.stopSessions)
    }

    @Test fun anotherProfileOnThePhoneEndsTheCarsPlayer() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm)
        activePlayback.sessionChanged(AMY); active.value = AMY; openFor.value = Judged(AMY); runCurrent()
        assertNull(vm.player.value)
        assertTrue(carEngine.released)
        assertNull("Amy's lists are not Ben's", vm.lists.value?.resume?.firstOrNull())
    }

    @Test fun startingOnThePhoneEndsTheCarsPlaybackAndTheCarGoesBackToItsList() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm)
        val phone = controllerFor(session, backgroundScope, engine)
        phone.open(ep2, StartFrom.RESUME); runCurrent()
        assertNull("the car is back at its list", vm.player.value)
        assertTrue(carEngine.released)
        assertFalse(engine.released)
        assertEquals(1, engine.loads.size)
        assertEquals(listOf(BEN), reports.stopSessions) // the car's stop
    }

    @Test fun startingOnTheCarEndsThePhonesPlayback() = runTest(dispatcher) {
        val phone = controllerFor(session, backgroundScope, engine)
        phone.open(ep2, StartFrom.RESUME); runCurrent()
        engine.startPlaying(); runCurrent()
        val vm = vm(); carPlaying(vm)
        assertTrue("the phone's player has ended", phone.state.value.closed)
        assertTrue(engine.released)
        assertFalse(carEngine.released)
        assertNotNull(vm.player.value)
    }

    @Test fun backStopsSendsTheStopAndReturnsToTheList() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm); carAt(30_000)
        vm.back(); runCurrent()
        assertNull(vm.player.value)
        assertTrue(carEngine.released)
        assertEquals(listOf(BEN), reports.stopSessions)
    }

    @Test fun theControlsPauseAndSkipBackTenAndForwardThirty() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm); carAt(100_000)
        vm.skipBy(-CarViewModel.BACK_MS); runCurrent()
        assertEquals(90_000L, carEngine.seeks.last())
        vm.skipBy(CarViewModel.FORWARD_MS); runCurrent()
        assertEquals(120_000L, carEngine.seeks.last())
        vm.playPause(); runCurrent()
        assertEquals(Phase.PAUSED, vm.player.value?.playback?.phase)
        assertTrue("paused, the controls stay up", vm.player.value!!.controlsVisible)
    }

    @Test fun aTapShowsOrHidesTheControlsAndNeverPauses() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm)
        assertTrue(vm.player.value!!.controlsVisible)
        vm.tapPicture(); runCurrent()
        assertFalse(vm.player.value!!.controlsVisible)
        vm.tapPicture(); runCurrent()
        assertTrue(vm.player.value!!.controlsVisible)
        assertEquals(Phase.PLAYING, vm.player.value?.playback?.phase)
        testScheduler.advanceTimeBy(6_000); runCurrent()
        assertFalse("they hide by themselves while playing", vm.player.value!!.controlsVisible)
    }

    /** Review I1: on the phone's own display the car activity shows a message only, reads nothing and plays nothing. */
    @Test fun onThePhonesOwnDisplayItShowsNothingOfAnyProfileAndStopsPlaying() = runTest(dispatcher) {
        library.homeFlow(BEN).value = homeWith(listOf(episode(101)), emptyList())
        val vm = vm(); carPlaying(vm)
        vm.setOnPhoneDisplay(true); runCurrent()
        assertEquals(CarAccess.PhoneDisplay, vm.access.value)
        assertNull(vm.lists.value)
        assertNull(vm.player.value)
        assertTrue(carEngine.released)
        vm.play(ep1); runCurrent()
        assertNull("nothing plays there", vm.player.value)
        vm.setOnPhoneDisplay(false); runCurrent()
        assertEquals(CarAccess.Open(BEN), vm.access.value)
    }

    @Test fun startedOnThePhonesOwnDisplayTheProfilesListsAreNeverRead() = runTest(dispatcher) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = CarViewModel(
                active, lock, openFor, library, downloads, { s, _ -> controllerFor(s, backgroundScope, carEngine) }, { Quality.MAXIMUM },
            ) as T
        }
        val vm = ViewModelProvider.create(store, factory)[CarViewModel::class.java]; runCurrent() // never told: fails closed
        assertEquals(CarAccess.PhoneDisplay, vm.access.value)
        assertTrue(library.homeCollects.isEmpty())
    }

    /**
     * Review I2: a direct change from Ben (open) to Amy (open) must never pair Amy's access with Ben's lists, even before Amy's
     * lists have loaded (Amy's Home here never answers).
     */
    @Test fun aProfileChangeNeverShowsThePreviousProfilesListsUnderTheNewOne() = runTest(dispatcher) {
        library.homeFlow(BEN).value = homeWith(listOf(episode(101)), listOf(episode(102)))
        val slowLibrary = object : app.shoalwatch.data.library.Library by library {
            override fun home(s: Session) = if (s == AMY) kotlinx.coroutines.flow.flow<Home> { kotlinx.coroutines.awaitCancellation() } else library.home(s)
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = CarViewModel(
                active, lock, openFor, slowLibrary, downloads, { s, _ -> controllerFor(s, backgroundScope, carEngine) }, { Quality.MAXIMUM },
            ) as T
        }
        val vm = ViewModelProvider.create(store, factory)[CarViewModel::class.java].also { it.setOnPhoneDisplay(false) }; runCurrent()
        assertEquals(listOf(ep1), vm.lists.value?.resume?.map { it.item.id })
        active.value = AMY; openFor.value = Judged(AMY); runCurrent()
        assertEquals(CarAccess.Open(AMY), vm.access.value)
        val shown = vm.lists.value
        assertTrue("Ben's lists are not shown as Amy's", shown == null || shown.session == AMY)
    }

    @Test fun losingTheCarScreenPausesAndItNeverPlaysAgainByItself() = runTest(dispatcher) {
        val vm = vm(); carPlaying(vm)
        vm.onHostPaused(); runCurrent()
        assertFalse(carEngine.isPlaying)
        vm.onHostResumed(); runCurrent()
        assertFalse(carEngine.isPlaying)
        assertEquals(Phase.PAUSED, vm.player.value?.playback?.phase)
    }
}
