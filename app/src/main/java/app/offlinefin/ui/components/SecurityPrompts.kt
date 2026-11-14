package app.offlinefin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.AddressRole
import app.offlinefin.ui.CertificateSummary

/*
 * The blocking security steps, shared by Add server (as screens) and Connection settings (as sub-pages).
 * Each is a full screen: the safe choice is the filled button, the risky one is a red text button.
 */

/** Plain `http`: the user must say "use anyway" before it is allowed. */
@Composable
fun HttpWarningScreen(onCancel: () -> Unit, onUseAnyway: () -> Unit, role: AddressRole? = null) {
    AppScreen(
        title = stringResource(R.string.http_title),
        onBack = onCancel,
        bottomBar = {
            SafeButton(stringResource(R.string.action_cancel), onCancel)
            CautionButton(stringResource(R.string.http_use), onUseAnyway)
        },
    ) {
        NoticeCard(title = stringResource(R.string.http_title), body = stringResource(R.string.http_body), tone = Tone.Danger)
        when (role) {
            AddressRole.Home -> Text(stringResource(R.string.http_for_home), style = MaterialTheme.typography.titleSmall)
            AddressRole.Away -> Text(stringResource(R.string.http_for_away), style = MaterialTheme.typography.titleSmall)
            null -> Unit
        }
    }
}

/**
 * An untrusted ([changed] = false) or changed certificate. With [certificate] null the screen shows a
 * spinner ([loading]) or the [error]; the trust button exists only once a certificate is on screen.
 */
@Composable
fun CertificateScreen(
    certificate: CertificateSummary?,
    loading: Boolean,
    error: String?,
    changed: Boolean,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(if (changed) R.string.cert_changed_title else R.string.cert_title)
    AppScreen(
        title = title,
        onBack = onCancel,
        bottomBar = {
            SafeButton(stringResource(R.string.action_cancel), onCancel)
            if (certificate != null) {
                CautionButton(stringResource(if (changed) R.string.cert_trust_new else R.string.cert_trust), onTrust)
            }
        },
    ) {
        NoticeCard(
            title = title,
            body = stringResource(if (changed) R.string.cert_changed_body else R.string.cert_body),
            tone = if (changed) Tone.Danger else Tone.Caution,
            icon = Icons.Filled.Lock,
        )
        when {
            certificate != null -> CertificateDetails(certificate)
            loading -> Loading(stringResource(R.string.cert_loading))
            else -> ErrorMessage(error ?: stringResource(R.string.cert_none_found))
        }
    }
}

/** The server wants a client certificate: pick one from the system `KeyChain`. */
@Composable
fun ClientCertificateScreen(onChoose: () -> Unit, onCancel: () -> Unit) {
    AppScreen(
        title = stringResource(R.string.clientcert_title),
        onBack = onCancel,
        bottomBar = {
            PrimaryButton(stringResource(R.string.clientcert_choose), onChoose)
            SecondaryButton(stringResource(R.string.action_cancel), onCancel)
        },
    ) {
        NoticeCard(
            title = stringResource(R.string.clientcert_title),
            body = stringResource(R.string.clientcert_body),
            tone = Tone.Info,
            icon = Icons.Filled.Lock,
        )
    }
}

/** A cancellable wait. */
@Composable
fun CheckingScreen(title: String, detail: String?, cancelLabel: String, onCancel: () -> Unit) {
    AppScreen(
        title = title,
        onBack = onCancel,
        bottomBar = { SecondaryButton(cancelLabel, onCancel) },
    ) {
        Loading(detail ?: title)
    }
}

/** A spinner and a line of text in a block of fixed height, so the layout does not jump when it appears or goes. */
@Composable
fun Loading(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().heightIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
