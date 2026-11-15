package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind

/**
 * One download, keyed by server + item (SPEC §3.3): the file is shared by every profile on that server, and who has it in
 * their library is [DownloadOwnerEntity]. Room is the queue: [state] and [queuePosition] are what the worker drains. Deleted
 * with its server. There is deliberately NO foreign key to `items`: the download and its pinned item rows are written in one
 * transaction, maintenance never deletes a pinned row, and a download must never vanish because metadata was refreshed.
 *
 * Value rules (checked by the writers, Tasks 3 to 10): [kind] MOVIE or EPISODE only; [volumeId] the StorageVolume uuid
 * `[A-Za-z0-9-]{1,36}` for REMOVABLE, null for INTERNAL; [mediaSourceId] `[A-Za-z0-9]{1,64}`; [container] one of
 * `DownloadPaths.MEDIA_EXTENSIONS` or "hls"; [validator] the ETag or Last-Modified for If-Range, 1..128 printable ASCII;
 * [sha256] 64 lower-case hex, set on COMPLETE. Times are epoch milliseconds. [verifiedAt] on a row that is not COMPLETE is
 * the time of its one failed check (Task 10: a second failed check is CHECK_FAILED); a retry from the UI clears it.
 */
@Entity(
    tableName = "downloads",
    primaryKeys = ["serverId", "itemId"],
    foreignKeys = [
        ForeignKey(entity = ServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("serverId", "seriesId"), Index("state", "queuePosition")],
)
data class DownloadEntity(
    val serverId: String,
    val itemId: String,
    val kind: ItemKind,
    val seriesId: String?,
    val seasonId: String?,
    val quality: DownloadQuality,
    val state: DownloadState,
    val failure: DownloadFailure?,
    val storage: StorageKind,
    val volumeId: String?,
    val mediaSourceId: String?,
    val container: String?,
    val totalBytes: Long?,
    val doneBytes: Long,
    val segmentsTotal: Int?,
    val segmentsDone: Int,
    val validator: String?,
    val restarts: Int,
    val attempts: Int,
    val runtimeMs: Long?,
    val audioIndex: Int?,
    val sha256: String?,
    val queuePosition: Long,
    val requestedAt: Long,
    val completedAt: Long?,
    val verifiedAt: Long?,
)
