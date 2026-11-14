package app.offlinefin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.CertificateSummary
import app.offlinefin.ui.theme.CodeStyles
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A certificate fingerprint in large monospace, four bytes to a line so it is easy to compare by eye with
 * the one on the server. Selectable; read out as separate pairs by screen readers.
 */
@Composable
fun FingerprintBlock(fingerprint: String, modifier: Modifier = Modifier) {
    val lines = fingerprint.split(':').chunked(4).map { it.joinToString(":") }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        SelectionContainer {
            Column(
                Modifier.padding(16.dp).semantics(mergeDescendants = true) { contentDescription = fingerprint.replace(':', ' ') },
            ) {
                lines.forEach { Text(it, style = CodeStyles.Fingerprint) }
            }
        }
    }
}

/** Fingerprint, who the certificate was issued to and by, and when it expires. */
@Composable
fun CertificateDetails(certificate: CertificateSummary, modifier: Modifier = Modifier) {
    val expires = certificate.notAfter.atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.cert_fingerprint_label), style = MaterialTheme.typography.labelLarge)
        FingerprintBlock(certificate.fingerprint)
        Labelled(stringResource(R.string.cert_issued_to), certificate.subject)
        Labelled(stringResource(R.string.cert_issued_by), certificate.issuer)
        Text(stringResource(R.string.cert_expires, expires), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}
