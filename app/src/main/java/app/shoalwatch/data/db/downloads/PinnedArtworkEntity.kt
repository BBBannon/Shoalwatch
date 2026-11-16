package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.data.downloads.ArtworkSlot

/**
 * A picture saved for offline use, outside Coil's evictable cache. Keyed by item, not by download: a series' poster is
 * pinned by every downloaded episode, so it hangs off the server and housekeeping removes it when nothing pins the item
 * any more (Task 12). [tag] is the validated image tag `[A-Za-z0-9]{1,64}`.
 */
@Entity(
    tableName = "pinned_artwork",
    primaryKeys = ["serverId", "itemId", "slot"],
    foreignKeys = [
        ForeignKey(entity = ServerEntity::class, parentColumns = ["id"], childColumns = ["serverId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PinnedArtworkEntity(val serverId: String, val itemId: String, val slot: ArtworkSlot, val tag: String, val bytes: Long, val savedAt: Long)
