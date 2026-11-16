package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * "The server confirmed that this profile may download this item" (its own `CanDownload` was true, or `/Download` served it to
 * this profile's own sign-in). The strict sharing rule (controller decision, Tasks 9-10 review): with sharing on, a profile that
 * does not own a download sees it, and can inherit it when the owner's account goes, ONLY with this row. Unknown or false is no
 * row. Deleted with the account and with the download.
 */
@Entity(
    tableName = "download_permissions",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["serverId", "userId"], childColumns = ["serverId", "userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["serverId", "itemId"], childColumns = ["serverId", "itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("serverId", "itemId")],
)
data class DownloadPermissionEntity(val serverId: String, val userId: String, val itemId: String, val confirmedAt: Long)
