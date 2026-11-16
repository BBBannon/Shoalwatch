package app.shoalwatch.data.db.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.data.downloads.SharedDelete

/**
 * Per-server download settings, kept with the server (SPEC §3.3): "Share downloads between profiles", off unless switched
 * on, and "Delete shared downloads" ([deleteShared], Milestone 5), ANYONE unless changed. No row means off and ANYONE.
 */
@Entity(
    tableName = "download_settings",
    primaryKeys = ["serverId"],
    foreignKeys = [
        ForeignKey(entity = ServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class DownloadSettingsEntity(
    val serverId: String,
    @ColumnInfo(defaultValue = "0") val shareBetweenProfiles: Boolean,
    @ColumnInfo(defaultValue = "ANYONE") val deleteShared: SharedDelete = SharedDelete.ANYONE,
)
