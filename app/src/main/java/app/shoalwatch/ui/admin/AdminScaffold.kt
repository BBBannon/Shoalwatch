package app.shoalwatch.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.Loading
import app.shoalwatch.ui.components.NoticeCard
import app.shoalwatch.ui.components.SecureWindow
import app.shoalwatch.ui.components.Tone
import app.shoalwatch.ui.present.rememberStrings
import java.time.ZoneId

/** Admin content is never wider than this; centred on a tablet. */
private val AdminMaxWidth = 840.dp

/** The plain-words mapper for the current language and the phone's time zone. */
@Composable
fun rememberAdminText(): AdminText {
    val strings = rememberStrings()
    return remember(strings) { AdminText(strings, ZoneId.systemDefault()) }
}

/**
 * Every admin screen. Sets FLAG_SECURE (SecureWindow). Closed: an empty, unlabelled surface and nothing else. Checking: "Checking
 * you're an administrator…". Open: top bar (title as heading, Back, a Refresh action named "Refresh"), then a LazyColumn capped at
 * 840dp and centred: the offline notice when not online, the load-problem line, the problem line (live region, Dismiss), the
 * notice line (live region, Dismiss), "Last updated 14:02:05", then [content].
 */
@Composable
fun AdminScaffold(
    title: String,
    frame: AdminFrame,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)?,
    onDismissProblem: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    SecureWindow()
    when (frame.gate) {
        AdminGate.Closed -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AdminGate.Checking -> Frame(title, onBack, null) { Loading(stringResource(R.string.admin_checking)) }
        is AdminGate.Open -> Frame(title, onBack, onRefresh) { OpenContent(frame, onDismissProblem, onDismissNotice, content) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Frame(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)?, body: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.admin_refresh)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) { body() }
    }
}

@Composable
private fun OpenContent(frame: AdminFrame, onDismissProblem: () -> Unit, onDismissNotice: () -> Unit, content: LazyListScope.() -> Unit) {
    val text = rememberAdminText()
    val loadLine = frame.loadProblem?.let { if (frame.updatedAt != null) stringResource(R.string.admin_refresh_failed) else text.problem(it) }
    val problemLine = frame.problem?.let(text::problem)
    val noticeLine = frame.notice?.let { stringResource(it.text, *it.args.toTypedArray()) }
    val updated = frame.updatedAt?.let { stringResource(R.string.admin_updated_at, text.clock(it)) }
    LazyColumn(
        Modifier.widthIn(max = AdminMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!frame.online) {
            item(key = "offline") { NoticeCard(stringResource(R.string.admin_offline_title), stringResource(R.string.admin_offline_body), announce = true) }
        }
        if (loadLine != null) item(key = "load-problem") { ErrorMessage(loadLine) }
        if (problemLine != null) item(key = "problem") { Dismissable(onDismissProblem) { ErrorMessage(problemLine, Modifier.weight(1f)) } }
        if (noticeLine != null) {
            item(key = "notice") {
                Dismissable(onDismissNotice) {
                    Text(
                        noticeLine,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        if (updated != null) {
            item(key = "updated") { Text(updated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content()
    }
}

@Composable
private fun Dismissable(onDismiss: () -> Unit, line: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        line()
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_dismiss)) }
    }
}
