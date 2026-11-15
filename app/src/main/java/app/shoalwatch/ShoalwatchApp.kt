package app.shoalwatch

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

/** Also Coil's one source of the image loader: `Artwork` finds it here, so there is exactly one, built by `ArtworkLoader`. */
class ShoalwatchApp : Application(), SingletonImageLoader.Factory {

    val graph: AppGraph by lazy { AppGraph(this) }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.setLogLevel(Log.LOG_LEVEL_OFF) // Media3's own log lines carry URIs
        graph.tokensWarmed // start loading saved sign-ins
        graph.activePlayback // start following the active session now: playback is refused until it is known
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader

    companion object {
        fun graph(context: Context): AppGraph =
            (context.applicationContext as ShoalwatchApp).graph
    }
}
