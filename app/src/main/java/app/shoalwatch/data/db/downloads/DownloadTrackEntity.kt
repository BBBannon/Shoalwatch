package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.data.playback.SubtitleKind

/**
 * An audio or subtitle track of a saved download, as the player will offer it offline. [fileSaved] means a TEXT or
 * STYLED_TEXT subtitle was saved beside the media as `sub-<streamIndex>.<fileFormat>`. Deleted with the download.
 */
@Entity(
    tableName = "download_tracks",
    primaryKeys = ["serverId", "itemId", "type", "streamIndex"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["serverId", "itemId"],
            childColumns = ["serverId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadTrackEntity(
    val serverId: String,
    val itemId: String,
    val type: TrackType,
    val streamIndex: Int,
    val label: String,
    val language: String?,
    val codec: String?,
    val kind: SubtitleKind?,
    val fileFormat: String?,
    val forced: Boolean,
    val hearingImpaired: Boolean,
    val isDefault: Boolean,
    val external: Boolean,
    val channels: Int?,
    val fileSaved: Boolean,
)
