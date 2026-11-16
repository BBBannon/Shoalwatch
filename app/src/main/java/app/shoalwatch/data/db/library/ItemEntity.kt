package app.shoalwatch.data.db.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.data.library.ItemKind

/**
 * One film, series, season or episode as a server described it. Scoped by server only: two profiles on the same server
 * share these rows (and, in Milestone 4, the same downloaded file); what each profile has done with an item is in
 * [UserDataEntity]. Times are epoch milliseconds. Deleted with its server.
 *
 * [nameKey] is the lower-cased [name], kept so offline search is case-insensitive for every script (SQLite's own
 * `LIKE` and `lower()` only fold ASCII). [pinned] is reserved for downloads (Milestone 4): a pinned row, and the
 * series/season/parent rows it points to, are never evicted. [fetchedAt] is when this row was last written from a server
 * response.
 */
@Entity(
    tableName = "items",
    primaryKeys = ["serverId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("serverId", "parentId"),
        Index("serverId", "seriesId", "seasonId", "parentIndexNumber", "indexNumber"),
    ],
)
data class ItemEntity(
    val serverId: String,
    val itemId: String,
    val kind: ItemKind,
    val name: String,
    val nameKey: String,
    val sortName: String?,
    val parentId: String?,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val overview: String?,
    val runtimeMs: Long?,
    val year: Int?,
    val premiereDate: Long?,
    val communityRating: Float?,
    val officialRating: String?,
    val childCount: Int?,
    val primaryTag: String?,
    val backdropTag: String?,
    val thumbTag: String?,
    val seriesPrimaryTag: String?,
    val fetchedAt: Long,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)
