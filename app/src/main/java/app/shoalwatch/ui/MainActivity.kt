package app.shoalwatch.ui

import android.content.Intent
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
import app.shoalwatch.ui.components.LocalArtworkLoader
import app.shoalwatch.ui.nav.AppRoot
import app.shoalwatch.ui.theme.ShoalwatchTheme

/**
 * The only activity: edge to edge, with every screen inside [AppRoot]'s navigation. The download notice opens it with
 * `open_tab=downloads`; that exact value is the only one it acts on ([requestedTab]). It tells `LockGate` when the app is left
 * and returned to, and hides the recent-apps preview while `LockGate.hideRecents` says so ([RecentsPrivacy]).
 */
class MainActivity : ComponentActivity() {
    private val openTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openTab.value = requestedTab(intent)
        val graph = ShoalwatchApp.graph(this)
        lifecycleScope.launch { graph.lockParts.lockGate.hideRecents.collect { RecentsPrivacy.apply(this@MainActivity, it) } }
        setContent {
            CompositionLocalProvider(LocalArtworkLoader provides graph.imageLoader) {
                ShoalwatchTheme { AppRoot(graph, openTab.value) { openTab.value = null } }
            }
        }
    }

    /** In front: anything queued is kicked again (it also resumes after Android's 6-hour data-sync limit). */
    override fun onStart() {
        super.onStart()
        ShoalwatchApp.graph(this).lockParts.lockGate.onReturned() // first: a lock is decided before anything else runs
        ShoalwatchApp.graph(this).downloadsInFront()
    }

    /** Leaving the app starts the app lock's timer; turning the phone or resizing the window does not. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) ShoalwatchApp.graph(this).lockParts.lockGate.onLeft()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab(intent)?.let { openTab.value = it }
    }

    companion object {
        /** The tab a launch asks for: only exactly `downloads`, from the download notice; anything else is nothing. */
        internal fun requestedTab(intent: Intent?): String? =
            intent?.getStringExtra(DownloadNotifications.EXTRA_OPEN_TAB)?.takeIf { it == DownloadNotifications.TAB_DOWNLOADS }
    }
}
