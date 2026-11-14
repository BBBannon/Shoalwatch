package app.offlinefin.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE serverId = :serverId ORDER BY userName COLLATE NOCASE, userId")
    fun observeForServer(serverId: String): Flow<List<AccountEntity>>

    @Query(
        "SELECT a.* FROM accounts a JOIN active_account x ON a.serverId = x.serverId AND a.userId = x.userId",
    )
    fun observeActive(): Flow<AccountEntity?>

    @Query(
        "SELECT a.* FROM accounts a JOIN active_account x ON a.serverId = x.serverId AND a.userId = x.userId",
    )
    suspend fun getActive(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE serverId = :serverId AND userId = :userId")
    suspend fun get(serverId: String, userId: String): AccountEntity?

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    /** An upsert, not REPLACE: REPLACE would delete the row and cascade away the active marker. */
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE serverId = :serverId AND userId = :userId")
    suspend fun delete(serverId: String, userId: String)

    @Upsert
    suspend fun setActive(marker: ActiveAccountEntity)
}
