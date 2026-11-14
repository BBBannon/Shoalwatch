package app.offlinefin.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val MinTarget = 56.dp

/** The one filled action on a screen. While [loading] it shows a spinner beside the same label, so nothing shifts. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = MinTarget)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, textAlign = TextAlign.Center)
    }
}

/** A calmer filled action, used for the safe choice ("Cancel") on a warning so it is the easy one to take. */
@Composable
fun SafeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = MinTarget)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/** A secondary action as a text button (48dp minimum target). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/** A text button in the error colour for the risky choice ("Use anyway", "Remove server"). */
@Composable
fun CautionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fillWidth: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = (if (fillWidth) modifier.fillMaxWidth() else modifier).heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/** A text button with a leading icon, for the actions listed on a card. */
@Composable
fun IconTextButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caution: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = if (caution) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
