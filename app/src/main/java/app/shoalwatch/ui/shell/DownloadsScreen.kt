package app.shoalwatch.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.R
import app.shoalwatch.ui.components.DownloadIcon
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.theme.ShoalwatchTheme

/** Downloads are a later milestone: a plain, honest placeholder with no controls that do nothing. */
@Composable
fun DownloadsScreen() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.downloads_title),
            body = stringResource(R.string.downloads_body),
            icon = DownloadIcon,
        )
    }
}

@Preview(showBackground = true, name = "Downloads")
@Composable
private fun DownloadsPreview() = ShoalwatchTheme {
    AppShell(Tab.DOWNLOADS, {}, SnackbarHostState()) { DownloadsScreen() }
}
