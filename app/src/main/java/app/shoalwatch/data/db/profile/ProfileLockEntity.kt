package app.shoalwatch.data.db.profile

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/**
 * One protected profile (Milestone 7). [verifier] is `PinHasher`'s sealed blob (never the PIN). [failures]: wrong PINs since the
 * last right one. Deleted with its account.
 */
@Entity(
    tableName = "profile_locks",
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
class ProfileLockEntity(val serverId: String, val userId: String, val verifier: ByteArray, val failures: Int) {
    override fun toString() = "ProfileLockEntity(<redacted>)"
}
