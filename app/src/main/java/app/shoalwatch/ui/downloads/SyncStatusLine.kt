package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.shoalwatch.data.sync.SyncStatus
import app.shoalwatch.ui.present.SyncText
import app.shoalwatch.ui.present.rememberStrings
import java.time.ZoneId

/**
 * One profile's sync line (see [SyncText]); nothing at all when there is nothing to say. Plain text that wraps (at least 14sp),
 * read by TalkBack as one element. It is a polite live region only in the frame its main words changed (the count, or what is
 * holding the changes back), so a new "Last synced" time alone is not announced.
 */
@Composable
fun SyncStatusLine(status: SyncStatus, nowMs: Long, modifier: Modifier = Modifier) {
    val words = SyncText.words(status, nowMs, ZoneId.systemDefault(), rememberStrings())
    val shown = remember { Shown() } // not state, so writing it recomposes nothing
    val changed = shown.composed && shown.main != words?.main
    SideEffect { shown.composed = true; shown.main = words?.main }
    if (words == null) return
    Column(modifier.semantics(mergeDescendants = true) { if (changed) liveRegion = LiveRegionMode.Polite }) {
        Text(words.main, style = MaterialTheme.typography.bodyMedium)
        words.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The main words the line last composed with (null: nothing shown). */
private class Shown {
    var composed = false
    var main: String? = null
}
