package app.shoalwatch

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

/** Also Coil's one source of the image loader: `Artwork` finds it here, so there is exactly one, built by `ArtworkLoader`. */
class ShoalwatchApp : Application(), SingletonImageLoader.Factory {

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.tokensWarmed // start loading saved sign-ins
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader

    companion object {
        fun graph(context: Context): AppGraph =
            (context.applicationContext as ShoalwatchApp).graph
    }
}
