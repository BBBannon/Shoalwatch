package app.shoalwatch.ui.subtitles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.subtitles.SubtitleCandidate
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import java.util.Locale

/**
 * The detail page's search: one secure window (a dialog is a window of its own, so it composes nothing under the lock cover and
 * hides itself from screenshots). The player shows [SubtitleSearchBody] inside its own sheet.
 */
@Composable
fun SubtitleSearchDialog(c: SubtitleSearchControls) {
    if (!c.state.open) return
    if (LocalCovered.current) return // under the lock cover
    AlertDialog(
        onDismissRequest = c.actions.onClose,
        properties = SecureDialog,
        title = { Text(stringResource(R.string.subsearch_title), Modifier.semantics { heading() }) },
        text = {
            val obscured = FilterObscuredTouches()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ObscuredTouchHint(obscured)
                SubtitleSearchBody(c, horizontal = 0)
            }
        },
        confirmButton = {
            TextButton(onClick = c.actions.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.subsearch_close)) }
        },
    )
}

/**
 * The search itself: the language (a button that opens a list of readable names), then the results as text only, each with its own
 * Download button (48dp, named for the result, disabled while one is going), and the last download's words. Offline, failed and refused
 * searches say so; offline and failed offer Try again.
 */
@Composable
fun SubtitleSearchBody(c: SubtitleSearchControls, horizontal: Int = 24) {
    val ui = c.state
    val pad = Modifier.padding(horizontal = horizontal.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = SubtitleLanguages.name(ui.language)
        OutlinedButton(onClick = c.actions.onTogglePicking, enabled = !ui.sending, modifier = pad.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subsearch_language, name))
        }
        if (ui.picking) {
            Text(stringResource(R.string.subsearch_language_heading), style = MaterialTheme.typography.titleMedium, modifier = pad.semantics { heading() })
            SubtitleLanguages.codes(ui.language).forEach { code ->
                LanguageRow(SubtitleLanguages.name(code), code == ui.language, horizontal) { c.actions.onLanguage(code) }
            }
            return@Column
        }
        when (ui.phase) {
            SubtitleSearchPhase.SEARCHING -> Row(pad, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.subsearch_searching), style = MaterialTheme.typography.bodyLarge)
            }
            SubtitleSearchPhase.OFFLINE -> {
                Text(stringResource(R.string.subsearch_offline_title), style = MaterialTheme.typography.titleMedium, modifier = pad)
                Text(stringResource(R.string.subsearch_offline_body), style = MaterialTheme.typography.bodyMedium, modifier = pad)
                TextButton(onClick = c.actions.onRetry, modifier = pad.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
            }
            SubtitleSearchPhase.FAILED -> {
                Text(stringResource(R.string.subsearch_failed), style = MaterialTheme.typography.bodyLarge, modifier = pad)
                TextButton(onClick = c.actions.onRetry, modifier = pad.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
            }
            SubtitleSearchPhase.NOT_ALLOWED -> Text(stringResource(R.string.subsearch_refused), style = MaterialTheme.typography.bodyLarge, modifier = pad)
            SubtitleSearchPhase.RESULTS -> {
                if (ui.candidates.isEmpty()) Text(stringResource(R.string.subsearch_none), style = MaterialTheme.typography.bodyLarge, modifier = pad)
                ui.candidates.forEach { r -> ResultRow(r, ui.sending, horizontal) { c.actions.onDownload(r) } }
            }
        }
        if (ui.sending) Text(stringResource(R.string.subsearch_sending), style = MaterialTheme.typography.bodyMedium, modifier = pad)
        if (ui.message != 0) Text(stringResource(ui.message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = pad)
    }
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, horizontal: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = horizontal.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** One result as text: its name, then provider, format, rating, downloads, "Hearing impaired" and "Forced". */
@Composable
private fun ResultRow(r: SubtitleCandidate, sending: Boolean, horizontal: Int, onDownload: () -> Unit) {
    val untitled = stringResource(R.string.untitled)
    val title = r.name.ifBlank { untitled }
    val facts = buildList {
        add(r.provider)
        r.format?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
        r.rating?.let { add(stringResource(R.string.subsearch_rating, String.format(Locale.getDefault(), "%.1f", it))) }
        r.downloads?.let { add(pluralStringResource(R.plurals.subsearch_downloads, it, java.text.NumberFormat.getIntegerInstance().format(it))) }
        if (r.hearingImpaired) add(stringResource(R.string.subsearch_hearing_impaired))
        if (r.forced) add(stringResource(R.string.subsearch_forced))
    }.filter { it.isNotBlank() }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(horizontal = horizontal.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val label = stringResource(R.string.subsearch_download_for, title)
        TextButton(onClick = onDownload, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
            Text(stringResource(R.string.subsearch_download))
        }
    }
}
