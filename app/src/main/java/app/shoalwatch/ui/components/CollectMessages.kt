package app.shoalwatch.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

/** Shows each message from a ViewModel (a failed action, "Playback arrives in the next update") in the snackbar, one after another. Material announces them to screen readers. */
@Composable
fun CollectMessages(messages: Flow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages, snackbar) { messages.collect { snackbar.showSnackbar(it) } }
}
