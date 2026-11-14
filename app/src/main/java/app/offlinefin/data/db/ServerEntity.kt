package app.offlinefin.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Jellyfin server. [customHeaders] is a SecretBox-sealed JSON object (never plaintext);
 * addresses are [app.offlinefin.net.ServerAddress.value] strings.
 */
@Entity(tableName = "servers")
class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val homeAddress: String?,
    val awayAddress: String?,
    val allowCleartext: Boolean,
    val pinnedSpkiSha256: String?,
    val clientCertAlias: String?,
    val customHeaders: ByteArray,
)
