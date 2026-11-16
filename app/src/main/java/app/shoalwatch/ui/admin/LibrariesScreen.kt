package app.shoalwatch.ui.admin

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.shoalwatch.R
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.ui.components.PrimaryButton

class LibrariesActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onScanAll: () -> Unit,
    val onScan: (libraryId: String) -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** "Scan all libraries" (with the full scan's progress while it runs), then one card per library with its own scan. */
@Composable
fun LibrariesScreen(state: LibrariesUiState, actions: LibrariesActions) {
    val text = rememberAdminText()
    AdminScaffold(
        stringResource(R.string.admin_libraries_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        item(key = "scan-all") { PrimaryButton(stringResource(R.string.admin_scan_all), actions.onScanAll, enabled = state.canScanAll) }
        state.fullScan?.takeIf { it.state == TaskState.RUNNING }?.let { scan ->
            item(key = "scan-all-progress") {
                val label = scan.progressPercent?.let { stringResource(R.string.admin_scan_all_running, it.toString()) }
                    ?: stringResource(R.string.admin_scan_all_running_unknown)
                AdminProgress(scan.progressPercent, label)
            }
        }
        if (state.libraries.isEmpty() && state.frame.updatedAt != null) {
            item(key = "empty") { Text(stringResource(R.string.admin_libraries_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.libraries, key = { "library-${it.id}" }) { library ->
            LibraryCard(library, text, state.canScan(library)) { actions.onScan(library.id) }
        }
    }
}

@Composable
private fun LibraryCard(library: LibraryFolder, text: AdminText, canScan: Boolean, onScan: () -> Unit) {
    val name = text.name(library.name, R.string.admin_untitled)
    AdminCard {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(text.libraryKind(library.kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (library.scanning) {
            val label = library.progressPercent?.let { stringResource(R.string.admin_library_scanning, it.toString()) }
                ?: stringResource(R.string.admin_library_scanning_unknown)
            AdminProgress(library.progressPercent, label)
        } else {
            Text(stringResource(R.string.admin_library_idle), style = MaterialTheme.typography.bodyMedium)
        }
        AdminAction(stringResource(R.string.admin_scan), stringResource(R.string.admin_scan_cd, name), canScan, onClick = onScan)
    }
}
