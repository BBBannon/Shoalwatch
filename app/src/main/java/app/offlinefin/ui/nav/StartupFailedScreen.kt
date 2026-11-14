package app.offlinefin.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.offlinefin.R
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.NoticeCard
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.Tone

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
