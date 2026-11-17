package app.shoalwatch.ui

import android.content.ComponentName
import android.content.Intent
import app.shoalwatch.BuildConfig
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.shoalwatch.ShoalwatchApp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.shoalwatch.data.downloads.DownloadNotifications
import app.shoalwatch.security.AppTrips
import app.shoalwatch.ui.components.LocalArtworkLoader
import app.shoalwatch.ui.nav.AppRoot
import app.shoalwatch.ui.player.ActivityPip
import app.shoalwatch.ui.player.LocalPipHost
import app.shoalwatch.ui.player.PipHost
import app.shoalwatch.ui.player.PipWish
import kotlinx.coroutines.flow.StateFlow
import app.shoalwatch.ui.theme.ShoalwatchTheme

/**
 * The only activity: edge to edge, with every screen inside [AppRoot]'s navigation. The download notice opens it with
 * `open_tab=downloads`; that exact value is the only one it acts on ([requestedTab]). It tells `LockGate` when the app is left
 * and returned to, and hides the recent-apps preview while `LockGate.hideRecents` says so ([RecentsPrivacy]). It is the player's
 * [PipHost] ([ActivityPip]): entering picture-in-picture counts as leaving for the lock, back to the full screen as returning.
 */
class MainActivity : ComponentActivity(), PipHost {
    private val openTab = mutableStateOf<String?>(null)

    /** Told when the app is left and returned to: the graph's `LockGate` (a test hands a recording fake: review M5 of Tasks 9-10). */
    internal var trips: () -> AppTrips = { ShoalwatchApp.graph(this).lockParts.lockGate }
    private val pip = ActivityPip(
        this,
        autoEnter = { ShoalwatchApp.graph(this).lockParts.pipPrefs.autoEnter.value },
        onLeft = { trips().onLeft() },
        onReturned = { trips().onReturned() },
    )
    override val inPip: StateFlow<Boolean> get() = pip.inPip
    override fun wish(w: PipWish?, onPlayPause: (() -> Unit)?) = pip.wish(w, onPlayPause)
    override fun enterNow(): Boolean = pip.enterNow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything reads it (Navigation acts on an intent's data as a deep link: Milestone 6 check M9).
        intent = sanitised(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openTab.value = requestedTab(intent)
        val graph = ShoalwatchApp.graph(this)
        lifecycleScope.launch { graph.lockParts.lockGate.hideRecents.collect { RecentsPrivacy.apply(this@MainActivity, it) } }
        setContent {
            CompositionLocalProvider(LocalArtworkLoader provides graph.imageLoader, LocalPipHost provides this) {
                ShoalwatchTheme { AppRoot(graph, openTab.value) { openTab.value = null } }
            }
        }
    }

    /** In front: anything queued is kicked again (it also resumes after Android's 6-hour data-sync limit). */
    override fun onStart() {
        super.onStart()
        pip.onActivityStarted() // first: a lock is decided before anything else runs (not a return while in the small window)
        ShoalwatchApp.graph(this).downloadsInFront()
    }

    /** Leaving the app starts the app lock's timer; turning the phone or resizing the window does not. */
    override fun onStop() {
        super.onStop()
        pip.onActivityStopped()
    }

    override fun onNewIntent(intent: Intent) {
        val clean = sanitised(intent)
        super.onNewIntent(clean)
        setIntent(clean)
        requestedTab(clean)?.let { openTab.value = it }
    }

    companion object {
        /**
         * A copy of [intent] with no data, no clip data, no categories but LAUNCHER, and only the `open_tab=downloads` extra (else
         * none). Navigation therefore never sees an `android-app://androidx.navigation/…` deep link (closes the Milestone 6 check's M9).
         */
        internal fun sanitised(intent: Intent?): Intent {
            val clean = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            clean.component = ComponentName(BuildConfig.APPLICATION_ID, MainActivity::class.java.name)
            requestedTab(intent)?.let { clean.putExtra(DownloadNotifications.EXTRA_OPEN_TAB, it) }
            intent?.flags?.let { clean.flags = it and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            return clean
        }

        /** The tab a launch asks for: only exactly `downloads`, from the download notice; anything else is nothing. */
        internal fun requestedTab(intent: Intent?): String? =
            intent?.getStringExtra(DownloadNotifications.EXTRA_OPEN_TAB)?.takeIf { it == DownloadNotifications.TAB_DOWNLOADS }
    }
}
