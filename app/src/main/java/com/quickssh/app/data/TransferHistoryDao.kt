package com.quickssh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val TRANSFER_HISTORY_RETAIN_LIMIT = 300

@Dao
interface TransferHistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY createdAt DESC, id DESC")
    fun getAllFlow(): Flow<List<TransferHistoryEntry>>

    @Query("SELECT COUNT(*) FROM transfer_history")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TransferHistoryEntry): Long

    @Update
    suspend fun update(entry: TransferHistoryEntry)

    @Query("SELECT * FROM transfer_history WHERE id = :id")
    suspend fun getById(id: Long): TransferHistoryEntry?

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfer_history WHERE id NOT IN (SELECT id FROM transfer_history ORDER BY createdAt DESC, id DESC LIMIT :limit)")
    suspend fun trimToLimit(limit: Int)
}
