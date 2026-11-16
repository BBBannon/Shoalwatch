package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * "This profile has the download in its library." Deleted with the account (what then happens to a download nobody owns is
 * Task 12's ownership rule) and with the download. Seeing a download still needs the profile's own `visible_items` record.
 *
 * [fetched]: this owner's own sign-in fetched the file or conversion that is on the phone (at most one owner of a download has
 * it). Every other owner (it inherited the copy, or joined it through "already on this phone", or added it while another
 * profile's sign-in was fetching it) stays under the strict sharing rule: re-asked after 7 days, refused while stale and the
 * server can be reached, and removed as an owner on an explicit "no" (final review M-1).
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
data class DownloadOwnerEntity(val serverId: String, val userId: String, val itemId: String, val addedAt: Long, val fetched: Boolean = false)
