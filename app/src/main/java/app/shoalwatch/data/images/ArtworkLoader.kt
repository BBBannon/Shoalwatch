package app.shoalwatch.data.images

import android.content.Context
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.servers.Session
import coil3.ImageLoader
import coil3.request.allowHardware
import coil3.serviceLoaderEnabled
import coil3.size.Precision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Builds THE app-wide image loader (`AppGraph.imageLoader`). It can load an [ArtworkRef] and nothing else; see "Artwork" in
 * docs/ARCHITECTURE.md for the component list and why. Coil's service-loader discovery is off, no Coil network module is
 * a dependency, and the first interceptor refuses any other kind of model.
 */
object ArtworkLoader {
    fun build(
        context: Context,
        api: ServerApi,
        signedIn: suspend (Session) -> Boolean,
        caches: ArtworkDiskCaches = ArtworkDiskCaches(File(context.cacheDir, "artwork")),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): ImageLoader = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components {
            add(ArtworkOnlyInterceptor(signedIn))
            add(ArtworkKeyer(), ArtworkRef::class)
            add(ArtworkFetcher.Factory(api, caches, io), ArtworkRef::class)
            add(ArtworkDecoder.Factory())
        }
        .diskCache { null } // pictures live in the per-server caches, never in a Coil-wide one
        .precision(Precision.INEXACT)
        .allowHardware(true)
        .build()
}
