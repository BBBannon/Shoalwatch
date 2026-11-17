package app.shoalwatch.ui.downloads

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.shoalwatch.R
import app.shoalwatch.ui.components.LocalCovered

/**
 * The one-time explanation before Android 13's notification prompt, on the first confirm while notifications are not enabled.
 * "Continue" asks the system; "Not now" does not; either way the download goes ahead (it never blocks), and it is asked once
 * (remembered by [asked] / [markAsked], `download_prefs`).
 */
class NotificationAskState internal constructor(
    private val needed: () -> Boolean,
    private val asked: () -> Boolean,
    private val markAsked: () -> Unit,
    internal val pending: MutableState<(() -> Unit)?>,
) {
    /** Runs [then] at once, or after the explanation when it is due. */
    fun beforeFirstConfirm(then: () -> Unit) {
        if (!needed() || asked()) return then()
        markAsked()
        pending.value = then
    }
}

@Composable
fun rememberNotificationAsk(asked: () -> Boolean = { true }, markAsked: () -> Unit = {}): NotificationAskState {
    val context = LocalContext.current
    return remember(asked, markAsked) {
        NotificationAskState(
            needed = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(context).areNotificationsEnabled() },
            asked = asked, markAsked = markAsked, pending = mutableStateOf(null),
        )
    }
}

@Composable
internal fun NotificationAskDialog(state: NotificationAskState) {
    // The launcher outlives the dialog, so the answer still reaches the download it was asked for.
    val waiting = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> waiting.value?.invoke(); waiting.value = null }
    val then = state.pending.value ?: return
    fun close() { state.pending.value = null }
    if (LocalCovered.current) return // under the lock cover (review M1)
    AlertDialog(
        onDismissRequest = { close(); then() },
        text = { Text(stringResource(R.string.dl_notify_ask)) },
        confirmButton = {
            TextButton(onClick = {
                close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { waiting.value = then; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } else then()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = { close(); then() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dl_not_now)) } },
    )
}
