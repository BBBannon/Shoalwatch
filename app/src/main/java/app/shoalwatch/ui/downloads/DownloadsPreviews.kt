package app.shoalwatch.ui.downloads

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.QueueStatus
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.theme.ShoalwatchTheme
import kotlinx.coroutines.flow.emptyFlow

private val previewSession = Session("0".repeat(32), "1".repeat(32))

private fun previewEntry(n: Int, name: String, state: DownloadState, progress: Float?) = OfflineEntry(
    ItemWithUserData(
        Item(
            id = n.toString().padStart(32, '0'), kind = ItemKind.MOVIE, name = name, sortName = null, parentId = null, seriesId = null, seriesName = null,
            seasonId = null, indexNumber = null, parentIndexNumber = null, overview = null, runtimeMs = 5_400_000, year = 2020, premiereDate = null,
            communityRating = null, officialRating = null, childCount = null, images = ItemImages(null, null, null, null),
        ),
        null,
    ),
    DownloadInfo(n.toString().padStart(32, '0'), state, null, DownloadQuality.ORIGINAL, progress, 1_800_000_000, true, StorageKind.INTERNAL, true),
)

private val previewActions = DownloadsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(showBackground = true, name = "Downloads")
@Composable
private fun DownloadsScreenPreview() = ShoalwatchTheme {
    DownloadsScreen(
        DownloadsUiState(
            previewSession,
            OfflineLibrary(listOf(previewEntry(1, "The Long Film", DownloadState.RUNNING, 0.45f)), listOf(previewEntry(2, "Ready Film", DownloadState.COMPLETE, 1f)), emptyList()),
            QueueStatus.WaitingForWifi, online = false, loading = false, localFailure = false, confirmRemove = null,
        ),
        previewActions, emptyFlow(), SnackbarHostState(), onOpenSettings = {},
    )
}
