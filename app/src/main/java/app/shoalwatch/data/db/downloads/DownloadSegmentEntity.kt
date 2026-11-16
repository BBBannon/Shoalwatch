package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.playback.SegmentKind

/** A skip marker (intro, outro...) saved with a download, so Skip works offline. Deleted with the download. */
@Entity(
    tableName = "download_segments",
    primaryKeys = ["serverId", "itemId", "kind", "startMs"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["serverId", "itemId"],
            childColumns = ["serverId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadSegmentEntity(val serverId: String, val itemId: String, val kind: SegmentKind, val startMs: Long, val endMs: Long)
