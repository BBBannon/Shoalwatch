package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.SkipReason
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.ui.present.DownloadText
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.components.LocalCovered

/** What the sheet does: [onChoose] (quality, "use this every time"), [onConfirm] (after the notification ask), [onDismiss]. */
data class DownloadSheetActions(val onChoose: (DownloadQuality, Boolean) -> Unit, val onConfirm: () -> Unit, val onDismiss: () -> Unit)

/**
 * The download sheet: the quality step, a spinner while the plan is prepared, then the confirm step with the size, the free
 * space and only the lines that apply. Scrolls at font scale 2.0; every choice is a 48dp radio row that says whether it is
 * selected. [ask] wraps the confirm with the notification ask (Android 13+, once).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(state: DownloadSheetState?, actions: DownloadSheetActions, ask: NotificationAskState = rememberNotificationAsk()) {
    if (state == null) return
    if (LocalCovered.current) return // under the lock cover (review M1)
    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is DownloadSheetState.Quality -> QualityStep(actions)
                is DownloadSheetState.Preparing -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.dl_preparing))
                }
                is DownloadSheetState.Confirm -> ConfirmStep(state, actions) { ask.beforeFirstConfirm(actions.onConfirm) }
                is DownloadSheetState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_close)) }
                }
            }
        }
    }
    NotificationAskDialog(ask)
}

@Composable
private fun ColumnScope.QualityStep(actions: DownloadSheetActions) {
    var selected by remember { mutableStateOf(DownloadQuality.ORIGINAL) }
    var always by remember { mutableStateOf(false) }
    Text(stringResource(R.string.dl_sheet_quality_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    val strings = rememberStrings()
    val bodies = mapOf(
        DownloadQuality.ORIGINAL to R.string.dl_original_body, DownloadQuality.HIGH to R.string.dl_high_body,
        DownloadQuality.MEDIUM to R.string.dl_medium_body, DownloadQuality.LOW to R.string.dl_low_body,
    )
    DownloadQuality.entries.forEach { q ->
        QualityRow(DownloadText.quality(strings, q), stringResource(bodies.getValue(q)), q == selected) { selected = q }
    }
    Text(stringResource(R.string.dl_conversion_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(always, role = Role.Checkbox) { always = it },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = always, onCheckedChange = null)
        Text(stringResource(R.string.dl_use_every_time))
    }
    Button(onClick = { actions.onChoose(selected, always) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_continue)) }
}

/** One radio choice: the whole row is the radio button (role, selected state), at least 48dp. */
@Composable
internal fun QualityRow(label: String, body: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The lines of a plan: only those whose counts are not zero. */
internal fun confirmLines(strings: Strings, plan: DownloadPlan): List<String> {
    fun skipped(r: SkipReason) = plan.skipped.count { it.reason == r }
    fun line(id: Int, n: Int) = if (n > 0) strings.plural(id, n) else null
    return listOfNotNull(
        line(R.plurals.dl_skip_not_allowed, skipped(SkipReason.NOT_ALLOWED)),
        line(R.plurals.dl_skip_conversion_unconfirmed, skipped(SkipReason.CONVERSION_UNCONFIRMED)),
        line(R.plurals.dl_skip_permission_unconfirmed, skipped(SkipReason.PERMISSION_UNCONFIRMED)),
        line(R.plurals.dl_skip_not_found, skipped(SkipReason.NOT_FOUND)),
        line(R.plurals.dl_converted_instead, plan.convertedInstead),
        line(R.plurals.dl_already_here, plan.items.count { it.shareOnly }),
        strings.get(R.string.dl_capped).takeIf { plan.capped },
        strings.get(R.string.dl_wifi_only).takeIf { !plan.mobileData },
    )
}

@Composable
private fun ColumnScope.ConfirmStep(state: DownloadSheetState.Confirm, actions: DownloadSheetActions, onDownload: () -> Unit) {
    val strings = rememberStrings()
    val plan = state.plan
    val count = plan.items.size
    val title = strings.plural(if (state.films) R.plurals.dl_confirm_title_films else R.plurals.dl_confirm_title_episodes, count)
    val total = DownloadText.size(strings, plan.totalBytes)
    val free = DownloadText.size(strings, plan.freeBytes)
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(stringResource(if (plan.storage == StorageKind.REMOVABLE) R.string.dl_confirm_body_card else R.string.dl_confirm_body_phone, total, free), style = MaterialTheme.typography.bodyLarge)
    confirmLines(strings, plan).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val noSpace = stringResource(R.string.dl_not_enough_space, total, free).takeIf { !plan.enoughSpace }
    if (noSpace != null) Text(noSpace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onDownload, enabled = plan.enoughSpace && count > 0,
            modifier = Modifier.heightIn(min = 48.dp).semantics { if (noSpace != null) stateDescription = noSpace },
        ) { Text(stringResource(R.string.dl_download)) }
        OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
    }
}

/** The sheet of [actions] for the [session] on screen (a step for another session is never shown), with the notification ask. */
@Composable
fun DownloadSheetHost(actions: DownloadActions?, session: app.shoalwatch.data.servers.Session?, asked: () -> Boolean, markAsked: () -> Unit) {
    if (actions == null) return
    val sheet by actions.sheet.collectAsStateWithLifecycle()
    val ask = rememberNotificationAsk(asked, markAsked)
    DownloadSheet(sheet?.takeIf { it.session == session }, remember(actions) { DownloadSheetActions(actions::choose, actions::confirm, actions::dismiss) }, ask)
}
