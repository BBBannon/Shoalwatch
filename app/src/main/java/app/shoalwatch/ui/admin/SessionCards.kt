package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.admin.WatchSession
import java.time.Instant

// "Who is watching" on the dashboard: one card per session that is playing, one row per session that is only connected.
// Every name shown is display text from the mapper (or a neutral word when it was empty); buttons name the user and device.

/**
 * A session that is playing: who, what, where, how far in, whether the server is converting it, and its actions. [now] is taken
 * for the same shape as [IdleSessionRow]; a playing card has no "last active" line.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayingSessionCard(
    session: WatchSession,
    text: AdminText,
    now: Instant,
    canAct: Boolean,
    onMessage: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val playing = session.nowPlaying ?: return
    val user = text.name(session.userName, R.string.admin_unnamed_user)
    val device = text.name(session.deviceName, R.string.admin_unnamed_device)
    AdminCard {
        Header(user, session.isThisDevice)
        val spoken = text.nowPlayingSpoken(playing)
        Text(text.nowPlayingLine(playing), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = spoken })
        Text(text.deviceLine(session), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Progress(playing.positionMs, playing.runtimeMs, playing.paused, text)
        text.method(playing)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (!session.isThisDevice && (session.canMessage || session.canControl)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.canMessage) {
                    AdminAction(stringResource(R.string.admin_session_message), stringResource(R.string.admin_session_message_cd, user, device), canAct, onClick = onMessage)
                }
                if (session.canControl && !playing.paused) {
                    AdminAction(stringResource(R.string.admin_session_pause), stringResource(R.string.admin_session_pause_cd, user, device), canAct, onClick = onPause)
                }
                if (session.canControl) {
                    AdminAction(stringResource(R.string.admin_session_stop), stringResource(R.string.admin_session_stop_cd, user, device), canAct, caution = true, onClick = onStop)
                }
            }
        }
    }
}

/** A session that is connected and not playing: "kid · Kid's tablet · Last active Today at 14:02", and Message when it can be. */
@Composable
internal fun IdleSessionRow(session: WatchSession, text: AdminText, now: Instant, canAct: Boolean, onMessage: () -> Unit) {
    val user = text.name(session.userName, R.string.admin_unnamed_user)
    val device = text.name(session.deviceName, R.string.admin_unnamed_device)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val line = listOf(user, device, stringResource(R.string.admin_last_active, text.ago(session.lastActivity, now))).joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (session.isThisDevice) AdminBadge(stringResource(R.string.admin_this_phone))
        }
        if (session.canMessage && !session.isThisDevice) {
            AdminAction(stringResource(R.string.admin_session_message), stringResource(R.string.admin_session_message_cd, user, device), canAct, onClick = onMessage)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(user: String, thisPhone: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(user, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (thisPhone) AdminBadge(stringResource(R.string.admin_this_phone))
    }
}

/** A thin bar (with its range for TalkBack) and "12:30 of 45:00", plus "Paused" when paused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Progress(positionMs: Long?, runtimeMs: Long?, paused: Boolean, text: AdminText) {
    val words = text.progress(positionMs, runtimeMs)
    if (positionMs != null && runtimeMs != null && runtimeMs > 0) {
        val fraction = (positionMs.toFloat() / runtimeMs).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                words?.let { contentDescription = it }
            },
        )
    }
    if (words != null || paused) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            words?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (paused) Text(stringResource(R.string.admin_session_paused), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
