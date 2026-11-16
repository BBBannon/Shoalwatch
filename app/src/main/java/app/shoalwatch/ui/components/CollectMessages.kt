package app.shoalwatch.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow

/**
 * Shows each message from a ViewModel (a failed action, such as marking an item watched) in the snackbar, one after another.
 * Material announces them to screen readers. A message belongs to the [session] that caused it: the one on screen goes as soon as
 * the session changes (see [DismissOnSessionChange]).
 */
@Composable
fun CollectMessages(messages: Flow<String>, snackbar: SnackbarHostState, session: Session?) {
    DismissOnSessionChange(snackbar, session)
    LaunchedEffect(messages, snackbar) { messages.collect { snackbar.showSnackbar(it) } }
}

/** Takes down whatever the snackbar is showing when [session] changes, so nothing one account caused stays up for the next. */
@Composable
fun DismissOnSessionChange(snackbar: SnackbarHostState, session: Session?) {
    LaunchedEffect(snackbar, session) { snackbar.currentSnackbarData?.dismiss() }
}
