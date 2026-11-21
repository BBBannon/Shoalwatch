package app.shoalwatch.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.manage.IdentifyMatch
import app.shoalwatch.ui.admin.AdminAction
import app.shoalwatch.ui.admin.AdminCard
import app.shoalwatch.ui.admin.AdminFormDialog
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminScaffold
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.NoKeyboardLearning

class IdentifyActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onUse: (IdentifyMatch) -> Unit,
    val onReplaceImages: (Boolean) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * Identify: Title and Year, Search, then each match as a card: a letter tile (never the match's own picture, S7), its name and year,
 * "From TheMovieDb", the start of its overview, and Use this (confirmed, with "Replace all pictures").
 */
@Composable
fun IdentifyScreen(state: IdentifyUiState, name: TextFieldState, year: TextFieldState, actions: IdentifyActions) {
    AdminScaffold(stringResource(R.string.identify_title), state.frame, actions.onBack, null, actions.onDismissProblem, actions.onDismissNotice) {
        if (state.known && state.kind == null) {
            item(key = "kind") { Text(stringResource(R.string.identify_only_films_series), style = MaterialTheme.typography.bodyMedium) }
            return@AdminScaffold
        }
        item(key = "form") {
            AdminCard {
                NoKeyboardLearning {
                    OutlinedTextField(
                        state = name, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.identify_name)) },
                        inputTransformation = InputTransformation.maxLength(200), lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                NoKeyboardLearning {
                    OutlinedTextField(
                        state = year, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.identify_year)) },
                        inputTransformation = InputTransformation.maxLength(4), lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                }
                AdminAction(
                    stringResource(R.string.identify_search), stringResource(R.string.identify_search),
                    state.frame.canAct && state.kind != null && name.text.isNotBlank(), onClick = actions.onSearch,
                )
                Text(stringResource(R.string.identify_no_pictures_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val results = state.results ?: return@AdminScaffold
        if (results.isEmpty()) item(key = "empty") { Text(stringResource(R.string.identify_empty), style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(results, key = { i, m -> "match-$i-${m.key}" }) { _, m -> MatchCard(m, state.frame.canAct) { actions.onUse(m) } }
    }
    if (state.frame.gate !is AdminGate.Open) return
    state.apply?.let { ask ->
        AdminFormDialog(
            stringResource(R.string.identify_confirm_title, quoted(ask.match.name)), stringResource(R.string.identify_use), state.frame.canAct,
            actions.onConfirm, actions.onDismiss,
        ) {
            Text(stringResource(R.string.identify_confirm_body), style = MaterialTheme.typography.bodyMedium)
            CheckRow(stringResource(R.string.identify_replace_images), ask.replaceImages, actions.onReplaceImages)
        }
    }
}

@Composable
private fun MatchCard(m: IdentifyMatch, canAct: Boolean, onUse: () -> Unit) {
    AdminCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Artwork(null, null, aspect = ArtworkAspect.POSTER, title = m.name, modifier = Modifier.width(56.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = if (m.year != null) "${m.name} (${m.year})" else m.name
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (m.providerName.isNotEmpty()) {
                    Text(stringResource(R.string.identify_from, m.providerName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                m.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
        AdminAction(stringResource(R.string.identify_use), stringResource(R.string.identify_use_cd, m.name), canAct, onClick = onUse)
    }
}
