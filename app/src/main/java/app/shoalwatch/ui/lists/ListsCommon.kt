package app.shoalwatch.ui.lists

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.core.displayText
import app.shoalwatch.data.lists.ListsLimits
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.CardShape
import app.shoalwatch.ui.components.DismissOnSessionChange
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.NoKeyboardLearning
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import kotlinx.coroutines.flow.Flow

/** Whether a screen's live read has arrived, is waiting, or failed (offline gets its own words and Try again). */
enum class ListsPhase { LOADING, READY, OFFLINE, GONE, FAILED }

internal fun phaseFor(e: Exception): ListsPhase = when ((e as? AppError)?.kind) {
    AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> ListsPhase.OFFLINE
    AppError.Kind.SERVER_NOT_FOUND -> ListsPhase.GONE
    else -> ListsPhase.FAILED
}

/** The words for a change that failed. A refusal is always the one sentence from the plan and never ends the sign-in. */
@StringRes
internal fun failureText(e: Exception?): Int = when ((e as? AppError)?.kind) {
    AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> R.string.lists_offline_change
    AppError.Kind.FORBIDDEN -> R.string.lists_refused
    AppError.Kind.SERVER_NOT_FOUND -> R.string.lists_gone
    else -> R.string.lists_failed
}

/**
 * One snackbar line for a playlist or collection action: a plural (the count, then [arg]) or plain words (with [arg] if given).
 * Shown only to its own session.
 */
data class ListNotice(val session: Session, @PluralsRes val plural: Int = 0, val count: Int = 0, @StringRes val text: Int = 0, val arg: String? = null) {
    fun resolve(resources: Resources): String = when {
        plural != 0 -> resources.getQuantityString(plural, count, count, arg.orEmpty())
        arg != null -> resources.getString(text, arg)
        else -> resources.getString(text)
    }

    override fun toString() = "ListNotice(<redacted>)"
}

/** What a typed name came to: a name to send (cleaned: 1 to [ListsLimits.NAME] visible characters), or why not. */
internal sealed interface ListName {
    data class Valid(val text: String) : ListName
    data object Empty : ListName
    data object TooLong : ListName
}

internal fun cleanListName(raw: String): ListName {
    val text = displayText(raw, ListsLimits.NAME + 1)
    return when {
        text.isEmpty() -> ListName.Empty
        text.codePointCount(0, text.length) > ListsLimits.NAME -> ListName.TooLong
        else -> ListName.Valid(text)
    }
}

/** The words for a name that cannot be sent. */
@StringRes
internal fun ListName.problem(): Int = when (this) {
    ListName.Empty -> R.string.lists_name_empty
    ListName.TooLong -> R.string.lists_name_too_long
    is ListName.Valid -> 0
}

/** Shows each [ListNotice] once in [snackbar], dismissed when the session changes. */
@Composable
fun ListNoticesEffect(notices: Flow<ListNotice>, snackbar: SnackbarHostState, session: Session?) {
    val resources = LocalContext.current.resources
    DismissOnSessionChange(snackbar, session)
    LaunchedEffect(notices, snackbar) { notices.collect { snackbar.showSnackbar(it.resolve(resources)) } }
}

/** A list as a large tappable card (an icon, a name, one line under it): the Playlists screen and Home's Playlists card. */
@Composable
fun ListCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = if (title == subtitle) title else stringResource(R.string.library_card_cd, title, subtitle)
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp).semantics { contentDescription = description },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(PlaylistIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Lines with a play arrow: playlists. Drawn from plain path data, as the other browse icons are. */
val PlaylistIcon: ImageVector by lazy {
    ImageVector.Builder("Playlist", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M3,10h11v2H3V10zM3,6h11v2H3V6zM3,14h7v2H3V14zM16,13v8l6,-4L16,13z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}

/**
 * A small form in its own secure window: a name field and a confirm button (Create, Save), with the problem with the name or the
 * server's answer under it. [onConfirm] checks the name; nothing is sent from here. Cancel is always the dismiss button.
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    name: TextFieldState,
    @StringRes error: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (LocalCovered.current) return // under the lock cover
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = SecureDialog,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            val obscured = FilterObscuredTouches()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
                NameField(name, error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } },
    )
}

/** The name field, with its problem (if any) under it. */
@Composable
internal fun NameField(name: TextFieldState, @StringRes error: Int) {
    NoKeyboardLearning {
        OutlinedTextField(
            state = name,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.lists_name_label)) },
            isError = error != 0,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        )
    }
    if (error != 0) ErrorMessage(stringResource(error))
}
