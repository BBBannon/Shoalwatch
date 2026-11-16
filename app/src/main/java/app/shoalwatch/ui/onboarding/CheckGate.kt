package app.shoalwatch.ui.onboarding

import androidx.compose.runtime.Composable
import app.shoalwatch.ui.AddressRole
import app.shoalwatch.ui.components.CertificateScreen
import app.shoalwatch.ui.components.ClientCertificateScreen
import app.shoalwatch.ui.components.HttpWarningScreen

/**
 * Maps an address check onto its blocking warning screens, for Add server and Connection settings alike. When no
 * warning is due, [content] draws the normal form: [checking] while a request is running (shown on the button,
 * so nothing jumps) and [failed] to show the error beside the field.
 */
@Composable
fun CheckGate(
    check: Check,
    role: AddressRole?,
    onCancel: () -> Unit,
    onAllowHttp: () -> Unit,
    onTrustCertificate: () -> Unit,
    onChooseClientCertificate: () -> Unit,
    content: @Composable (checking: Boolean, failed: Check.Failed?) -> Unit,
) {
    when (check) {
        Check.NeedsHttpConsent -> HttpWarningScreen(onCancel = onCancel, onUseAnyway = onAllowHttp, role = role)
        Check.LoadingCertificate, is Check.NeedsCertificateTrust -> CertificateScreen(
            certificate = (check as? Check.NeedsCertificateTrust)?.certificate,
            loading = check == Check.LoadingCertificate,
            error = null,
            changed = false,
            onTrust = onTrustCertificate,
            onCancel = onCancel,
        )
        Check.NeedsClientCertificate -> ClientCertificateScreen(onChoose = onChooseClientCertificate, onCancel = onCancel)
        else -> content(check == Check.Checking, check as? Check.Failed)
    }
}

/** Which screen a check is on, so a form that only changes its text does not animate but a warning does. */
fun gateKey(check: Check, base: Any): Any = when (check) {
    Check.NeedsHttpConsent -> "http"
    Check.LoadingCertificate, is Check.NeedsCertificateTrust -> "certificate"
    Check.NeedsClientCertificate -> "client-certificate"
    else -> base
}
