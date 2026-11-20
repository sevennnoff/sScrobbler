package com.sscrobbler.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sscrobbler.app.model.ScrobbleStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingScrobbleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pendingScrobble: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PendingScrobbleEntity>>

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingScrobbleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(item: HistoryItemEntity)

    @Transaction
    suspend fun enqueue(item: PendingScrobbleEntity, history: HistoryItemEntity) {
        if (insert(item) != -1L) putHistory(history)
    }

    @Query("UPDATE history_items SET status = :status WHERE id IN (:ids)")
    suspend fun setHistoryStatus(ids: List<String>, status: ScrobbleStatus)

    @Transaction
    suspend fun completeBatch(accepted: List<String>, rejected: List<String>) {
        if (accepted.isNotEmpty()) setHistoryStatus(accepted, ScrobbleStatus.Scrobbled)
        if (rejected.isNotEmpty()) setHistoryStatus(rejected, ScrobbleStatus.Failed)
        deleteByIds(accepted + rejected)
    }

    @Query("SELECT * FROM pending_scrobbles ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getBatch(limit: Int = 50): List<PendingScrobbleEntity>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun count(): Int

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_scrobbles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM pending_scrobbles WHERE fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    @Query("DELETE FROM pending_scrobbles")
    suspend fun deleteAll()
}
