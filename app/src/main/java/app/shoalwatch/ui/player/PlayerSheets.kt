package app.shoalwatch.ui.player

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlaybackRules
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.SleepState
import app.shoalwatch.data.playback.SubtitleChoice
import app.shoalwatch.ui.present.PlayerText
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.components.LocalCovered

/**
 * The player's sheets: one open at a time ([PlayerUiState.sheet]). Every sheet scrolls (large text, a phone held sideways), every
 * choice is a radio row that says which is selected, at least 48dp high.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetHost(state: PlayerUiState, actions: PlayerActions, streamLine: String?, hdrLine: String?) {
    val sheet = state.sheet ?: return
    if (LocalCovered.current) return // under the lock cover (review M1)
    ModalBottomSheet(onDismissRequest = actions.onCloseSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (sheet) {
                PlayerSheet.TRACKS -> TracksSheet(state, actions)
                PlayerSheet.QUALITY -> QualitySheet(state, actions)
                PlayerSheet.SPEED -> SpeedSheet(state, actions)
                PlayerSheet.SLEEP -> SleepSheet(state, actions)
                PlayerSheet.SUBTITLE_STYLE -> SubtitleStyleSheet(state, actions)
                PlayerSheet.STREAM_INFO -> StreamInfoSheet(state, streamLine, hdrLine)
            }
        }
    }
}

@Composable
private fun ColumnScope.TracksSheet(state: PlayerUiState, actions: PlayerActions) {
    val p = state.playback
    SheetHeading(R.string.player_tracks)
    if (p.audioTracks.isNotEmpty()) {
        SubHeading(R.string.player_audio_heading)
        p.audioTracks.forEach { t -> ChoiceRow(t.label, t.index == p.audioIndex) { actions.onSelectAudio(t.index) } }
    }
    SubHeading(R.string.player_subtitles_heading)
    val on = (p.subtitle as? SubtitleChoice.On)?.index
    ChoiceRow(stringResource(R.string.player_subtitles_off), on == null) { actions.onSelectSubtitle(null) }
    p.subtitleTracks.forEach { t -> ChoiceRow(t.label, t.index == on) { actions.onSelectSubtitle(t.index) } }
    if (p.subtitleEffectsNotDrawn && on != null && p.method != PlayMethod.LOCAL) { // a saved copy is never redrawn by the server
        Text(stringResource(R.string.subtitle_let_server_draw_detail), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        OutlinedButton(onClick = { actions.onBurnIn(on) }, modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.subtitle_let_server_draw))
        }
    }
}

@Composable
private fun ColumnScope.QualitySheet(state: PlayerUiState, actions: PlayerActions) {
    val strings = rememberStrings()
    SheetHeading(R.string.player_quality)
    if (state.playback.method == PlayMethod.LOCAL) {
        Text(stringResource(R.string.player_quality_saved), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        return
    }
    Quality.entries.forEach { q -> ChoiceRow(PlayerText.quality(strings, q), q == state.playback.quality) { actions.onQuality(q) } }
}

@Composable
private fun ColumnScope.SpeedSheet(state: PlayerUiState, actions: PlayerActions) {
    val strings = rememberStrings()
    SheetHeading(R.string.player_speed)
    PlaybackRules.SPEEDS.sorted().forEach { s -> ChoiceRow(PlayerText.speed(strings, s), s == state.playback.speed) { actions.onSpeed(s) } }
}

@Composable
private fun ColumnScope.SleepSheet(state: PlayerUiState, actions: PlayerActions) {
    val strings = rememberStrings()
    SheetHeading(R.string.player_sleep)
    val sleep = state.playback.sleep
    SleepChoice.entries.forEach { c ->
        // A timed choice running now is not told apart by its length (the state holds only when it ends): none is marked.
        val selected = (c == SleepChoice.OFF && sleep == SleepState.Off) || (c == SleepChoice.END_OF_ITEM && sleep == SleepState.EndOfItem)
        ChoiceRow(PlayerText.sleep(strings, c, episode = state.playback.episodeLine != null), selected) { actions.onSleep(c) }
    }
}

@Composable
private fun ColumnScope.StreamInfoSheet(state: PlayerUiState, streamLine: String?, hdrLine: String?) {
    val strings = rememberStrings()
    SheetHeading(R.string.player_stream_info)
    val quality = PlayerText.quality(strings, state.playback.quality).takeIf { state.playback.method != PlayMethod.LOCAL }
    listOfNotNull(streamLine, hdrLine, quality).forEach {
        Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
    }
}

@Composable
internal fun SheetHeading(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
}

@Composable
internal fun SubHeading(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
}

/** One choice: the whole row is the radio button (role, selected state), 48dp or more. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
