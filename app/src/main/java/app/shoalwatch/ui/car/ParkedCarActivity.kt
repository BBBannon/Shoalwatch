package app.shoalwatch.ui.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shoalwatch.AppGraph
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.ui.player.VideoSurface
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.Dispatchers

/**
 * Shoalwatch on the Android Auto car screen, as a **parked app** (Milestone 9; see "Android Auto (parked)" in
 * docs/ARCHITECTURE.md). Android Auto finds it by its `MAIN` + `CAR_LAUNCHER` filter, runs it on the car display only while parked,
 * and covers it when the car moves off (that pauses playback, which never resumes by itself).
 *
 * - It reads nothing from its intent (any app could start it) and has no Navigation (which would act on an intent's data).
 * - It shows the phone's active profile only while the lock is open for it ([CarViewModel]); no PIN is typed here.
 * - Shown (resumed) counts as the app being in front for "Lock after", through the car's side of `AppFront`.
 * - Its window is NEVER secure: Android Auto blanks a `FLAG_SECURE` window on the car display, so nothing here applies
 *   `RecentsPrivacy` or a secure window (`CarBoundaryTest`).
 */
class ParkedCarActivity : ComponentActivity() {
    private val graph: AppGraph get() = ShoalwatchApp.graph(this)
    private val vm: CarViewModel by viewModels { viewModelFactory { initializer { carViewModel(graph) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val subtitles = graph.subtitleSettings
        setContent {
            ShoalwatchTheme {
                // At once on the main thread, as the phone's lock cover is: a lock set in onResume covers the next frame.
                val access by vm.access.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
                val lists by vm.lists.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
                val player by vm.player.collectAsStateWithLifecycle()
                val appearance by subtitles.appearance.collectAsStateWithLifecycle()
                val actions = remember(vm) {
                    CarActions(
                        onPlay = vm::play, onPlayPause = vm::playPause, onBackTen = { vm.skipBy(-CarViewModel.BACK_MS) },
                        onForwardThirty = { vm.skipBy(CarViewModel.FORWARD_MS) }, onTapPicture = vm::tapPicture, onBack = vm::back,
                    )
                }
                CarScreen(access, lists, player, actions) {
                    VideoSurface(vm.video, vm.cues, appearance, controlsUp = player?.controlsVisible == true)
                }
            }
        }
    }

    /** On the car screen: a return for the lock first (it may lock now), then the controls show (playback stays paused). */
    override fun onResume() {
        super.onResume()
        graph.lockParts.carTrips.onReturned()
        vm.onHostResumed()
    }

    /** Android Auto took the screen (the car moved off, another app, the car's own menu): pause, and leave for the lock. */
    override fun onPause() {
        super.onPause()
        if (isChangingConfigurations) return
        vm.onHostPaused()
        graph.lockParts.carTrips.onLeft()
    }

    companion object {
        /** The car's ViewModel over the app's own data layer and player: the phone's active profile, its lock, and its playback rules. */
        internal fun carViewModel(graph: AppGraph): CarViewModel {
            val gate = graph.lockParts.lockGate
            return CarViewModel(
                graph.serverCatalog.activeSession(), gate.state, gate.openFor, graph.library, graph.downloads,
                { s, scope -> graph.playbackController(s, scope, graph.lockParts.profilePlayback.autoplayReader(s, scope)) },
                graph.qualityDefault::start,
            )
        }
    }
}
