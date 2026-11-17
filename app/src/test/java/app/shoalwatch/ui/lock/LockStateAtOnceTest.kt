package app.shoalwatch.ui.lock

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.State
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.nav.collectAtOnce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tasks 5-6 review M2: the gate sets its state on the main thread in `onStart`. The UI's copy must change within that very call, not
 * one dispatch later, so the first frame drawn after the return is already covered. A real activity and the real UI dispatcher (the
 * compose test rule's own dispatcher would hide the delay).
 */
@RunWith(RobolectricTestRunner::class)
class LockStateAtOnceTest {
    @Test fun aLockSetOnTheMainThreadIsSeenBeforeTheCallReturns() {
        Dispatchers.resetMain() // the real main dispatcher, whatever an earlier test in this JVM left behind
        val gate = MutableStateFlow<LockState>(LockState.Open)
        lateinit var seen: State<LockState>
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { seen = gate.collectAtOnce() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LockState.Open, seen.value)
            val locked = LockState.Profile(Session("s1", "u1"), "Ben")
            gate.value = locked // as `onReturned` does in `onStart`, on the main thread
            assertEquals("seen inside the same main-thread call", locked, seen.value)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
