package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * What one user (profile) has done with one item: watched, where playback stopped, favourite. Scoped by (server, user), so
 * two profiles keep their own. There is deliberately no foreign key to `items`: user data may arrive before its item row or
 * outlive it (watched state must survive eviction of metadata, for sync in a later milestone). [updatedAt] is when this row
 * was last written on this device (epoch ms). Deleted with its account.
 */
@Entity(
    tableName = "user_data",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId", "itemId")],
)
data class UserDataEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val played: Boolean,
    val positionMs: Long,
    val playedPercentage: Float?,
    val playCount: Int,
    val favourite: Boolean,
    val lastPlayed: Long?,
    val unplayedItemCount: Int?,
    val updatedAt: Long,
    val pendingSyncAt: Long? = null,
)
