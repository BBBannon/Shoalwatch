package app.shoalwatch.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shoalwatch.BuildConfig
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.ui.theme.ShoalwatchTheme
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision

/**
 * The guarded image loader, provided once at the root of the app's Compose tree (`MainActivity`). There is deliberately NO
 * default: Coil's own default loader would have file and content fetchers and no guard. Without one, a screen showing a ref
 * fails loudly in debug builds and tests, and shows the placeholder (logging a fixed line) in release.
 */
val LocalArtworkLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** The shape of the box a picture fills. The box is that shape before anything loads, so the layout never jumps. */
enum class ArtworkAspect(val ratio: Float) { POSTER(2f / 3f), WIDE(16f / 9f), SQUARE(1f) }

/**
 * A poster, backdrop or thumbnail. [ref] is all the screen ever knows: the loader behind it ([LocalArtworkLoader]) is the only
 * thing that can fetch, from the verified address, with the sign-in. While loading, on any failure (offline with nothing saved,
 * a rejected file, a signed-out account), when there is no loader and when [ref] is null, a tinted block with the first letter of
 * [title] shows instead; once the picture has loaded the block goes, so a transparent picture shows the theme surface behind it.
 * The caller sets the width (or the height); the other follows [aspect]. [contentDescription] is null for a purely decorative
 * picture inside a card that already carries the name. Screens use only this, with refs from `Item.primaryArtwork(session)` and
 * friends, and never retain a ref across an account change.
 */
@Composable
fun Artwork(
    ref: ArtworkRef?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    aspect: ArtworkAspect = ArtworkAspect.POSTER,
    title: String? = null,
    shape: Shape = ArtworkShape,
) {
    val context = LocalContext.current
    val loader = LocalArtworkLoader.current
    val fade = remember(context) { crossfadeMillis(animationsEnabled(context)) }
    val frame = modifier.aspectRatio(aspect.ratio).clip(shape)
    if (ref != null && loader == null) {
        check(!BuildConfig.DEBUG) { "Artwork needs a LocalArtworkLoader provided at the root of the app." }
        AppLog.w("Artwork", "no artwork loader provided")
    }
    if (ref == null || loader == null) {
        val described = if (contentDescription != null) frame.semantics { this.contentDescription = contentDescription; role = Role.Image } else frame
        ArtworkPlaceholder(seed = ref?.itemId ?: title.orEmpty(), title = title, modifier = described)
        return
    }
    var loaded by remember(ref) { mutableStateOf(false) }
    Box(frame.background(MaterialTheme.colorScheme.surface)) {
        if (!loaded) ArtworkPlaceholder(seed = ref.itemId, title = title, modifier = Modifier.matchParentSize())
        val request = remember(ref, fade) { ImageRequest.Builder(context).data(ref).precision(Precision.INEXACT).crossfade(fade).build() }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** The rounded corners every picture has; a progress bar laid over a picture's foot is clipped to the same shape. */
val ArtworkShape: Shape = RoundedCornerShape(8.dp)

/** False when the system's "remove animations" setting (animator duration scale 0) is on. */
private fun animationsEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
} catch (e: RuntimeException) {
    true
}

@Preview(showBackground = true, name = "Artwork placeholder, with a title")
@Composable
private fun ArtworkPlaceholderWithTitlePreview() = ShoalwatchTheme {
    Artwork(ref = null, contentDescription = null, title = "Alien", modifier = Modifier.width(120.dp))
}

@Preview(showBackground = true, name = "Artwork placeholder, no title")
@Composable
private fun ArtworkPlaceholderWithoutTitlePreview() = ShoalwatchTheme {
    Artwork(ref = null, contentDescription = null, aspect = ArtworkAspect.WIDE, modifier = Modifier.width(240.dp))
}
