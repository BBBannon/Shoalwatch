package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.Tone

/** Reading the saved servers failed. Deliberately not the Welcome screen: that would invite adding a server over existing data. */
@Composable
internal fun StartupFailedScreen(onRetry: () -> Unit) {
    AppScreen(
        title = "",
        onBack = null,
        bottomBar = { PrimaryButton(stringResource(R.string.action_retry), onRetry) },
    ) {
        NoticeCard(
            title = stringResource(R.string.startup_failed_title),
            body = stringResource(R.string.startup_failed_body),
            tone = Tone.Danger,
        )
    }
}
