package app.shoalwatch.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The window while the player shows: landscape by the sensor (the lock pins the current orientation), system bars hidden until
 * swiped in, the screen kept on while [keepOn] (playing or buffering; `View.keepScreenOn`, so no `WAKE_LOCK` permission). All
 * of it is put back when the player goes. Not set: `FLAG_SECURE` (screenshots of the player are allowed).
 *
 * A configuration change (the switch to landscape itself, split screen, dark mode, font size) recreates the activity: that is
 * not leaving the player, so nothing is put back then (Task 8 review I-1); the new composition sets it all again.
 */
@Composable
internal fun PlayerWindow(keepOn: Boolean, locked: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (activity?.isChangingConfigurations == true) return@onDispose
            bars?.show(WindowInsetsCompat.Type.systemBars())
            // The app's own setting (none in the manifest): whatever the sensor says, as on every other screen.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
        }
    }
    DisposableEffect(activity, locked) {
        activity?.requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { }
    }
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { }
    }
}

/** The host stopping (screen off, app left) and starting again, for the ViewModel; a configuration change is neither. */
@Composable
internal fun HostLifecycle(onStopped: () -> Unit, onStarted: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val stopped by rememberUpdatedState(onStopped)
    val started by rememberUpdatedState(onStarted)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (activity?.isChangingConfigurations == true) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped()
                Lifecycle.Event.ON_START -> started()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Tells [onChange] whether TalkBack's touch exploration is on now, and again whenever it changes. */
@Composable
internal fun TouchExploration(onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val latest by rememberUpdatedState(onChange)
    DisposableEffect(view) {
        val manager = view.context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { latest(it) }
        latest(manager?.isTouchExplorationEnabled == true)
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
}

/** Whether this screen shows Dolby Vision (for the stream-info line); false when it cannot be read. */
@Suppress("DEPRECATION") // HdrCapabilities.getSupportedHdrTypes: deprecated on API 34, still answers
@Composable
internal fun rememberDolbyVisionScreen(): Boolean {
    val view = LocalView.current
    return remember(view) {
        runCatching { view.display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true }.getOrDefault(false)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
