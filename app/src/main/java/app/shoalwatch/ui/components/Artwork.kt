package app.shoalwatch.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.ui.theme.ShoalwatchTheme
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** The shape of the box a picture fills. The box is that shape before anything loads, so the layout never jumps. */
enum class ArtworkAspect(val ratio: Float) { POSTER(2f / 3f), WIDE(16f / 9f), SQUARE(1f) }

/**
 * A poster, backdrop or thumbnail. [ref] is all the screen ever knows: the loader behind it (`AppGraph.imageLoader`, set up as
 * Coil's singleton in `ShoalwatchApp`) is the only thing that can fetch, from the verified address, with the sign-in. While
 * loading, on any failure (offline with nothing saved, a rejected file, a signed-out account) and when [ref] is null, a
 * tinted block with the first letter of [title] shows instead. The caller sets the width (or the height); the other follows [aspect]. [contentDescription] is null for a purely decorative
 * picture inside a card that already carries the name.
 */
@Composable
fun Artwork(
    ref: ArtworkRef?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    aspect: ArtworkAspect = ArtworkAspect.POSTER,
    title: String? = null,
) {
    val context = LocalContext.current
    val fade = remember(context) { crossfadeMillis(animationsEnabled(context)) }
    val frame = modifier.aspectRatio(aspect.ratio).clip(RoundedCornerShape(CORNER))
    if (ref == null) {
        val described = if (contentDescription != null) frame.semantics { this.contentDescription = contentDescription; role = Role.Image } else frame
        ArtworkPlaceholder(seed = title.orEmpty(), title = title, modifier = described)
        return
    }
    Box(frame) {
        ArtworkPlaceholder(seed = ref.itemId, title = title, modifier = Modifier.matchParentSize())
        val request = remember(ref, fade) { ImageRequest.Builder(context).data(ref).crossfade(fade).build() }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = remember(context) { SingletonImageLoader.get(context) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

private val CORNER = 8.dp

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
