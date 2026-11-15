package app.shoalwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.ArtworkRef

/**
 * The wide picture at the head of a series or an item, softly faded into the page at its foot, with a back button over it that
 * stays a 48dp target on a darkened disc so it reads over any picture. The picture is decoration: the page carries the title.
 */
@Composable
fun HeroBackdrop(artwork: ArtworkRef?, title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter, vertical = 8.dp)) {
        Artwork(artwork, contentDescription = null, aspect = ArtworkAspect.WIDE, title = title, modifier = Modifier.fillMaxWidth())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                shape = CardShape,
            ),
        )
        OverlayBackButton(onBack, Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

/** A back arrow on a darkened disc, 48dp to touch. */
@Composable
fun OverlayBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.cd_back)
    IconButton(onClick = onBack, modifier = modifier.size(TouchTarget).semantics { contentDescription = label }) {
        Surface(Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
