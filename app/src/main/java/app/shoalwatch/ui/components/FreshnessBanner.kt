package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.theme.ShoalwatchTheme

/** What the three banner buttons do. */
class BannerActions(val onRetry: () -> Unit, val onSignIn: () -> Unit, val onReview: () -> Unit)

/**
 * The compact notice shown above saved content when a refresh failed: what happened, in one plain line, and one button.
 * Offline (and network or timeout) says what is on screen is saved and offers a retry; an expired sign-in offers Sign in; a
 * certificate problem offers Check certificate; anything else says "Couldn't refresh" with a retry. Icon and words, never colour alone, and
 * it is announced politely when it appears.
 */
@Composable
fun FreshnessBanner(kind: BannerKind, actions: BannerActions, modifier: Modifier = Modifier, padded: Boolean = true) {
    val (text, button, onClick) = when (kind) {
        BannerKind.OFFLINE -> Triple(R.string.banner_offline, R.string.action_retry, actions.onRetry)
        BannerKind.SIGN_IN -> Triple(R.string.banner_signin, R.string.action_sign_in, actions.onSignIn)
        BannerKind.CERTIFICATE -> Triple(R.string.banner_certificate, R.string.action_review, actions.onReview)
        BannerKind.GENERIC -> Triple(R.string.banner_generic, R.string.action_retry, actions.onRetry)
    }
    val colours = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        BannerKind.OFFLINE -> colours.secondaryContainer to colours.onSecondaryContainer
        BannerKind.CERTIFICATE -> colours.errorContainer to colours.onErrorContainer
        else -> colours.tertiaryContainer to colours.onTertiaryContainer
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = if (padded) Gutter else 0.dp, vertical = 4.dp),
        shape = CardShape,
        color = container,
        contentColor = content,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (kind == BannerKind.OFFLINE) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.padding(top = 12.dp).size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                // The live region is on the message alone: the banner is not merged, so its button stays a control of its own.
                Text(stringResource(text), Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onClick) { Text(stringResource(button), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** A thin progress line along the top of a screen while a refresh runs. It always takes its 4dp, so nothing moves when it appears. */
@Composable
fun RefreshLine(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
    }
}

@Preview(showBackground = true, name = "Banners")
@Composable
private fun BannerPreview() = ShoalwatchTheme {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val none = BannerActions({}, {}, {})
        BannerKind.entries.forEach { FreshnessBanner(it, none) }
    }
}
