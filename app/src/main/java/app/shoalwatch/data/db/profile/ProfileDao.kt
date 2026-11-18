package app.shoalwatch.data.db.profile

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Profile locks and per-profile playback settings. `INSERT OR IGNORE` and `UPDATE`, never `REPLACE` (it would delete the row). */
@Dao
interface ProfileDao {
    @Query("SELECT serverId, userId FROM profile_locks")
    fun observeProtected(): Flow<List<ProfileKeyRow>>

    @Query("SELECT * FROM profile_locks WHERE serverId = :serverId AND userId = :userId")
    suspend fun lock(serverId: String, userId: String): ProfileLockEntity?

    /** -1 when the profile is already protected (the first verifier stays). */
    @Query("INSERT OR IGNORE INTO profile_locks (serverId, userId, verifier, failures) VALUES (:serverId, :userId, :verifier, 0)")
    suspend fun insertLock(serverId: String, userId: String, verifier: ByteArray): Long

    @Query("UPDATE profile_locks SET verifier = :verifier, failures = 0 WHERE serverId = :serverId AND userId = :userId")
    suspend fun replaceVerifier(serverId: String, userId: String, verifier: ByteArray): Int

    @Query("UPDATE profile_locks SET failures = :failures WHERE serverId = :serverId AND userId = :userId")
    suspend fun setFailures(serverId: String, userId: String, failures: Int): Int

    @Query("DELETE FROM profile_locks WHERE serverId = :serverId AND userId = :userId")
    suspend fun deleteLock(serverId: String, userId: String): Int

    /** Null when the profile has no playback row (the defaults apply). */
    @Query("SELECT autoplayNext FROM profile_playback WHERE serverId = :serverId AND userId = :userId")
    fun observeAutoplay(serverId: String, userId: String): Flow<Boolean?>

    @Upsert
    suspend fun upsertPlayback(row: ProfilePlaybackEntity)
}

/** Which profile is protected; ids only, never printed. */
data class ProfileKeyRow(val serverId: String, val userId: String) {
    override fun toString() = "ProfileKeyRow(<redacted>)"
}
