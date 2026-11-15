package app.shoalwatch.ui.components

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.shoalwatch.R

/**
 * Plain text cut to [collapsedLines] lines with "Show more" to read the rest and "Show less" to fold it again; the button says whether the text is expanded. The button only appears when
 * something is actually hidden. Left-aligned, never interpreted as markup or as links.
 */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 3) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = state }) {
                Text(stringResource(if (expanded) R.string.action_less else R.string.action_more), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
