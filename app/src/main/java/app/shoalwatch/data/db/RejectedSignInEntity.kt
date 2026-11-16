package app.shoalwatch.data.db

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The server refused this account's token (a 401), kept so a restart does not try the rejected token again (Milestone 2
 * check I4). Deleted with the account, so signing in again starts clean. [rejectedAt] is epoch milliseconds.
 */
@Entity(
    tableName = "rejected_sign_ins",
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
data class RejectedSignInEntity(val serverId: String, val userId: String, val rejectedAt: Long) {
    override fun toString() = "RejectedSignInEntity(<redacted>)"
}
