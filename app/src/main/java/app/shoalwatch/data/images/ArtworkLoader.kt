package app.shoalwatch.data.images

import android.content.Context
import app.shoalwatch.data.api.ServerApi
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.request.allowHardware
import coil3.serviceLoaderEnabled
import coil3.size.Precision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Builds THE app-wide image loader (`AppGraph.imageLoader`). It can load an [ArtworkRef] and nothing else; see "Artwork" in
 * docs/ARCHITECTURE.md for the component list and why. Coil's service-loader discovery is off, no Coil network module is
 * a dependency, and the first interceptor refuses any other kind of model.
 */
object ArtworkLoader {
    const val DISK_BYTES = 250L * 1024 * 1024

    fun build(
        context: Context,
        api: ServerApi,
        directory: File = File(context.cacheDir, "artwork"),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): ImageLoader = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components {
            add(ArtworkOnlyInterceptor())
            add(ArtworkKeyer(), ArtworkRef::class)
            add(ArtworkFetcher.Factory(api, io), ArtworkRef::class)
            add(ArtworkDecoder.Factory())
        }
        .diskCache { DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(DISK_BYTES).build() }
        .precision(Precision.INEXACT)
        .allowHardware(true)
        .build()
}
