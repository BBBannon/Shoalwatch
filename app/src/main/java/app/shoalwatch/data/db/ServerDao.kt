package app.shoalwatch.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A server without its addresses or secrets: what lists read. No `customHeaders`, so no decryption. */
class ServerRow(val id: String, val name: String, val version: String, val pinnedSpkiSha256: String?)

@Dao
interface ServerDao {
    @Query("SELECT id, name, version, pinnedSpkiSha256 FROM servers ORDER BY name COLLATE NOCASE, id")
    fun observeRows(): Flow<List<ServerRow>>


    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: String): ServerEntity?

    /** An upsert, not REPLACE: REPLACE deletes the row first, which would cascade away its accounts. */
    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: String)
}
