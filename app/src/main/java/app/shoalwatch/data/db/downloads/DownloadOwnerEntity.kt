package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * "This profile has the download in its library." Deleted with the account (what then happens to a download nobody owns is
 * Task 12's ownership rule) and with the download. Seeing a download still needs the profile's own `visible_items` record.
 */
@Entity(
    tableName = "download_owners",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["serverId", "itemId"],
            childColumns = ["serverId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId", "itemId")],
)
data class DownloadOwnerEntity(val serverId: String, val userId: String, val itemId: String, val addedAt: Long)
