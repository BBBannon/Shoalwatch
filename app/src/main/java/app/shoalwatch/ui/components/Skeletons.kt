package app.shoalwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.theme.ShoalwatchTheme

/** A quiet grey block the shape of something that is about to arrive. No shimmer: calm, and nothing moves. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, ratio: Float? = null, corner: Dp = 8.dp) {
    val base = if (ratio != null) modifier.aspectRatio(ratio) else modifier
    Box(base.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

/**
 * A placeholder for a row of cards of the same size as the real thing (a heading bar, then [count] cards of [cardWidth]), so the
 * screen does not jump when the content arrives. Announced once as "Loading".
 */
@Composable
fun SkeletonRow(ratio: Float, cardWidth: Dp, modifier: Modifier = Modifier, count: Int = 6) {
    val loading = stringResource(R.string.cd_loading_content)
    Column(modifier.semantics { contentDescription = loading }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(Modifier.padding(horizontal = Gutter).width(160.dp).height(24.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false,
        ) {
            items((0 until count).toList()) {
                Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(), ratio = ratio, corner = 8.dp)
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(16.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(14.dp))
                }
            }
        }
    }
}

/** A placeholder for an episode list: rows with a thumbnail and two lines of text. */
@Composable
fun SkeletonEpisodes(modifier: Modifier = Modifier, count: Int = 4) {
    val loading = stringResource(R.string.cd_loading_content)
    Column(modifier.semantics { contentDescription = loading }.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.width(120.dp), ratio = 16f / 9f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(18.dp))
                    SkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Skeletons")
@Composable
private fun SkeletonPreview() = ShoalwatchTheme {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeletonRow(16f / 9f, PhoneWideCardWidth)
        SkeletonRow(2f / 3f, 128.dp)
        SkeletonEpisodes()
    }
}
