package app.shoalwatch.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.manage.ManageLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.shoalwatch.R
import app.shoalwatch.data.manage.LockableField
import app.shoalwatch.ui.admin.AdminAction
import app.shoalwatch.ui.admin.AdminCard
import app.shoalwatch.ui.admin.AdminScaffold
import app.shoalwatch.ui.components.NoKeyboardLearning

/** The form's fields, owned by the ViewModel (never saved anywhere else). */
class EditFields(
    val name: TextFieldState,
    val sortName: TextFieldState,
    val originalTitle: TextFieldState,
    val overview: TextFieldState,
    val year: TextFieldState,
    val premiere: TextFieldState,
    val officialRating: TextFieldState,
    val communityRating: TextFieldState,
    /** What is being typed for each list before Add. */
    val inputs: Map<EditList, TextFieldState>,
)

class EditActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onLock: (LockableField, Boolean) -> Unit,
    val onLockAll: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onAddEntry: (EditList) -> Unit = {},
    val onRemoveEntry: (EditList, Int) -> Unit = { _, _ -> },
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * Edit details: every field labelled, with its problem in words beneath it; each list as its entries (chips, each with "Remove …")
 * and a field with Add; the locks as check boxes; Save. Works at a large text size (one column, the scaffold's list scrolls).
 */
@Composable
fun EditScreen(state: EditUiState, fields: EditFields, actions: EditActions) {
    AdminScaffold(stringResource(R.string.meta_edit_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice) {
        if (!state.loaded) return@AdminScaffold
        item(key = "text") {
            AdminCard {
                Field(fields.name, R.string.edit_name, problem = R.string.edit_bad_name.takeIf { EditProblem.NAME in state.problems })
                Field(fields.sortName, R.string.edit_sort_name)
                Field(fields.originalTitle, R.string.edit_original_title)
                Field(fields.overview, R.string.edit_overview, lines = 6, max = 8_000, capitalise = KeyboardCapitalization.Sentences)
            }
        }
        item(key = "numbers") {
            AdminCard {
                Field(fields.year, R.string.edit_year, number = true, max = 4, problem = R.string.edit_bad_year.takeIf { EditProblem.YEAR in state.problems })
                Field(fields.premiere, R.string.edit_premiere, max = 10, problem = R.string.edit_bad_date.takeIf { EditProblem.DATE in state.problems })
                Field(fields.officialRating, R.string.edit_official_rating, max = 20)
                if (state.ratingOptions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.edit_rating_choices, state.ratingOptions.take(30).joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Field(fields.communityRating, R.string.edit_community_rating, decimal = true, max = 4, problem = R.string.edit_bad_rating.takeIf { EditProblem.RATING in state.problems })
            }
        }
        item(key = "lists") {
            AdminCard {
                for (list in EditList.entries) {
                    Entries(list, state.lists[list].orEmpty(), fields.inputs.getValue(list), actions)
                }
            }
        }
        item(key = "locks") {
            AdminCard {
                Text(stringResource(R.string.edit_locks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                for (f in LockableField.entries) CheckRow(stringResource(f.label()), f in state.locked, { actions.onLock(f, it) }, enabled = !state.lockAll)
                CheckRow(stringResource(R.string.edit_lock_all), state.lockAll, actions.onLockAll)
            }
        }
        item(key = "save") { AdminAction(stringResource(R.string.edit_save), stringResource(R.string.edit_save), state.frame.canAct, onClick = actions.onSave) }
    }
}

private fun EditList.label() = when (this) {
    EditList.GENRES -> R.string.edit_genres
    EditList.TAGS -> R.string.edit_tags
    EditList.STUDIOS -> R.string.edit_studios
}

/** One list: its name as a heading, each entry as a chip whose cross removes it, then a field and Add for one more entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Entries(list: EditList, entries: List<String>, input: TextFieldState, actions: EditActions) {
    val label = stringResource(list.label())
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    if (entries.isEmpty()) {
        Text(stringResource(R.string.edit_list_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { i, entry ->
            val remove = stringResource(R.string.edit_list_remove, entry)
            InputChip(
                selected = false,
                onClick = { actions.onRemoveEntry(list, i) },
                label = { Text(entry) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = remove },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Field(input, R.string.edit_list_add_label, max = ManageLimits.LIST_ENTRY) }
        val addTo = stringResource(R.string.edit_list_add_to, label)
        TextButton(onClick = { actions.onAddEntry(list) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addTo }) {
            Text(stringResource(R.string.edit_list_add))
        }
    }
}

@Composable
private fun Field(
    field: TextFieldState,
    label: Int,
    lines: Int = 1,
    max: Int = 200,
    number: Boolean = false,
    decimal: Boolean = false,
    capitalise: KeyboardCapitalization = KeyboardCapitalization.Words,
    problem: Int? = null,
) {
    NoKeyboardLearning {
        OutlinedTextField(
            state = field,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(label)) },
            isError = problem != null,
            supportingText = problem?.let { { Text(stringResource(it)) } },
            inputTransformation = InputTransformation.maxLength(max),
            lineLimits = if (lines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = lines, maxHeightInLines = lines + 4),
            keyboardOptions = KeyboardOptions(
                capitalization = if (number || decimal) KeyboardCapitalization.None else capitalise,
                keyboardType = when {
                    number -> KeyboardType.Number
                    decimal -> KeyboardType.Decimal
                    else -> KeyboardType.Text
                },
            ),
        )
    }
}
