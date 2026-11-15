package app.shoalwatch.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleBackground
import app.shoalwatch.data.playback.SubtitleChoice
import app.shoalwatch.data.playback.SubtitleColour
import app.shoalwatch.data.playback.SubtitleDelivery
import app.shoalwatch.data.playback.SubtitleEdge
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.ui.present.PlayerText
import app.shoalwatch.ui.present.rememberStrings

/**
 * Subtitle size, colour, background and edge (radio rows), "Use the file's own styling" (a switch), and the delay, which only a
 * file subtitle drawn by the app can have (otherwise it says why). Every change applies at once.
 */
@Composable
internal fun ColumnScope.SubtitleStyleSheet(state: PlayerUiState, actions: PlayerActions) {
    val a = state.appearance ?: return
    val set = actions.onAppearance
    SheetHeading(R.string.player_subtitle_style)
    SubHeading(R.string.subtitle_size)
    SIZES.forEach { size -> ChoiceRow(stringResource(R.string.subtitle_size_value, size), a.sizePercent == size) { set(a.copy(sizePercent = size)) } }
    SubHeading(R.string.subtitle_colour)
    SubtitleColour.entries.forEach { c -> ChoiceRow(stringResource(colourName(c)), a.colour == c) { set(a.copy(colour = c)) } }
    SubHeading(R.string.subtitle_background)
    SubtitleBackground.entries.forEach { b -> ChoiceRow(stringResource(backgroundName(b)), a.background == b) { set(a.copy(background = b)) } }
    SubHeading(R.string.subtitle_edge)
    SubtitleEdge.entries.forEach { e -> ChoiceRow(stringResource(edgeName(e)), a.edge == e) { set(a.copy(edge = e)) } }
    KeepStyling(a, set)
    Delay(state, actions)
}

@Composable
private fun KeepStyling(a: SubtitleAppearance, set: (SubtitleAppearance) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(a.keepFileStyling, role = Role.Switch) { set(a.copy(keepFileStyling = it)) }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.subtitle_keep_styling), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = a.keepFileStyling, onCheckedChange = null)
    }
}

@Composable
private fun Delay(state: PlayerUiState, actions: PlayerActions) {
    val strings = rememberStrings()
    val p = state.playback
    SubHeading(R.string.subtitle_delay_label)
    val delivery = (p.subtitle as? SubtitleChoice.On)?.delivery
    val reason = when (delivery) {
        SubtitleDelivery.IN_STREAM -> R.string.subtitle_delay_not_in_stream
        SubtitleDelivery.BURNED_IN -> R.string.subtitle_delay_not_burned_in
        else -> null
    }
    val enabled = delivery == SubtitleDelivery.FILE
    Text(PlayerText.delay(strings, p.subtitleDelayMs), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp))
    if (reason != null) Text(stringResource(reason), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val step = SubtitleSettings.DELAY_STEP_MS
        OutlinedButton(onClick = { actions.onDelay(p.subtitleDelayMs - step) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subtitle_delay_earlier))
        }
        OutlinedButton(onClick = { actions.onDelay(p.subtitleDelayMs + step) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subtitle_delay_later))
        }
    }
}

private val SIZES = listOf(75, 100, 150, 200)

private fun colourName(c: SubtitleColour) = when (c) {
    SubtitleColour.WHITE -> R.string.subtitle_colour_white
    SubtitleColour.YELLOW -> R.string.subtitle_colour_yellow
    SubtitleColour.CYAN -> R.string.subtitle_colour_cyan
    SubtitleColour.GREEN -> R.string.subtitle_colour_green
}

private fun backgroundName(b: SubtitleBackground) = when (b) {
    SubtitleBackground.NONE -> R.string.subtitle_background_none
    SubtitleBackground.SHADED -> R.string.subtitle_background_shaded
    SubtitleBackground.SOLID -> R.string.subtitle_background_solid
}

private fun edgeName(e: SubtitleEdge) = when (e) {
    SubtitleEdge.NONE -> R.string.subtitle_edge_none
    SubtitleEdge.OUTLINE -> R.string.subtitle_edge_outline
    SubtitleEdge.DROP_SHADOW -> R.string.subtitle_edge_shadow
}
