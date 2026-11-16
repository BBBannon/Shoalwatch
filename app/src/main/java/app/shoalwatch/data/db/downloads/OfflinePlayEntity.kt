package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/**
 * What one profile watched from a saved copy, for Milestone 5's outbox to send with its original dates (SPEC §3.2).
 * [playedAt] is when it was finished, by the phone's clock, and is never cleared once set. Deleted with the account; kept
 * when the download itself is deleted (the watching still happened). Times are epoch milliseconds.
 */
@Entity(
    tableName = "offline_plays",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OfflinePlayEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val positionMs: Long,
    val played: Boolean,
    val playedAt: Long?,
    val lastPlayedAt: Long,
    val updatedAt: Long,
)
