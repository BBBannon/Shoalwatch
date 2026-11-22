package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.shoalwatch.data.images.ArtworkLimits
import app.shoalwatch.data.images.ArtworkRef
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision

/**
 * A full-screen photo (the photo viewer, Milestone 11), through the same guarded loader as [Artwork]. It asks the server for the
 * photo at screen size: the smallest of 720, 1280 and 1920 that covers [longestSidePx] ([ArtworkLimits.photoBucket]), and the
 * loader decodes a `PHOTO` ref against that bucket under its 4 MP cap. The picture is fitted, not cropped; [pictureModifier] is
 * applied to it alone (the viewer's zoom and pan), over a placeholder that fills the page. With no [ref] or no loader, only the
 * placeholder shows, carrying [pictureModifier]. Decorative: the page carries the description.
 */
@Composable
fun PhotoArtwork(ref: ArtworkRef?, longestSidePx: Int, seed: String, title: String?, pictureModifier: Modifier = Modifier) {
    val loader = LocalArtworkLoader.current
    val context = LocalContext.current
    if (ref == null || loader == null) {
        ArtworkPlaceholder(seed = seed, title = title, modifier = pictureModifier)
        return
    }
    val request = remember(ref, longestSidePx) {
        val bucket = ArtworkLimits.photoBucket(longestSidePx)
        ImageRequest.Builder(context).data(ref).size(bucket, bucket).precision(Precision.INEXACT).build()
    }
    Box(Modifier.fillMaxSize()) {
        ArtworkPlaceholder(seed = seed, title = title, modifier = Modifier.fillMaxSize())
        AsyncImage(request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = pictureModifier)
    }
}
