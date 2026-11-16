package app.shoalwatch

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import androidx.work.Configuration
import app.shoalwatch.data.downloads.ShoalwatchWorkerFactory

/**
 * Also Coil's one source of the image loader: `Artwork` finds it here, so there is exactly one, built by `ArtworkLoader`; and
 * WorkManager's configuration (initialised on demand: the manifest removes its default initialiser), so the download and sync workers are
 * built by [ShoalwatchWorkerFactory] and WorkManager logs only errors.
 */
class ShoalwatchApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    val graph: AppGraph by lazy { AppGraph(this) }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.setLogLevel(Log.LOG_LEVEL_OFF) // Media3's own log lines carry URIs
        graph.tokensWarmed // start loading saved sign-ins
        graph.activePlayback // start following the active session now: playback is refused until it is known
        graph.watchDownloads() // WorkManager itself is reached only once something is queued (see MainActivity.onStart)
        graph.watchSharing() // the sharing re-ask on each session change and each time the server comes back
        graph.downloadHousekeeping.onStart() // requeue what a dead process left, sweep orphans, kick if anything waits
        graph.syncScheduler.start() // send what the outbox holds, and again each time a server with rows comes back
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(ShoalwatchWorkerFactory({ graph.downloadWorkerParts }, { graph.syncSender }))
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader

    companion object {
        fun graph(context: Context): AppGraph =
            (context.applicationContext as ShoalwatchApp).graph
    }
}
