package app.shoalwatch.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An account without its token: what screens read. Selecting only these columns keeps the Keystore out of it. */
class AccountRow(val serverId: String, val userId: String, val userName: String)

@Dao
interface AccountDao {
    @Query("SELECT serverId, userId, userName FROM accounts WHERE serverId = :serverId ORDER BY userName COLLATE NOCASE, userId")
    fun observeRowsForServer(serverId: String): Flow<List<AccountRow>>

    @Query(
        "SELECT a.serverId, a.userId, a.userName FROM accounts a JOIN active_account x ON a.serverId = x.serverId AND a.userId = x.userId",
    )
    fun observeActiveRow(): Flow<AccountRow?>

    @Query(
        "SELECT a.serverId, a.userId, a.userName FROM accounts a JOIN active_account x ON a.serverId = x.serverId AND a.userId = x.userId",
    )
    suspend fun getActiveRow(): AccountRow?


    @Query("SELECT * FROM accounts WHERE serverId = :serverId AND userId = :userId")
    suspend fun get(serverId: String, userId: String): AccountEntity?

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE serverId = :serverId")
    suspend fun getForServer(serverId: String): List<AccountEntity>

    /** Every account whose sign-in the server has not rejected (what a restart may load; Milestone 2 check I4). */
    @Query("SELECT a.* FROM accounts a WHERE NOT EXISTS (SELECT 1 FROM rejected_sign_ins r WHERE r.serverId = a.serverId AND r.userId = a.userId)")
    suspend fun getAllUsable(): List<AccountEntity>

    /** Remembers a 401 for the account; throws if there is no such account (foreign key). */
    @Query("INSERT OR IGNORE INTO rejected_sign_ins (serverId, userId, rejectedAt) VALUES (:serverId, :userId, :at)")
    suspend fun reject(serverId: String, userId: String, at: Long)

    @Query("DELETE FROM rejected_sign_ins WHERE serverId = :serverId AND userId = :userId")
    suspend fun clearRejected(serverId: String, userId: String)

    /** An upsert, not REPLACE: REPLACE would delete the row and cascade away the active marker. */
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE serverId = :serverId AND userId = :userId")
    suspend fun delete(serverId: String, userId: String)

    @Upsert
    suspend fun setActive(marker: ActiveAccountEntity)
}
