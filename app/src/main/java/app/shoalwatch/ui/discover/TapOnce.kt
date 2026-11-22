package app.shoalwatch.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Lets one tap through until the screen is on top again, so a double tap on a tile opens its page once. */
class TapOnce {
    private var used = false

    fun once(action: () -> Unit) {
        if (used) return
        used = true
        action()
    }

    fun reset() { used = false }
}

@Composable
fun rememberTapOnce(): TapOnce {
    val tap = remember { TapOnce() }
    LifecycleResumeEffect(tap) {
        tap.reset()
        onPauseOrDispose {}
    }
    return tap
}
