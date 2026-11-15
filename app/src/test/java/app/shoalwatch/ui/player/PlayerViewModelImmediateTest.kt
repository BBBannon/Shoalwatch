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
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ViewModel with Main as the app has it (`Main.immediate`: a state set from a click on the main thread reaches the
 * collector at once, inside the call), which `UnconfinedTestDispatcher` stands for. Task 8 review I-2: turning the sleep
 * timer off must not read as the timer firing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class PlayerViewModelImmediateTest : PlaybackControllerTestBase() {
    private val dispatcher = UnconfinedTestDispatcher()
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SubtitleSettings(context.getSharedPreferences("player_vm_immediate_test", Context.MODE_PRIVATE)) { CaptionDefaults(false, null, 1f) }
    private val store = ViewModelStore()

    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun reset() = Dispatchers.resetMain()

    private fun TestScope.vm(): PlayerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                PlayerViewModel(ep1, StartFrom.RESUME, SavedStateHandle(), catalog, { s, _ -> controllerFor(s, backgroundScope) }, settings) as T
        }
        return ViewModelProvider.create(store, factory)[PlayerViewModel::class.java].also { runCurrent() }
    }

    @Test fun turningTheSleepTimerOffIsNotThePlayerPausedByIt() = runTest(dispatcher) {
        val vm = vm()
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
        assertEquals(Phase.PLAYING, vm.state.value.playback.phase)
        vm.setSleep(SleepChoice.MIN_15)
        vm.setSleep(SleepChoice.OFF)
        runCurrent()
        assertFalse(vm.state.value.sleepFired)
        vm.setSleep(SleepChoice.MIN_30)
        vm.setSleep(SleepChoice.END_OF_ITEM)
        vm.setSleep(SleepChoice.OFF)
        runCurrent()
        assertFalse(vm.state.value.sleepFired)
        // And a real firing is still said.
        vm.setSleep(SleepChoice.MIN_15)
        clock.ms += 15 * 60_000L
        advanceTimeBy(250); runCurrent()
        assertTrue(vm.state.value.sleepFired)
        store.clear()
    }
}
