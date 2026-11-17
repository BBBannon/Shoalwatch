package app.shoalwatch.ui.lock

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.ui.platform.ComposeView
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.nav.collectAtOnce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tasks 5-6 review M2: the gate sets its state on the main thread in `onStart`. The UI's copy must change within that very call, not
 * one dispatch later, so the first frame drawn after the return is already covered. A real activity and the real main dispatcher.
 *
 * The composition runs on its own [Recomposer] over plain `Dispatchers.Main` (which posts, never runs at once), not on Compose's
 * per-thread UI dispatcher: that one is shared by every test on Robolectric's main thread and, in the full suite, was sometimes
 * left unable to run anything (the collector never started, in about half the runs). Plain `Main` keeps the test discriminating:
 * a collector without `Main.immediate` would resume one post later and the second check would fail.
 */
@RunWith(RobolectricTestRunner::class)
class LockStateAtOnceTest {
    @Test fun aLockSetOnTheMainThreadIsSeenBeforeTheCallReturns() {
        Dispatchers.resetMain() // the real main dispatcher, whatever an earlier test in this JVM left behind
        val gate = MutableStateFlow<LockState>(LockState.Open)
        lateinit var seen: State<LockState>
        val job = Job()
        val context = Dispatchers.Main + BroadcastFrameClock() + job
        val recomposer = Recomposer(context)
        CoroutineScope(context).launch { recomposer.runRecomposeAndApplyChanges() }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val view = ComposeView(controller.get()).apply { setParentCompositionContext(recomposer) }
            controller.get().setContentView(view)
            view.setContent { seen = gate.collectAtOnce() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("the UI is collecting the gate", 1, gate.subscriptionCount.value)
            assertEquals(LockState.Open, seen.value)
            val locked = LockState.Profile(Session("s1", "u1"), "Ben")
            gate.value = locked // as `onReturned` does in `onStart`, on the main thread
            assertEquals("seen inside the same main-thread call", locked, seen.value)
        } finally {
            controller.pause().stop().destroy()
            recomposer.cancel()
            job.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
