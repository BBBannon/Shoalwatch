package app.offlinefin.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.offlinefin.OfflineFinApp
import app.offlinefin.ui.nav.AppRoot
import app.offlinefin.ui.theme.OfflineFinTheme

/** The only activity: edge to edge, with every screen inside [AppRoot]'s navigation. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = OfflineFinApp.graph(this)
        setContent {
            OfflineFinTheme { AppRoot(graph) }
        }
    }
}
