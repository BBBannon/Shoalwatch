package app.offlinefin.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A signed-in user on a server. [accessToken] is SecretBox ciphertext only. Deleted with its server. */
@Entity(
    tableName = "accounts",
    primaryKeys = ["serverId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
class AccountEntity(
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: ByteArray,
)

/**
 * The single "which account is active" marker: [slot] is always 0, so there can never be two rows.
 * Deleted automatically when the account (or its server) is deleted.
 */
@Entity(
    tableName = "active_account",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["slot"],
    indices = [Index("serverId", "userId")],
)
class ActiveAccountEntity(
    val slot: Int = 0,
    val serverId: String,
    val userId: String,
)
