package app.shoalwatch.ui.shell

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    // Centred while it fits; scrolls when it does not (a large text size, a short window).
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
