package app.shoalwatch.ui.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.CaptionDefaults
import app.shoalwatch.data.playback.PlaybackControllerTestBase
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player screen's ViewModel over a real [app.shoalwatch.data.playback.PlaybackController] on the playback fakes
 * ([PlaybackControllerTestBase]): who owns playback, leaving when the session ends, the controls hiding, restored state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class PlayerViewModelTest : PlaybackControllerTestBase() {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SubtitleSettings(context.getSharedPreferences("player_vm_test", Context.MODE_PRIVATE)) { CaptionDefaults(false, null, 1f) }
    private val made = mutableListOf<Session>()
    private val store = ViewModelStore()

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private fun TestScope.vm(saved: SavedStateHandle = SavedStateHandle(), from: StartFrom = StartFrom.RESUME): PlayerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                // The controller runs on the test's background scope: its endless tick loops would keep a ViewModel scope from ever going idle.
                PlayerViewModel(ep1, from, saved, catalog, { s, _ -> made += s; controllerFor(s, backgroundScope) }, settings) as T
        }
        return ViewModelProvider.create(store, factory)[PlayerViewModel::class.java].also { runCurrent() }
    }

    private fun TestScope.playing(vm: PlayerViewModel) {
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
    }

    private fun TestScope.switchTo(s: Session?) { catalog.activeFlow.value = s?.let { account(it, "x") }; runCurrent() }

    @Test fun theFirstSessionOwnsPlayback() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(listOf(BEN), made)
        assertEquals(BEN, vm.state.value.session)
        assertEquals(1, engine.loads.size)
        playing(vm)
        assertEquals("Episode 101", vm.state.value.playback.title)
        assertFalse(vm.state.value.leave)
    }

    @Test fun switchingAccountEndsPlaybackAsTheOldSessionAndLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        activePlayback.sessionChanged(AMY) // the graph's own hook, as in the app; the screen must leave either way
        switchTo(AMY)
        val s = vm.state.value
        assertTrue(s.leave)
        assertTrue(engine.released)
        assertEquals(PlaybackState.CLOSED, s.playback)
        assertNull(s.playback.title); assertNull(s.playback.itemId); assertEquals(0L, s.playback.positionMs); assertNull(s.playback.next)
        assertEquals(listOf(BEN), made)
        assertEquals(listOf(session), reports.stopSessions)
    }

    @Test fun switchingAccountEndsPlaybackEvenBeforeTheGraphsHookRuns() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        switchTo(AMY)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertEquals("the old account is still signed in: its stop is sent as itself", listOf(session), reports.stopSessions)
    }

    @Test fun signingOutEndsPlaybackSendsNothingAndLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        catalog.accountList.value = listOf(account(AMY, "amy"))
        switchTo(null)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertTrue(reports.calls.none { it.startsWith("stopped") })
    }

    @Test fun anAccountRemovedWhileAnotherBecomesActiveSendsNothing() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        catalog.accountList.value = listOf(account(AMY, "amy"))
        switchTo(AMY)
        assertTrue(vm.state.value.leave); assertTrue(engine.released)
        assertTrue(reports.calls.none { it.startsWith("stopped") })
    }

    @Test fun nobodySignedInPlaysNothingAndLeaves() = runTest(dispatcher) {
        catalog.activeFlow.value = null
        val vm = vm()
        assertTrue(vm.state.value.leave)
        assertTrue(made.isEmpty()); assertTrue(engine.loads.isEmpty())
    }

    @Test fun aClosedControllerLeaves() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        activePlayback.end(BEN.serverId, BEN.userId); runCurrent()
        assertTrue(vm.state.value.leave)
    }

    @Test fun theControlsHideAfterFourSecondsOnlyWhilePlaying() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.controlsVisible)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("not playing yet: stays", vm.state.value.controlsVisible)
        playing(vm)
        advanceTimeBy(3_900); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        advanceTimeBy(200); runCurrent()
        assertFalse(vm.state.value.controlsVisible)
        vm.tapSurface(); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.playPause(); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        advanceTimeBy(10_000); runCurrent()
        assertTrue("paused: stays", vm.state.value.controlsVisible)
    }

    @Test fun theControlsNeverHideWithTouchExplorationOrASheetOpen() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.setTouchExploration(true)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.setTouchExploration(false)
        vm.openSheet(PlayerSheet.SPEED)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.state.value.controlsVisible)
        vm.closeSheet()
        advanceTimeBy(4_100); runCurrent()
        assertFalse(vm.state.value.controlsVisible)
    }

    @Test fun theOwnerAndThePositionAreSavedButNoTitle() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved); playing(vm); at(42_000)
        assertEquals(BEN.serverId, saved.get<String>(PlayerViewModel.KEY_SERVER))
        assertEquals(BEN.userId, saved.get<String>(PlayerViewModel.KEY_USER))
        assertEquals(42_000L, saved.get<Long>(PlayerViewModel.KEY_POSITION))
        assertEquals(ep1, saved.get<String>(PlayerViewModel.KEY_ITEM))
        assertEquals(setOf(PlayerViewModel.KEY_SERVER, PlayerViewModel.KEY_USER, PlayerViewModel.KEY_ITEM, PlayerViewModel.KEY_POSITION), saved.keys())
    }

    @Test fun afterNextTheSavedPositionIsTheNewEpisodesAndNotTheOldOnes() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved); playing(vm); at(500_000)
        vm.next(); runCurrent()
        assertEquals(ep2, saved.get<String>(PlayerViewModel.KEY_ITEM))
        assertNull("the old episode's position is not kept for the new one", saved.get<Long>(PlayerViewModel.KEY_POSITION))
        engine.startPlaying(); runCurrent(); at(600_000)
        assertEquals(600_000L, saved.get<Long>(PlayerViewModel.KEY_POSITION))
    }

    @Test fun restoredAfterUpNextReopensTheEpisodeThatWasPlayingAtItsPosition() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(
            PlayerViewModel.KEY_SERVER to BEN.serverId, PlayerViewModel.KEY_USER to BEN.userId,
            PlayerViewModel.KEY_ITEM to ep2, PlayerViewModel.KEY_POSITION to 600_000L,
        ))
        val vm = vm(saved) // the route still names ep1, the episode first opened
        assertEquals(ep2, vm.state.value.playback.itemId)
        assertEquals(listOf(600_000L), engine.seeks)
    }

    @Test fun restoredForAnotherSessionPlaysNothingAndLeaves() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(PlayerViewModel.KEY_SERVER to AMY.serverId, PlayerViewModel.KEY_USER to AMY.userId, PlayerViewModel.KEY_POSITION to 90_000L))
        val vm = vm(saved)
        assertTrue(vm.state.value.leave)
        assertTrue(made.isEmpty()); assertTrue(engine.loads.isEmpty())
    }

    @Test fun restoredForTheSameSessionGoesBackToTheSavedPosition() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(PlayerViewModel.KEY_SERVER to BEN.serverId, PlayerViewModel.KEY_USER to BEN.userId, PlayerViewModel.KEY_ITEM to ep1, PlayerViewModel.KEY_POSITION to 90_000L))
        vm(saved)
        assertEquals(listOf(90_000L), engine.seeks)
    }

    @Test fun leavingTheScreenEndsPlaybackAndSendsTheStop() = runTest(dispatcher) {
        val vm = vm(); playing(vm); at(30_000)
        store.clear(); runCurrent()
        assertTrue(engine.released)
        assertEquals(listOf("stopped@30000"), stopCalls)
        assertFalse(vm.state.value.leave)
    }

    @Test fun theHostStoppingPauses() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.onHostStopped(); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        assertFalse(engine.isPlaying)
    }

    @Test fun aSleepTimerThatFiresIsSaidOnceButTurningItOffIsNot() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        vm.setSleep(SleepChoice.MIN_15); runCurrent()
        vm.setSleep(SleepChoice.OFF); runCurrent()
        assertFalse(vm.state.value.sleepFired)
        vm.setSleep(SleepChoice.MIN_15); runCurrent()
        clock.ms = 15 * 60_000L + 1
        advanceTimeBy(250); runCurrent()
        assertEquals(Phase.PAUSED, vm.state.value.playback.phase)
        assertTrue(vm.state.value.sleepFired)
        vm.dismissSleepFired()
        assertFalse(vm.state.value.sleepFired)
    }

    @Test fun theStreamLineShowsForFourSecondsAtTheStart() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        assertTrue(vm.state.value.streamInfoShown)
        advanceTimeBy(4_100); runCurrent()
        assertFalse(vm.state.value.streamInfoShown)
    }

    @Test fun tryAgainOpensTheItemAgain() = runTest(dispatcher) {
        val vm = vm(); playing(vm)
        engine.emit(EngineEvent.Failed(app.shoalwatch.data.playback.engine.EngineFailure.OTHER)); runCurrent()
        assertEquals(Phase.FAILED, vm.state.value.playback.phase)
        vm.retry(); runCurrent()
        assertEquals(2, engine.loads.size)
    }

    @Test fun lockingKeepsTheControlsFromHidingNothingButTogglesIt() = runTest(dispatcher) {
        val vm = vm()
        vm.toggleLock(); assertTrue(vm.state.value.locked)
        vm.toggleLock(); assertFalse(vm.state.value.locked)
    }
}
