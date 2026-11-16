package app.shoalwatch.data.db.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity
import app.shoalwatch.data.downloads.DownloadQuality

/**
 * "Keep the next episodes of this series downloaded for this profile", after [anchorItemId] was watched. [tries] counts the
 * plans made for it; [blockedAt] is when the last plan had too little free space (null otherwise). Deleted with the account.
 * Times are epoch milliseconds.
 */
@Entity(
    tableName = "auto_next_requests",
    primaryKeys = ["serverId", "userId", "seriesId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AutoNextRequestEntity(
    val serverId: String,
    val userId: String,
    val seriesId: String,
    val anchorItemId: String,
    val quality: DownloadQuality,
    val requestedAt: Long,
    @ColumnInfo(defaultValue = "0") val tries: Int,
    val blockedAt: Long?,
) {
    override fun toString() = "AutoNextRequestEntity(<redacted>)"
}
