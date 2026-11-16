package app.shoalwatch.data.db.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/** When this profile's outbox was last fully sent (epoch milliseconds). Deleted with the account. */
@Entity(
    tableName = "sync_state",
    primaryKeys = ["serverId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncStateEntity(val serverId: String, val userId: String, val lastSyncedAt: Long) {
    override fun toString() = "SyncStateEntity(<redacted>)"
}
