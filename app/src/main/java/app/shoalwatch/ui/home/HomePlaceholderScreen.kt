package app.shoalwatch.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.ConnectionChip
import app.shoalwatch.ui.components.Loading
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.Tone

/** Milestone 1's home: who is signed in, to what, and how it is connected right now. Browsing arrives next. */
@Composable
fun HomePlaceholderScreen(
    state: HomeUiState,
    onOpenServers: () -> Unit,
    onRetry: () -> Unit,
    onReviewCertificate: (serverId: String) -> Unit,
) {
    AppScreen(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            IconButton(onClick = onOpenServers) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings_cd))
            }
        },
        bottomBar = if (state.signedOut) {
            { PrimaryButton(stringResource(R.string.home_manage), onOpenServers) }
        } else {
            null
        },
    ) {
        when {
            state.loading -> Loading(stringResource(R.string.loading))
            state.signedOut -> NoticeCard(
                title = stringResource(R.string.home_signed_out_title),
                body = stringResource(R.string.home_signed_out_body),
                tone = Tone.Info,
            )
            else -> SignedIn(state, onRetry, onReviewCertificate)
        }
    }
}

@Composable
private fun SignedIn(state: HomeUiState, onRetry: () -> Unit, onReviewCertificate: (String) -> Unit) {
    // No connection reading yet: show "Checking" rather than nothing.
    val connection = state.connection ?: ConnectionView(ConnectionView.Kind.CHECKING)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.serverName.orEmpty(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.home_signed_in_as, state.userName.orEmpty()), style = MaterialTheme.typography.bodyLarge)
            ConnectionChip(connection)
            if (connection.kind == ConnectionView.Kind.OFFLINE) SecondaryButton(stringResource(R.string.action_retry), onRetry)
        }
    }
    if (connection.needsCertificateReview && state.serverId != null) {
        NoticeCard(
            title = stringResource(if (connection.certificateChanged) R.string.cert_changed_title else R.string.cert_untrusted_alert_title),
            body = stringResource(if (connection.certificateChanged) R.string.cert_changed_body else R.string.cert_untrusted_alert_body),
            tone = Tone.Danger,
        ) {
            PrimaryButton(stringResource(R.string.cert_review), { onReviewCertificate(state.serverId) })
        }
    }
    Text(
        stringResource(R.string.home_coming),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
