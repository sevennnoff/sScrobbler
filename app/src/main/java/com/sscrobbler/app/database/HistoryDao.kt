package com.sscrobbler.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sscrobbler.app.model.ScrobbleStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryItemEntity)

    @Query("SELECT * FROM history_items ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 500): Flow<List<HistoryItemEntity>>

    @Query("SELECT * FROM history_items ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 500): List<HistoryItemEntity>

    @Query("UPDATE history_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: ScrobbleStatus)

    @Query("UPDATE history_items SET artworkUrl = :artworkUrl WHERE id = :id")
    suspend fun updateArtworkUrl(id: String, artworkUrl: String)

    @Query("DELETE FROM history_items WHERE id NOT IN (SELECT id FROM history_items ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun prune(keepCount: Int = 500)

    @Query("DELETE FROM history_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM history_items")
    suspend fun deleteAll()
}
