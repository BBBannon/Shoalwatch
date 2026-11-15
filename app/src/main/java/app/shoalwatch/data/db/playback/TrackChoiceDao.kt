package app.shoalwatch.data.db.playback

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Reads and writes one user's remembered track choice; every query names the server and the user. */
@Dao
interface TrackChoiceDao {
    @Query("SELECT * FROM track_choices WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId")
    suspend fun get(serverId: String, userId: String, seriesId: String): TrackChoiceEntity?

    /** Room's insert-then-update, never `REPLACE` and never SQL UPSERT (SQLite 3.22). */
    @Upsert
    suspend fun upsert(choice: TrackChoiceEntity)
}
