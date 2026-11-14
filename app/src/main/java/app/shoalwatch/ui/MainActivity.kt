package app.shoalwatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.ui.nav.AppRoot
import app.shoalwatch.ui.theme.ShoalwatchTheme

/** The only activity: edge to edge, with every screen inside [AppRoot]'s navigation. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = ShoalwatchApp.graph(this)
        setContent {
            ShoalwatchTheme { AppRoot(graph) }
        }
    }
}
