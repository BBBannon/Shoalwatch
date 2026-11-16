package app.shoalwatch.data.db.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ViewDao {
    @Query("SELECT * FROM views WHERE serverId = :serverId AND userId = :userId ORDER BY position")
    abstract fun observeViews(serverId: String, userId: String): Flow<List<ViewEntity>>

    @Query("SELECT * FROM views WHERE serverId = :serverId AND userId = :userId ORDER BY position")
    abstract suspend fun getViews(serverId: String, userId: String): List<ViewEntity>

    @Upsert
    protected abstract suspend fun upsertAll(views: List<ViewEntity>)

    @Query("DELETE FROM views WHERE serverId = :serverId AND userId = :userId AND viewId NOT IN (:keep)")
    protected abstract suspend fun deleteOthers(serverId: String, userId: String, keep: List<String>)

    @Query("DELETE FROM views WHERE serverId = :serverId AND userId = :userId")
    protected abstract suspend fun deleteAll(serverId: String, userId: String)

    /** Makes the user's stored views exactly [views] (in that order), in one transaction: views no longer listed are deleted. */
    @Transaction
    open suspend fun replaceViews(serverId: String, userId: String, views: List<ViewEntity>) {
        if (views.isEmpty()) return deleteAll(serverId, userId)
        upsertAll(views)
        deleteOthers(serverId, userId, views.map { it.viewId })
    }
}
